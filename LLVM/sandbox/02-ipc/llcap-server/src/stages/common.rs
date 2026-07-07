use std::{
  ffi::CStr,
  io::{Read, Write},
  os::unix::process::ExitStatusExt,
  path::{Path, PathBuf},
  sync::{Arc, Mutex},
  time::Duration,
};

use crate::{
  constants::Constants,
  log::Log,
  modmap::ExtModuleMap,
  shmem_capture::{
    FinalizerInfraInfo, MetadataPublisher,
    hooklib_commons::{META_MEM_NAME, META_MEM_SIZE_NAME, META_SEM_ACK, META_SEM_DATA},
  },
};

use anyhow::{Result, anyhow, bail, ensure};
use tokio::{process::Command, time::timeout};

/// sets up a thread that monitors the child in the background
///
/// the monitor returns a oneshot receiver which will be filled with a boolean flag indicating success or failure of the monitor initialization, the monitor's join handle is also returned
///
/// the monitor performs the IPC finalizing sequence when a program crashes (is terminated by a signal)
///
fn spawn_process_monitor(
  mut child: tokio::process::Child,
  fin_info: FinalizerInfraInfo,
) -> (
  tokio::sync::oneshot::Receiver<bool>,
  tokio::task::JoinHandle<()>,
) {
  let (monitor_ok_tx, monitor_ok_rx) = tokio::sync::oneshot::channel::<bool>();
  // tokio::spawn ensures the monitor runs without an await
  let child_monitor = tokio::spawn(async move {
    let lg = Log::get("child_monitor");
    lg.trace("child monitor launched");
    // attaches to the crucial semaphore
    let fnlzr_infra = match fin_info.try_open() {
      Ok(infra) => infra,
      Err(e) => {
        lg.crit(format!("Failed to init finalizer... manual cleanup most likely required, please terminate llcap-server and perform cleanup (--cleanup)\nError: {e}"));
        // if the send call is Err => the other end of the channel hung up (unreasonable to react here)
        let _ = monitor_ok_tx.send(false);
        return;
      }
    };
    let _ = monitor_ok_tx.send(true);

    // polls with a delay until child exits
    // if exits by signal, artificially terminates the connection via fnlzr_infra
    loop {
      match child.try_wait() {
        Ok(Some(code)) => {
          if let Some(sig) = code.signal() {
            lg.progress(format!("App terminated by signal {sig}"));
            // wait just to be absolutely sure
            std::thread::sleep(Duration::from_millis(300));
            let _ = fnlzr_infra.finalization_flush().inspect_err(|e| lg.crit(format!("Failed to finalize comms... manual cleanup most likely required, please terminate llcap-server and perform cleanup (--cleanup)\nError: {e}")));
            break;
          }
          lg.progress(format!("App terminated with exit code {:?}", code.code()));
          break;
        }
        Ok(_) => (),
        Err(e) => {
          lg.crit(e.to_string());
          break;
        }
      }
      std::thread::sleep(Duration::from_millis(300));
    }
    lg.trace("child monitor stopped");
  });

  (monitor_ok_rx, child_monitor)
}

/// a generic driver function that wraps around the launch of the requested
/// application & the monitoring thread
///
/// `cmd` - command to be executed as a child process
/// `meta_sender` - function that performs metadata sending (according to capture type)
/// `capture` - function performing the capture (more like a closure that wraps the real invokation)
///
/// the function simply abstracts away the child monitor thread
pub async fn drive_instrumented_application<MetadataSender, CaptureHandler, R>(
  mut cmd: Command,
  finalizer_info: FinalizerInfraInfo,
  metadata_svr: Arc<Mutex<MetadataPublisher>>,
  meta_sender: MetadataSender,
  capture: CaptureHandler,
  infra_params: InfraParams,
) -> Result<R>
where
  CaptureHandler: FnOnce() -> Result<R>,
  MetadataSender: FnOnce(&mut MetadataPublisher, InfraParams) -> Result<()>,
{
  let lg = Log::get("app_driver");

  {
    // do not hold accorss awaits
    let mut guard = metadata_svr.lock().unwrap();
    guard.re_new()?;
    meta_sender(&mut guard, infra_params)?;
  }

  let spawned_child = cmd
    .spawn()
    .map_err(|e| anyhow!("Failed to spawn from command {e}"))?;

  let (monitor_ready_rx, child_monitor) = spawn_process_monitor(spawned_child, finalizer_info);
  lg.trace("Waiting for monitor start");
  match timeout(Duration::from_secs(10), monitor_ready_rx).await {
    Ok(Ok(val)) => ensure!(val, "Monitor NOT ready"),
    Err(_) => bail!("Monitor ready timeout"),
    Ok(Err(e)) => bail!("monitor ready error: {}", e),
  }

  lg.progress("Monitor ready, listening!");

  let res = capture()?;

  child_monitor
    .await
    .map_err(|e| anyhow!("Child monitor join {:?}", e))?;
  Ok(res)
}

pub fn obtain_module_map(path: &std::path::PathBuf) -> Result<ExtModuleMap> {
  let lg = Log::get("obtain_module_map");
  let mb_modules = ExtModuleMap::try_from(path);
  match mb_modules {
    Ok(m) => Ok(m),
    Err(e) => {
      lg.crit(format!(
        "Could not parse module mapping from {}:\n{}",
        path.to_string_lossy(),
        e
      ));
      bail!("Could not parse module mapping");
    }
  }
}

pub fn cmd_from_args<T: AsRef<std::ffi::OsStr>>(args: &[T]) -> Result<Command> {
  ensure!(!args.is_empty(), "Command must be specified");
  let mut cmd = tokio::process::Command::new(args.first().unwrap());
  cmd.args(args.iter().skip(1));
  Ok(cmd)
}

pub fn null_terminated_to_string(data: &[u8]) -> Result<String> {
  String::from_utf8(data.split_last().unwrap().1.to_vec()).map_err(|e| anyhow!(e))
}

#[derive(Clone, Copy)]
pub struct InfraParams {
  pub buff_count: u32,
  pub buff_len: u32,
}

pub struct CommonStageParams {
  pub infra: InfraParams,
  pub data_semaphore_name: String,
  pub ack_semaphore_name: String,
  meta_mem_name_null_term: Vec<u8>,
  meta_mem_size_name_null_term: Vec<u8>,
}

impl CommonStageParams {
  pub fn try_initialize(
    buff_count: u32,
    buff_size: u32,
    modules_path: &PathBuf,
  ) -> Result<(Self, ExtModuleMap)> {
    let modules = obtain_module_map(modules_path)?;
    let sem_str = null_terminated_to_string(META_SEM_DATA)?;
    let ack_str = null_terminated_to_string(META_SEM_ACK)?;
    ensure!(
      buff_size % 8 == 0,
      "Buffer size must be a multiple of 8 due to alignment requirements (thread ID)"
    );
    // this is a hooklib limit and must be kept in sync
    // use e2e tests to check for validity of this value (run tests with buffer size equal to min_buff_size())
    ensure!(
      buff_size >= Constants::min_buff_size(),
      "Buffer size must be larger (at least {})",
      Constants::min_buff_size()
    );
    Ok((
      Self {
        infra: InfraParams {
          buff_count,
          buff_len: buff_size,
        },
        data_semaphore_name: sem_str,
        ack_semaphore_name: ack_str,
        meta_mem_name_null_term: META_MEM_NAME.to_vec(),
        meta_mem_size_name_null_term: META_MEM_SIZE_NAME.to_vec(),
      },
      modules,
    ))
  }

  pub fn shmem_path_cstr(&self) -> Result<(&CStr, &CStr)> {
    Ok((
      std::ffi::CStr::from_bytes_with_nul(&self.meta_mem_name_null_term).map_err(|e| anyhow!(e))?,
      std::ffi::CStr::from_bytes_with_nul(&self.meta_mem_size_name_null_term)
        .map_err(|e| anyhow!(e))?,
    ))
  }
}

pub fn get_thread_counts_path(root: &Path) -> PathBuf {
  root.join("thread_call_counts.txt")
}

pub fn dump_thread_counts(root: &Path, counts: &[u64]) -> Result<usize> {
  let line = counts
    .iter()
    .map(|v| format!("{v}"))
    .fold(String::new(), |acc, v| format!("{acc} {v}"))
    + "\n";
  let file_path = get_thread_counts_path(root);
  Log::get("dump_thread_counts").trace(format!("dumping thread counts {}", file_path.display()));
  let mut file = std::fs::File::create(file_path).map_err(|e| anyhow!(e))?;
  file.write(line.as_bytes()).map_err(|e| anyhow!(e))
}

pub fn read_thread_counts(root: &Path) -> Result<Vec<u64>> {
  let file_path = get_thread_counts_path(root);
  let mut line = String::new();

  let lg = Log::get("read_thread_counts");
  lg.trace(format!("parsing thread counts {}", file_path.display()));

  std::fs::File::open(file_path)
    .map_err(|e| anyhow!(e))?
    .read_to_string(&mut line)
    .map_err(|e| anyhow!(e))?;

  let newline = line.char_indices().find(|v| v.1 == '\n');
  ensure!(newline.is_some(), "newline not found");

  let str_list = line.split_at(newline.unwrap().0).0;
  str_list
    .split(' ')
    .skip(1) // see the encoding, there is a leading space on the line, we're skipping it here
    .try_fold(Vec::new(), |mut acc, v| {
      let val = v
        .parse::<u64>()
        .map_err(|e| anyhow!("Parsing tid {v}: {e}"))?;
      acc.push(val);
      Ok(acc)
    })
}
