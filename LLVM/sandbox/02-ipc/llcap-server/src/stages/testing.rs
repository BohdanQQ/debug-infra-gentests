use std::{
  fmt::Debug,
  fs::{self, File},
  mem,
  os::unix::process::ExitStatusExt,
  path::{Path, PathBuf},
  process::{ExitStatus, Stdio},
  sync::{Arc, Mutex, atomic::AtomicBool},
  time::Duration,
};

use anyhow::{Result, anyhow, bail, ensure};
use num_traits::Zero;
use tokio::{
  io::{AsyncReadExt, BufReader},
  net::{UnixListener, UnixStream, unix::OwnedWriteHalf},
  process::{Child, Command},
  sync::oneshot::{Receiver, Sender},
  time::{sleep, timeout},
};

use crate::{
  args::PacketInspecSpec,
  libc_wrappers::get_child_exit_code_with_timeout,
  log::{IntoLogString, Log, LogStrategy},
  modmap::{ExtModuleMap, IntegralFnId, IntegralModId, NumFunUid},
  phase::try_lock_anhw,
  shmem_capture::{
    MetadataPublisher,
    hooklib_commons::{
      CLI_MSG_SIZE, TAG_EXC, TAG_EXIT, TAG_FATAL, TAG_PASS, TAG_PKT, TAG_SGNL, TAG_START,
      TAG_TEST_END, TAG_TEST_FINISH, TAG_TIMEOUT, TEST_SERVER_SOCKET_NAME,
    },
    send_test_metadata,
  },
  stages::{
    self,
    arg_capture::PacketReader,
    common::{CommonStageParams, InfraParams, cmd_from_args, null_terminated_to_string},
    test_registry::{TestID, TestRegisryItem, TestRegistry},
  },
};

use super::arg_capture::PacketProvider;

#[derive(Clone, Debug)]
pub struct LogResult {
  pub call: CallIndexT,
  pub uid: NumFunUid,
  pub pkt: PacketIndexT,
  pub thread_lid: ThreadLidT,
  pub status: TestStatus,
}

impl LogResult {
  pub const fn from_test(t: &TestRegisryItem, status: TestStatus) -> Self {
    Self {
      call: CallIndexT(t.call_index.0 + 1),
      uid: t.uid,
      pkt: t.packet_index,
      thread_lid: t.thread_lid,
      status,
    }
  }
}

impl IntoLogString for LogResult {
  fn get_log_string(&self, log_strat: &mut LogStrategy) -> String {
    let Self {
      call,
      uid,
      pkt,
      status,
      thread_lid,
    } = self;
    match log_strat {
      LogStrategy::StdOut | LogStrategy::PlainText(_) => format!(
        "{:^5}|{:^11}|{:^13}|{:^8}|{:^8}|{status:?}",
        thread_lid.0,
        uid.module_id.hex_string(),
        uid.function_id.hex_string(),
        call.0,
        pkt.0,
      ),
      LogStrategy::Json {
        file: _,
        first,
        detail,
      } => {
        let def_modid = uid.module_id.hex_string();
        let def_fnid = uid.function_id.hex_string();
        let (module_id, fn_id, packet_hex) = match detail {
          crate::log::Detail::Normal => (def_modid, def_fnid, None),
          crate::log::Detail::Detailed(mods, packets) => {
            let modid = mods
              .get_module_string_id(uid.module_id)
              .unwrap_or(&def_modid)
              .to_owned();
            let fnid = mods.get_function_name(*uid).unwrap_or(&def_fnid).to_owned();
            let hex = packets
              .try_read_packet(*uid, pkt.0 as usize)
              .unwrap_or(vec![])
              .iter()
              .map(|v| format!("{v:02X}"))
              .fold(String::new(), |acc, v| acc + &v);
            (modid, fnid, Some(hex))
          }
        };

        format!(
          "{}\n\t{{\n\t\t\"thread_id\":\"{}\",\n\t\t\"module_id\":\"{module_id}\",\n\t\t\"function_id\":\"{fn_id}\",\n\t\t\"call_n\":{},\n\t\t\"packet_idx\":{}{}\n\t\t\"status\":\"{status:?}\"\n\t}}",
          if *first { "" } else { "," },
          thread_lid.0,
          call.0,
          pkt.0,
          packet_hex.map_or_else(
            || ",".to_owned(),
            |hex| format!(",\n\t\t\"packet_hex\":\"{hex}\",")
          ),
        )
      }
    }
  }
}

pub type TestResults = Vec<LogResult>;

/// returns the path to the socket used to communicate with test instances
/// (e.g. supply function argument values, communicate start/end of a test session)
pub fn test_server_socket() -> String {
  null_terminated_to_string(TEST_SERVER_SOCKET_NAME)
    .expect("Failed to convert null-terminated socket name")
}

/// implements the central "dispatch" server
/// to which all test instances (test coordinators) connect
///
/// this future signals readiness (listening for connections) via `ready_tx`
/// and periodically checks `end_rx` which orders this future (and the server) to terminate
pub async fn test_server_job(
  packet_dir: PathBuf,
  modules: Arc<ExtModuleMap>,
  mem_limit: usize,
  (ready_tx, mut end_rx): (Sender<()>, Receiver<()>),
  results: Arc<Mutex<TestResults>>,
) -> Result<()> {
  let lg = Log::get("test_server_job");
  let path = test_server_socket();
  lg.info(format!("Starting at {path}"));
  let listener = UnixListener::bind(path.clone())?;
  ready_tx
    .send(())
    .map_err(|()| anyhow!("Receiver dropped"))?;
  lg.info("Listening");

  let mut handles = vec![];
  // this stop flags is an emergency info relay
  // basically, if the "global timeout" terminates the process, the
  // client_stream does not "close" properly and keeps timing out instead of
  // giving an error
  // this flag will be set for all "test_coordinator_case_handler" that have not yet
  // stopped serving their client after the server is instructed to stop
  let test_job_stop_flag: Arc<AtomicBool> = Arc::new(AtomicBool::new(false));

  while end_rx.try_recv().is_err() {
    // use timeout to be able to listen to end_rx
    match timeout(Duration::from_millis(100), listener.accept()).await {
      Ok(Ok((mut client_stream, client_addr))) => {
        lg.trace(format!("Connected test {client_addr:?}"));
        let results = results.clone(); // required to send this to the test_coordinator_case_handler future (cloning the mutex)

        // first message will always be the PID of the coordinator
        // PID is - the original process' PID or the PID of the fork (MT support)
        let pid = client_stream.read_u32_le().await?;

        handles.push(tokio::spawn(test_coordinator_case_handler(
          pid,
          client_stream,
          packet_dir.clone(),
          modules.clone(),
          mem_limit,
          results,
          test_job_stop_flag.clone(),
        )));
      }
      Ok(Err(e)) => Err(anyhow!(e))?,
      Err(_) => (), // timeout
    }
  }

  lg.info("Finishing");
  for handle in handles {
    test_job_stop_flag.store(true, std::sync::atomic::Ordering::Relaxed);
    if let Err(e) = handle.await? {
      lg.crit(format!("A job has finished with error: {e}"));
    }
  }

  // socket cleanup when server job terminates: making sure this resource is freed before deleting the underlying descriptor
  mem::drop(listener);
  fs::remove_file(path)?;
  Ok(())
}

#[derive(PartialEq, Eq, Debug, Clone, Copy)]
pub struct CallIndexT(pub u32);
#[derive(PartialEq, Eq, Debug, Clone, Copy)]
pub struct PacketIndexT(pub u64);
#[derive(PartialEq, Eq, Debug, Clone, Copy)]
pub struct ThreadLidT(pub u64);
#[derive(Debug, Clone)]
/// message received from the test client (test coordinator)
enum TestMessage {
  /// test started
  Start(NumFunUid, CallIndexT),
  /// test requested argument packet (payload is the packet index)
  PacketRequest(PacketIndexT),
  /// test (testing a packet index) ended with a status
  TestEnd(PacketIndexT, ThreadLidT, TestStatus),
  /// entire test session ended
  End,
}

#[derive(Debug, Clone)]
pub enum TestStatus {
  Pass,
  Exception,
  Timeout,
  GlobalTimeout,
  #[allow(dead_code)] // used by Debug
  Exit(i32),
  #[allow(dead_code)]
  Signal(i32),
  /// an unexpected test failure outside the sandboxing and monitoring of the test coordinator
  /// - could be a test coordinator crash or a test job crash
  #[allow(dead_code)]
  Fatal(String),
  /// possibly spurious error cause (might be masked by a success that came from the coordinator)
  #[allow(dead_code)]
  Spurious(String),
}

impl From<ExitStatus> for TestStatus {
  fn from(value: ExitStatus) -> Self {
    if let Some(sig) = value.signal() {
      return Self::Signal(sig);
    }

    if let Some(code) = value.code() {
      return Self::Exit(code);
    }

    Self::Spurious(format!("Exit status: {value:?}"))
  }
}

// note: TAG_* constants are common for the hook library and llcap-server (generated by bindgen)

const CLI_STATUS_SZ: usize = 6;
impl TryFrom<&[u8; CLI_STATUS_SZ]> for TestStatus {
  type Error = String;

  /// format of [`TestStatus`] messages:
  ///
  /// `| TAG: 2B | payload: 0-4B |`
  ///
  /// payload is either empty for [`Timeout`][`TestStatus::Timeout`], [`Exception`][`TestStatus::Exception`], [`Pass`][`TestStatus::Pass`] and [`Fatal`][`TestStatus::Fatal`] variants
  /// or and 4B of [`i32`] representing either the test's
  /// [`Signal`][`TestStatus::Signal`] or [`Exit`][`TestStatus::Exit`] code
  fn try_from(value: &[u8; CLI_STATUS_SZ]) -> Result<Self, Self::Error> {
    let (tag, data) = value.split_at(2);
    if tag.starts_with(&TAG_PASS.to_le_bytes()) {
      Ok(Self::Pass)
    } else if tag.starts_with(&TAG_EXC.to_le_bytes()) {
      Ok(Self::Exception)
    } else if tag.starts_with(&TAG_TIMEOUT.to_le_bytes()) {
      Ok(Self::Timeout)
    } else if tag.starts_with(&TAG_EXIT.to_le_bytes()) {
      let sized: [u8; 4] = data
        .try_into()
        .map_err(|e: std::array::TryFromSliceError| e.to_string())?;
      Ok(Self::Exit(i32::from_le_bytes(sized)))
    } else if tag.starts_with(&TAG_SGNL.to_le_bytes()) {
      let sized: [u8; 4] = data
        .try_into()
        .map_err(|e: std::array::TryFromSliceError| e.to_string())?;
      Ok(Self::Signal(i32::from_le_bytes(sized)))
    } else if tag.starts_with(&TAG_FATAL.to_le_bytes()) {
      Ok(Self::Fatal("test coordinator".to_owned()))
    } else {
      Err(format!("Invalid status format: {value:?} {tag:?} {data:?}"))
    }
  }
}

/// extracts the first 4 bytes into a u32 from a starting offset
pub fn consume_to_u32(bytes: &[u8], start: usize) -> Result<u32, String> {
  match [
    bytes.get(start),
    bytes.get(start + 1),
    bytes.get(start + 2),
    bytes.get(start + 3),
  ] {
    [Some(a), Some(b), Some(c), Some(d)] => Ok(u32::from_le_bytes([*a, *b, *c, *d])),
    _ => Err("Invalid data".to_owned()),
  }
}

// note: TAG_* constants are common for the hook library and llcap-server (generated by bindgen)
// same for MSG_SIZE, derived here from CLI_MSG_SIZE

const MSG_SIZE: usize = CLI_MSG_SIZE as usize;

impl TryFrom<&[u8; MSG_SIZE]> for TestMessage {
  type Error = String;

  /// format of test messages
  ///
  /// `| TAG: 2B | payload: 0-14B |`
  ///
  /// payload is empty for ([`End`][`TestMessage::End`])
  /// or consists of 2x4B IDs for ([`Start`][`TestMessage::Start`])
  /// or ([`PacketRequest`][`TestMessage::PacketRequest`]) 8B of the packet index
  /// or ([`TestEnd`][`TestMessage::TestEnd`]) 8B of the packet index + (2 - 6B) representing a [`TestStatus`]
  fn try_from(value: &[u8; MSG_SIZE]) -> Result<Self, Self::Error> {
    let (tag, data) = value.split_at(2);
    if tag.starts_with(&TAG_START.to_le_bytes()) {
      Ok(Self::Start(
        (
          IntegralModId(consume_to_u32(data, 0)?),
          IntegralFnId(consume_to_u32(data, 4)?),
        )
          .into(),
        CallIndexT(consume_to_u32(data, 8)?),
      ))
    } else if tag.starts_with(&TAG_PKT.to_le_bytes()) {
      let pkt_idx_bytes: [u8; 8] = data
        .split_at(8)
        .0
        .try_into()
        .map_err(|e: std::array::TryFromSliceError| e.to_string() + " pktrq")?;
      Ok(Self::PacketRequest(PacketIndexT(u64::from_le_bytes(
        pkt_idx_bytes,
      ))))
    } else if tag.starts_with(&TAG_TEST_END.to_le_bytes()) {
      let (t1, t2) = data.split_at(8);
      let thread_lid: [u8; 8] = t1
        .try_into()
        .map_err(|e: std::array::TryFromSliceError| e.to_string() + " thread lid")?;
      let (s1, s2) = t2.split_at(8);
      let pkt_idx_bytes: [u8; 8] = s1
        .try_into()
        .map_err(|e: std::array::TryFromSliceError| e.to_string() + " msgend")?;
      let status_bytes: [u8; CLI_STATUS_SZ] = s2
        .try_into()
        .map_err(|e: std::array::TryFromSliceError| e.to_string() + " msg status")?;
      Ok(Self::TestEnd(
        PacketIndexT(u64::from_le_bytes(pkt_idx_bytes)),
        ThreadLidT(u64::from_le_bytes(thread_lid)),
        TestStatus::try_from(&status_bytes)?,
      ))
    } else if tag.starts_with(&TAG_TEST_FINISH.to_le_bytes()) {
      Ok(Self::End)
    } else {
      Err(format!("Invalid msg format: {value:?}"))
    }
  }
}

#[derive(Debug)]
/// the state of a test session (single test case with concrete packet index), updates of this state are communicated by the test coordinator
enum ClientState {
  /// test session is starting, no message other than "start" is expected
  Init,
  /// test session started, testing a particular function at a particular call index
  /// expects packet requests
  Started(NumFunUid, CallIndexT),
  /// test session has ended, no more messages are expected
  Ended,
}

/// this future represents the handling of the test coordinator's test case (a specific function id + call index)
/// on test case end, `results` are updated
async fn test_coordinator_case_handler(
  pid: u32,
  stream: UnixStream,
  packet_dir: PathBuf,
  modules: Arc<ExtModuleMap>,
  mem_limit: usize,
  results: Arc<Mutex<TestResults>>,
  stop_flag: Arc<AtomicBool>,
) -> Result<()> {
  // initialize state, packet supply and the stream for reading and writing to the client on the other side
  let mut state = ClientState::Init;
  let get_logger =
    |state: &ClientState| Log::get(&format!("test_coordinator_case_handler({pid})@{state:?}"));
  let mut lg = get_logger(&state);
  let mut packets = PacketReader::new(&packet_dir, &modules, mem_limit)?;
  let (read, write) = stream.into_split();
  let mut buff_stream = BufReader::new(read);

  loop {
    // preallocate 16bytes for client messages
    let mut data = [0u8; MSG_SIZE];
    // polling with timeout to check for client status updates
    // note: this matcher "passes" only if data is received from the socket
    match timeout(Duration::from_millis(100), buff_stream.read(&mut data)).await {
      Ok(Ok(0)) => {
        bail!("Client closed connection - ending, state: {state:?}");
      }
      Ok(Ok(n)) => {
        if n != data.len() {
          bail!(
            "Expected {} bytes, got {n} - ending, state: {state:?}",
            data.len()
          );
        }
        // we received a message, we continue to message handling
      }
      Ok(Err(e)) => {
        bail!("Not readable {e} - ending, state: {state:?}");
      }
      Err(_) => {
        // see test_server_job
        if stop_flag.load(std::sync::atomic::Ordering::Relaxed) {
          return Ok(());
        }
        continue;
      } // timeout
    }

    lg.trace(format!("Read done: {data:02X?}"));
    let msg = TestMessage::try_from(&data).map_err(|e| anyhow!(e))?;

    let (new_state, response) = handle_client_msg(state, msg, &mut packets, &results)?;
    state = new_state;
    lg = get_logger(&state); // rename logger for a new state
    if matches!(state, ClientState::Ended) {
      lg.trace("Client ended");
      return Ok(());
    }

    lg.trace(format!("Response: {response:02X?}"));
    if response.is_none() {
      continue; // no response required
    }

    send_protocol_response(&write, &response.unwrap()).await?;
  }
}

/// Ok variant contains a new client state and an optional response - if none, no response
/// shall be sent
///
/// implements a simple state machine (states via [`ClientState`], messages via [`TestMessage`]):
///
/// ```
///                                     v----EndTest/PacketRequest----+
/// | Init | ----- Start/End ----> | Started | -----------------------+----End----> | Ended |
///
/// ```
/// all other transitions are considered an error
///
/// returns the next state and an optional response to the client (or an error)
fn handle_client_msg(
  state: ClientState,
  msg: TestMessage,
  packets: &mut dyn PacketProvider,
  results: &Arc<Mutex<TestResults>>,
) -> Result<(ClientState, Option<Vec<u8>>)> {
  let lg = Log::get("handle_client_msg");
  match state {
    ClientState::Init => match msg {
      TestMessage::Start(id, i) => Ok((ClientState::Started(id, i), None)),
      TestMessage::End => Ok((ClientState::Ended, None)),
      _ => bail!("Invalid transition from Init state with msg {msg:?}"),
    },
    ClientState::Started(id, call_idx) => match msg {
      TestMessage::TestEnd(test_index, thread_lid, status) => {
        let test_result = LogResult {
          uid: id,
          call: call_idx,
          pkt: test_index,
          status,
          thread_lid,
        };
        lg.info(format!("test ended: {test_result:?}"));
        try_lock_anhw(results)?.push(test_result);
        Ok((state, None))
      }
      TestMessage::PacketRequest(idx) => {
        lg.trace(format!("{msg:?}"));
        let response = packets.get_packet(id, usize::try_from(idx.0)?);
        Ok((state, response))
      }
      TestMessage::End => Ok((ClientState::Ended, None)),
      TestMessage::Start(..) => bail!("Invalid transition from Started state with msg {msg:?}"),
    },
    ClientState::Ended => bail!(
      "Client state is Ended, no more messages were expected msg: {msg:?}, from state: {state:?}"
    ),
  }
}

async fn raw_send_to_client(stream: &OwnedWriteHalf, data: &[u8]) -> Result<()> {
  let mut idx = 0;
  loop {
    stream.writable().await?;
    // not expecting too much contention/throughput, busy looping seems okay
    match stream.try_write(data.split_at(idx).1) {
      Ok(n) => {
        if n == data.len() {
          return Ok(());
        }
        idx += n;
      }
      Err(ref e) if e.kind() == tokio::io::ErrorKind::WouldBlock => (),
      Err(e) => {
        bail!(e.to_string());
      }
    }
  }
}

/// sends response in accordance with the comms protocol between the test client and this server
/// (length + payload)
async fn send_protocol_response(write_stream: &OwnedWriteHalf, response: &[u8]) -> Result<()> {
  // send response length + response
  raw_send_to_client(
    write_stream,
    &u32::try_from(response.as_ref().len())?.to_le_bytes(),
  )
  .await?;

  raw_send_to_client(write_stream, response).await
}

#[derive(Clone, Debug)]
pub struct TestJobFailure {
  pub log_res: LogResult,
}

impl TestJobFailure {
  pub fn from_test<T: AsRef<str> + Clone>(
    t: &TestRegisryItem,
    message: T,
    status: Option<TestStatus>,
  ) -> Self {
    Self {
      log_res: LogResult::from_test(
        t,
        status.unwrap_or_else(|| TestStatus::Fatal(String::from(message.as_ref()))),
      ),
    }
  }
}

pub async fn singular_test_job(
  metadata_svr: Arc<Mutex<MetadataPublisher>>,
  infra_params: InfraParams,
  test: &TestRegisryItem,
  cmdline: &[&str],
  output_gen: Arc<Option<TestOutputPathGen>>,
) -> Result<TestStatus, TestJobFailure> {
  // lambda transforming the test errors to proper return values
  let mk_error = |error: anyhow::Error, status: TestStatus| {
    TestJobFailure::from_test(test, error.to_string(), Some(status))
  };
  let lg = Log::get("singular_test_job");
  // prepare job parameters
  {
    lg.trace(format!("Test: {test:?}"));

    let mut guard = metadata_svr.lock().unwrap();
    guard.re_new().map_err(|v| {
      TestJobFailure::from_test(
        test,
        v.to_string(),
        TestStatus::Fatal("Failed to re-new semaphores".to_owned()).into(),
      )
    })?;

    send_test_metadata(&mut guard, infra_params, test, None)
      .map_err(|e| mk_error(e, TestStatus::Timeout))?;
  }
  // prepare the "command", mainly the std out/err
  let mut cmd = cmd_from_args(cmdline)
    .map_err(|e| mk_error(e, TestStatus::Fatal("Command creation".to_owned())))?;
  if let Some(output_gen) = output_gen.as_ref() {
    let out_path = output_gen.get_out_path(test, "");
    let err_path = output_gen.get_err_path(test, "");
    cmd.stdout(Stdio::from(File::create(out_path.clone()).map_err(
      |e| {
        mk_error(
          anyhow!("Stdout file creation failed: {e} {}", out_path.display()),
          TestStatus::Fatal("Stdout create".to_owned()),
        )
      },
    )?));
    cmd.stderr(Stdio::from(File::create(err_path).map_err(|e| {
      mk_error(
        anyhow!("Stderr file creation failed {e} {}", out_path.display()),
        TestStatus::Fatal("Stderr create".to_owned()),
      )
    })?));
  }
  // launch the test
  let test_process = cmd.spawn().map_err(|e| {
    mk_error(
      anyhow!("spawn from command: {e}"),
      TestStatus::Fatal("Spawn".to_owned()),
    )
  })?;

  lg.progress(format!(
    "PID of the program under test: {:?}",
    test_process.id()
  ));

  let result = wait_or_terminate(test_process, test).await;

  sleep(Duration::from_millis(300)).await;
  lg.trace(format!("final status: {result:?}"));

  match &result {
    TestStatus::Fatal(m) | TestStatus::Spurious(m) => {
      Err(TestJobFailure::from_test(test, m.clone(), Some(result)))
    }
    _ => Ok(result),
  }
}

impl CheckpointedTesting {
  pub async fn singular_restore_job(
    &self,
    metadata_svr: Arc<Mutex<MetadataPublisher>>,
    test: &TestRegisryItem,
    dump_path: &str,               // restore from where
    checkpoint_path: Option<&str>, // checkpoint if applicable
  ) -> Result<TestStatus, TestJobFailure> {
    let infra_params = self.common.params.infra;
    let output_gen = &self.output_generator;
    let paths = self
      .last_checkpoint_paths
      .as_ref()
      .ok_or(TestJobFailure::from_test(
        test,
        "Checkpoint was not done before the test",
        None,
      ))?;
    output_gen
      .reload_before_restore(paths)
      .map_err(|e| TestJobFailure::from_test(test, format!("Failed to restore {e}"), None))?;

    // lambda transforming the test errors to proper return values
    let mk_error = |error: anyhow::Error, status: TestStatus| {
      TestJobFailure::from_test(test, error.to_string(), Some(status))
    };
    let lg = Log::get("singular_restore_job");
    // prepare job parameters
    let dump_path = {
      let mut guard = metadata_svr.lock().unwrap();
      guard.re_new().map_err(|v| {
        TestJobFailure::from_test(
          test,
          v.to_string(),
          TestStatus::Fatal("Failed to re-new semaphores".to_owned()).into(),
        )
      })?;
      lg.trace(format!("Test: {test:?}"));

      send_test_metadata(&mut guard, infra_params, test, checkpoint_path)
        .map_err(|e| mk_error(e, TestStatus::Timeout))?;
      dump_path.to_string()
    };

    const PIDFILE: &str = "/tmp/llcap-criu-pidfile";
    // must be removed in order for the PID to get written
    let _ = tokio::fs::remove_file(PIDFILE).await;

    lg.info(format!("Restoring from {dump_path}"));
    // prepare the "command", mainly the std out/err
    let mut cmd = vec![
      "criu",
      "restore",
      // for unprivileged runs (Not working currently)
      // "--unprivileged",
      // the following are required for exit code tracking
      // combining the two options with --pidfile allows child tracking and therefore exit status tracking
      // https://criu.org/Tree_after_restore
      "--restore-detached",
      "--restore-sibling",
      "--pidfile",
      PIDFILE,
      // dump path
      "-D",
      &dump_path,
    ];
    if Log::is_debug() {
      cmd.push("-v4");
    }

    let mut cmd: Command = cmd_from_args(&cmd)
      .map_err(|e| mk_error(e, TestStatus::Fatal("Command creation".to_owned())))?;

    // launch the restore
    let mut restore_process = cmd.spawn().map_err(|e| {
      mk_error(
        anyhow!("spawn restore from command: {e}"),
        TestStatus::Fatal("Spawn".to_owned()),
      )
    })?;

    lg.info(format!(
      "PID of the restore process: {:?}",
      restore_process.id()
    ));
    restore_process.wait().await.map_err(|v| {
      TestJobFailure::from_test(
        test,
        &format!("{v}"),
        Some(TestStatus::Fatal(
          "Failed to wait for the CRIU restore".to_owned(),
        )),
      )
    })?;

    let (test_pid, test_exit) = wait_restored_test(test, &PathBuf::from(PIDFILE))?;
    let test_exit = status_or_kill(test, test_pid, test_exit).await?;

    let result = TestStatus::from(test_exit);
    sleep(Duration::from_millis(300)).await;
    lg.trace(format!("final status: {test_exit:?} from {result:?}"));
    output_gen
      .persist_after_restore(test, paths)
      .map_err(|e| TestJobFailure::from_test(test, format!("Failed to persist {e}"), None))?;

    match &result {
      TestStatus::Fatal(m) | TestStatus::Spurious(m) => {
        Err(TestJobFailure::from_test(test, m.clone(), Some(result)))
      }
      _ => Ok(result),
    }
  }

  fn get_criu_path(
    &self,
    i: &CRIUCheckpointIndex,
    test_for_err: &TestRegisryItem,
  ) -> std::result::Result<String, TestJobFailure> {
    Ok(
      self
        .output_generator
        .get_criu_dump(i.0)
        .map_err(|e| {
          TestJobFailure::from_test(
            test_for_err,
            format!("{e}"),
            TestStatus::Fatal("Invalid checkpoint dir path".to_owned()).into(),
          )
        })?
        .to_string_lossy()
        .to_string(),
    )
  }

  // starts a checkpointing job
  pub async fn singular_checkpointing_job(
    &self,
    metadata_svr: Arc<Mutex<MetadataPublisher>>,
    test: &TestRegisryItem,
    mode: CheckpointJobMode<'_>,
  ) -> Result<TestStatus, TestJobFailure> {
    // either we're starting execution or we are restoring and running up until the next checkpoint
    let do_checkpoint = match test.mode {
      stages::test_registry::TestingMode::Testing
      | stages::test_registry::TestingMode::MTCompatTesting => {
        return Err(TestJobFailure::from_test(
          test,
          "invalid test mode for checkpoint".to_owned(),
          None,
        ));
      }
      stages::test_registry::TestingMode::CheckpointedTesting(do_checkpoint) => do_checkpoint,
    };
    if !do_checkpoint {
      return Err(TestJobFailure::from_test(
        test,
        "invalid test: expected do_checkpoint to be true".to_owned(),
        None,
      ));
    }
    let lg = Log::get("singular_checkpointing_job");
    // lambda transforming the test errors to proper return values
    let mk_error = |err, stat| mk_fail(test, err, stat);

    match mode {
      CheckpointJobMode::Restore(idx) => {
        let checkpoint_path = self.get_criu_path(&CRIUCheckpointIndex(idx), test)?;
        // must perform restoration based on the previous index
        let previous_cpoint_path = self.get_criu_path(&CRIUCheckpointIndex(idx - 1), test)?;
        // restore job from a checkpoint path with specific parameters
        self
          .singular_restore_job(
            metadata_svr,
            test,
            &previous_cpoint_path,
            Some(&checkpoint_path),
          )
          .await
      }
      CheckpointJobMode::New(cmd, idx) => {
        let checkpoint_path = self.get_criu_path(&CRIUCheckpointIndex(idx), test)?;
        // performing a new checkpoint
        // prepare job parameters
        {
          lg.trace(format!("Checkpoint new Test: {test:?}"));

          let mut guard = metadata_svr.lock().unwrap();
          guard.re_new().map_err(|v| {
            TestJobFailure::from_test(
              test,
              v.to_string(),
              TestStatus::Fatal("Failed to re-new semaphores".to_owned()).into(),
            )
          })?;
          send_test_metadata(
            &mut guard,
            self.common.params.infra,
            test,
            Some(&checkpoint_path),
          )
          .map_err(|e| mk_error(e, TestStatus::Timeout))?;
        };

        // prepare the "command", mainly the std out/err
        let mut cmd = cmd_from_args(cmd)
          .map_err(|e| mk_error(e, TestStatus::Fatal("Command creation".to_owned())))?;
        let out_path = self.output_generator.get_out_path(test, "");
        let err_path = self.output_generator.get_err_path(test, "");
        lg.trace(format!(
          "Job {test:?}\n\toutputs: {}, {}",
          out_path.display(),
          err_path.display()
        ));
        cmd
          .stdin(Stdio::null())
          .stdout(Stdio::from(File::create(out_path.clone()).map_err(
            |e| {
              mk_error(
                anyhow!("Stdout file creation failed: {e} {}", out_path.display()),
                TestStatus::Fatal("Stdout create".to_owned()),
              )
            },
          )?))
          .stderr(Stdio::from(File::create(err_path).map_err(|e| {
            mk_error(
              anyhow!("Stderr file creation failed {e} {}", out_path.display()),
              TestStatus::Fatal("Stderr create".to_owned()),
            )
          })?));

        let test_process = cmd.spawn().map_err(|e| {
          mk_error(
            anyhow!("spawn checkpoint from command: {e}"),
            TestStatus::Fatal("Spawn".to_owned()),
          )
        })?;

        lg.progress(format!(
          "PID of the checkpointed program: {:?}",
          test_process.id()
        ));

        let result = wait_or_terminate(test_process, test).await;

        sleep(Duration::from_millis(300)).await;

        lg.trace(format!("final status: {result:?}"));
        match &result {
          TestStatus::Fatal(m) | TestStatus::Spurious(m) => {
            Err(TestJobFailure::from_test(test, m.clone(), Some(result)))
          }
          _ => Ok(result),
        }
      }
    }
  }
}

fn mk_fail(test: &TestRegisryItem, error: anyhow::Error, status: TestStatus) -> TestJobFailure {
  TestJobFailure::from_test(test, error.to_string(), Some(status))
}

async fn status_or_kill(
  test: &TestRegisryItem,
  test_pid: i32,
  test_exit: Option<ExitStatus>,
) -> Result<ExitStatus, TestJobFailure> {
  let Some(test_exit) = test_exit else {
    let mut spawned = cmd_from_args(&["kill", "-9", &test_pid.to_string()])
      .and_then(|mut v| v.spawn().map_err(|e| anyhow!("Kill spawn failed: {e}")))
      .map_err(|e| mk_fail(test, e, TestStatus::Fatal("kill".to_owned())))?;
    spawned
      .wait()
      .await
      .map_err(|e| mk_fail(test, anyhow!(e), TestStatus::Fatal("kill-wait".to_owned())))?;

    return Err(TestJobFailure::from_test(
      test,
      "",
      TestStatus::Timeout.into(),
    ));
  };
  Ok(test_exit)
}

/// Waits for the given test identified by the PID in the pidfile.
/// Assumes pidfile contains decimal-encoded PID of the process (as created by CRIU)
fn wait_restored_test(
  test: &TestRegisryItem,
  pidfile: &PathBuf,
) -> Result<(i32, Option<ExitStatus>), TestJobFailure> {
  let lg = Log::get("wait_restored_test");
  let test_pid = fs::read(pidfile).map_err(|e| {
    TestJobFailure::from_test(
      test,
      "",
      Some(TestStatus::Fatal(format!(
        "Failed to read CRIU restore pidfile: {e}"
      ))),
    )
  })?;
  lg.trace(format!("Pidfile read: {test_pid:?}"));
  let test_pid = String::from_utf8(test_pid).map_err(|e| {
    TestJobFailure::from_test(
      test,
      "",
      Some(TestStatus::Fatal(format!(
        "Failed to parse CRIU restore pidfile: {e}"
      ))),
    )
  })?;
  let test_pid = libc::pid_t::from_str_radix(&test_pid, 10).map_err(|e| {
    TestJobFailure::from_test(
      test,
      "",
      Some(TestStatus::Fatal(format!(
        "Failed to parse CRIU restore PID: {e}"
      ))),
    )
  })?;
  let test_exit = get_child_exit_code_with_timeout(test_pid, test.timeout_test).map_err(|e| {
    TestJobFailure::from_test(
      test,
      "",
      Some(TestStatus::Fatal(format!(
        "Failed to wait for CRIU restore PID {test_pid}: {e}"
      ))),
    )
  })?;
  Ok((test_pid, test_exit))
}

#[derive(Debug)]
pub enum CheckpointJobMode<'a> {
  /// spawning a new job from a command line and a start index
  New(&'a [String], usize),
  /// restoring a job from a checkpoint index
  Restore(usize),
}

struct CRIUCheckpointIndex(usize);

/// waits for or terminates the test instance (child process representing the entire test process) based on a timeout
async fn wait_or_terminate(mut process: Child, test: &TestRegisryItem) -> TestStatus {
  let lg = Log::get("wait_or_terminate");
  let process_timeout = test.timeout_process;
  let kill_children = test.terminate_child();
  // for some reason, the `.wait` call sometimes panics with CRIU support
  // I was unable to track it down, it seems that tokio might expect the termination to mean
  // something different than us
  if let Some(timeout_duration) = process_timeout {
    match tokio::time::timeout(timeout_duration, process.wait()).await {
      Err(_) => {
        lg.crit(format!("Global timeout, killing child, test {test:?}"));
        // this will most likely generate a redundant Timeout/Error test status
        // (we are killing children first, then the monitor)

        // but since we return error from here, there will also be a
        // "Fatal" test status wich should be detected
        // the clutter is okay, since this should not happen
        if kill_children && let Some(pid) = process.id() {
          // kills the children of the tested app (forked by hooklib)
          let _ = Command::new("pkill")
            .args(["-P", &pid.to_string()])
            .spawn()
            .unwrap()
            .wait()
            .await;
        }
        let _ = process.kill().await;

        TestStatus::GlobalTimeout
      }
      Ok(status) => status.map_or_else(
        |e| TestStatus::Spurious(format!("I/O error in wait: {e:?}")),
        TestStatus::from,
      ),
    }
  } else {
    // we ignore "failures" here because if .wait() erred, there is not much we can do, if the
    // test child failed, it can be a perfectly desired result (no point reacting to it)
    // if the waiting failed, it will get logged
    process.wait().await.map_or_else(
      |e| {
        let msg = format!("Test {test:?} failed with error {e}");
        lg.crit(&msg);
        TestStatus::Spurious(msg)
      },
      TestStatus::from,
    )
  }
}

pub struct TestOutputPathGen {
  dir: bool,
  base: PathBuf,
  tmp_dir: PathBuf,
  criu_dumps_dir: PathBuf,
}

impl TestOutputPathGen {
  fn ensure_dir(path: &PathBuf) -> Result<()> {
    ensure!(
      !path.exists() || path.is_dir(),
      format!("Path {} is not a directory!", path.display())
    );
    if !path.exists() {
      std::fs::create_dir(path).map_err(|e| anyhow!(e))?;
    }
    Ok(())
  }

  pub fn make(base: Option<&PathBuf>) -> Result<Option<Self>> {
    let Some(base) = base else { return Ok(None) };
    let tmp_parent = if base.is_dir() {
      base.clone()
    } else {
      ensure!(base.parent().is_some());
      base.parent().unwrap().to_path_buf()
    };

    let folder = Path::new("temp");
    let tmp_path = tmp_parent.join(folder);
    Self::ensure_dir(&tmp_path)?;
    let folder = Path::new("criu-dumps");
    let criu_dumps_dir = tmp_parent.join(folder);
    Self::ensure_dir(&criu_dumps_dir)?;
    Ok(
      Self {
        dir: base.is_dir(),
        tmp_dir: tmp_path,
        base: base.clone(),
        criu_dumps_dir,
      }
      .into(),
    )
  }

  pub fn get_criu_dump(&self, index: usize) -> Result<PathBuf> {
    let path = self.criu_dumps_dir.join(format!("{index}"));
    Self::ensure_dir(&path)
      .map_err(|e| anyhow!(e))
      .map(|()| path)
  }

  pub fn get_out_path(&self, tst: &TestRegisryItem, suffix: &str) -> PathBuf {
    self.get_path(format!(
      "M{}-F{}-{}{}.out",
      tst.uid.module_id.hex_string(),
      tst.uid.function_id.hex_string(),
      tst.id(),
      suffix
    ))
  }

  pub fn get_err_path(&self, tst: &TestRegisryItem, suffix: &str) -> PathBuf {
    self.get_path(format!(
      "M{}-F{}-{}{}.err",
      tst.uid.module_id.hex_string(),
      tst.uid.function_id.hex_string(),
      tst.id(),
      suffix
    ))
  }

  fn get_path(&self, dir_append_variant: String) -> PathBuf {
    if self.dir {
      self.base.join(dir_append_variant)
    } else {
      self.base.clone()
    }
  }

  /// Stores stdout/err to temporary locations after a checkpoint.
  /// The temporary copy is needed for the restoration done later
  /// Returns (out, err) temporary paths used for restoring
  pub fn store_after_checkpoint(
    &self,
    ran_from_scratch: bool,
    tst: &TestRegisryItem,
    paths: Option<&CheckpointPaths>,
  ) -> Result<CheckpointPaths> {
    let from_out = self.get_out_path(tst, "");
    let from_out_per = self.get_out_path(tst, "-per");
    let from_err = self.get_err_path(tst, "");
    let from_err_per = self.get_err_path(tst, "-per");
    ensure!(from_out.file_name().is_some() && from_err.file_name().is_some());
    let temp_out = self.tmp_dir.join(from_out.file_name().unwrap());
    let temp_err = self.tmp_dir.join(from_err.file_name().unwrap());

    // when checkpointing from a restored program, the paths have the -per suffix
    let expected_out = if ran_from_scratch {
      std::fs::copy(&from_out, &temp_out)
        .map_err(|e| anyhow!("Checkpoint scratch copy, out: {e}"))?;
      // this is the very first path, all restored programs use this SINGLE path for their outputs
      // - we are merely replacing it in between runs and restoring it before runs
      Some(from_out)
    } else {
      std::fs::copy(&from_out_per, &temp_out).map_err(|e| anyhow!("Checkpoint copy, out: {e}"))?;
      // if in the -per path -> we have done 2nd, 3rd, ... etc checkpoint, this path is useless
      // - we are only keeping the copy in the temporary path (for copying into the Some(from_out))
      None
    };
    let expected_err = if ran_from_scratch {
      std::fs::copy(&from_err, &temp_err)
        .map_err(|e| anyhow!("Checkpoint scratch copy, err: {e}"))?;
      Some(from_err)
    } else {
      std::fs::copy(&from_err_per, &temp_err).map_err(|e| anyhow!("Checkpoint copy, err: {e}"))?;
      None
    };

    Ok(CheckpointPaths {
      criu_expected: if ran_from_scratch {
        match (expected_out, expected_err) {
          (Some(x), Some(y)) => (x, y),
          _ => bail!("bug"),
        }
      } else {
        match paths {
          Some(x) => x.criu_expected.clone(),
          None => bail!("bug paths"),
        }
      },
      originals: (temp_out, temp_err),
    })
  }

  /// Reloads temporarily stored stdout/err of a test job to allow the restoration to go through.
  pub fn reload_before_restore(&self, previous_paths: &CheckpointPaths) -> Result<()> {
    previous_paths.restore()
  }

  pub fn persist_after_restore(
    &self,
    tst: &TestRegisryItem,
    previous_paths: &CheckpointPaths,
  ) -> Result<()> {
    let out = self.get_out_path(tst, "-per");
    let err = self.get_err_path(tst, "-per");
    ensure!(out.file_name().is_some() && err.file_name().is_some());
    previous_paths.persist(&out, &err)
  }
}

pub fn inspect_packet(
  spec: &PacketInspecSpec,
  modules: &ExtModuleMap,
  reader: &PacketReader,
) -> Result<()> {
  let (fn_uid, pkt_idx) = (spec.0, spec.1);
  let (fnid, modid) = (fn_uid.function_id, fn_uid.module_id);
  let lg = Log::get("inspect_packet");

  let module = modules
    .get_module_string_id(modid)
    .ok_or(anyhow!("Module {} not found", modid.hex_string()))?;
  lg.progress(format!("Module: {module}"));

  let function = modules
    .get_function_name(fn_uid)
    .ok_or(anyhow!("Function {} not found", fnid.hex_string()))?;
  lg.progress(format!("Function: {function}"));

  let len = reader
    .get_packet_count(fn_uid)
    .ok_or(anyhow!("Error, no packets found for function"))? as usize;

  let report_packet = |pkt: &Vec<u8>| {
    lg.progress(format!("Raw packet: {pkt:?}"));
  };

  let desc = modules
    .get_function_arg_size_descriptors(fn_uid)
    .ok_or(anyhow!("Error, no packet description found"))?;

  lg.progress(format!("Packet Description: {desc:?}"));

  match pkt_idx {
    crate::args::PktIdxSpec::Single(mut pkt_idx) => {
      ensure!(pkt_idx < len, "Packet index overflows packet count");
      lg.progress(format!("Packet index: {pkt_idx}"));

      let pkt = loop {
        let pkt = reader.read_next_packet(fn_uid)?;
        if pkt_idx == 0 {
          break pkt;
        }
        pkt_idx -= 1;
      }
      .ok_or(anyhow!("Error reading packet"))?;
      report_packet(&pkt);
      Ok(())
    }
    crate::args::PktIdxSpec::All => {
      let mut counter = 0;
      while let Some(pkt) = reader.read_next_packet(fn_uid)? {
        lg.progress(format!("Packet index: {counter}"));
        report_packet(&pkt);
        counter += 1;
      }
      Ok(())
    }
  }
}

#[derive(Clone, Debug)]
pub struct PartialRegistryItem {
  pub uid: NumFunUid,
  pub test_count: u32,
  pub test_case_timeout: Duration,
  pub global_timeout: Option<Duration>,
  pub call_counts: Arc<Vec<u64>>,
}
struct CommonTestingPhaseStore {
  pub test_registry: TestRegistry,
  pub tests: Vec<TestID>,
  pub params: Arc<CommonStageParams>,
  pub output_generator: Arc<Option<TestOutputPathGen>>,
}

pub trait TestingPhase {
  // prepares test cases (allows post-processing)
  fn prepare_cases(&mut self, command: &[String], partial: &PartialRegistryItem) -> Result<bool>;
  // runs test cases and provides their results
  async fn run_tests(
    &mut self,
    meta: Arc<Mutex<MetadataPublisher>>,
  ) -> Result<(Vec<LogResult>, Vec<TestJobFailure>)>;
  // indicates whether a next batch of prepare-run is needed
  fn next_batch(&self) -> Option<()>;
}

impl CommonTestingPhaseStore {
  pub fn new(
    common_params: Arc<CommonStageParams>,
    out_gen: Arc<Option<TestOutputPathGen>>,
  ) -> Self {
    Self {
      test_registry: TestRegistry::new(),
      tests: vec![],
      params: common_params,
      output_generator: out_gen,
    }
  }
}

pub struct MTSupportTesting {
  common: CommonTestingPhaseStore,
}

impl MTSupportTesting {
  pub fn new(
    common_params: Arc<CommonStageParams>,
    out_gen: Arc<Option<TestOutputPathGen>>,
  ) -> Self {
    Self {
      common: CommonTestingPhaseStore::new(common_params, out_gen),
    }
  }
}

impl TestingPhase for MTSupportTesting {
  fn prepare_cases(&mut self, command: &[String], partial: &PartialRegistryItem) -> Result<bool> {
    self.common.test_registry.clear();
    self.common.tests.clear();

    let tests = &mut self.common.tests;
    let test_count = partial.test_count;
    let timeout_test = partial.test_case_timeout;
    let uid: NumFunUid = partial.uid;
    let thread_counts = &partial.call_counts;
    for (thread_idx, call_count) in thread_counts.iter().enumerate() {
      for call_index in 0..*call_count {
        for packet_index in 0..test_count {
          tests.push(self.common.test_registry.add_new_test(
            TestRegisryItem {
              uid,
              call_index: CallIndexT(u32::try_from(call_index)?),
              packet_index: PacketIndexT(packet_index.into()),
              thread_lid: ThreadLidT(u64::try_from(thread_idx)?),
              thread_count: u32::try_from(thread_counts.len())?,
              test_count,
              timeout_test,
              timeout_process: Some(timeout_test),
              mode: stages::test_registry::TestingMode::MTCompatTesting,
            },
            command,
          ));
        }
      }
    }
    Ok(!self.common.tests.is_empty())
  }

  async fn run_tests(
    &mut self,
    meta: Arc<Mutex<MetadataPublisher>>,
  ) -> Result<(Vec<LogResult>, Vec<TestJobFailure>)> {
    let test_reg = &self.common.test_registry;
    let mut errors: Vec<TestJobFailure> = vec![];
    let results: Arc<Mutex<Vec<LogResult>>> = Arc::new(Mutex::new(vec![]));
    let lg = Log::get("run_tests<MTSupp>");
    for test in &self.common.tests {
      let (tst, cmd) = test_reg
        .get_test_params(*test)
        .ok_or(anyhow!("Inconsistent test registry"))?;
      lg.trace(format!("Start test {tst:?}"));
      let cmdline = cmd.iter().map(String::as_str).collect::<Vec<&str>>();
      let test_job = singular_test_job(
        meta.clone(),
        self.common.params.infra,
        tst,
        &cmdline,
        self.common.output_generator.clone(),
      );

      match test_job.await {
        Err(e) => errors.push(e),
        Ok(status) => try_lock_anhw(&results)?.push(LogResult::from_test(
          test_reg.get_test_params(*test).unwrap().0,
          // maps GlobalTimeout to just Timeout - in the MT compat testing, we don't distinguish those
          if matches!(status, stages::testing::TestStatus::GlobalTimeout) {
            stages::testing::TestStatus::Timeout
          } else {
            status
          },
        )),
      }
    }
    Ok((
      Arc::try_unwrap(results)
        .map_err(|_| anyhow!("Failed to free results out of Arc"))?
        .lock()
        .unwrap()
        .clone(),
      errors,
    ))
  }

  fn next_batch(&self) -> Option<()> {
    None
  }
}

pub struct BasicTesting {
  common: CommonTestingPhaseStore,
}

impl BasicTesting {
  pub fn new(
    common_params: Arc<CommonStageParams>,
    out_gen: Arc<Option<TestOutputPathGen>>,
  ) -> Self {
    Self {
      common: CommonTestingPhaseStore::new(common_params, out_gen),
    }
  }
}

impl TestingPhase for BasicTesting {
  fn prepare_cases(&mut self, command: &[String], partial: &PartialRegistryItem) -> Result<bool> {
    self.common.test_registry.clear();
    self.common.tests.clear();

    let tests = &mut self.common.tests;
    let test_count = partial.test_count;
    let timeout_test = partial.test_case_timeout;
    let uid: NumFunUid = partial.uid;
    let thread_counts = &partial.call_counts;
    for call_idx in 0..test_count {
      tests.push(self.common.test_registry.add_new_test(
        TestRegisryItem {
          uid,
          call_index: CallIndexT(call_idx),
          packet_index: PacketIndexT(0_u64),
          thread_lid: ThreadLidT(0_u64),
          thread_count: u32::try_from(thread_counts.len())?,
          test_count,
          timeout_test,
          timeout_process: partial.global_timeout,
          mode: stages::test_registry::TestingMode::Testing,
        },
        command,
      ));
    }
    Ok(!self.common.tests.is_empty())
  }

  async fn run_tests(
    &mut self,
    meta: Arc<Mutex<MetadataPublisher>>,
  ) -> Result<(Vec<LogResult>, Vec<TestJobFailure>)> {
    let test_reg = &self.common.test_registry;
    let mut errors: Vec<TestJobFailure> = vec![];
    let results: Arc<Mutex<Vec<LogResult>>> = Arc::new(Mutex::new(vec![]));
    let lg = Log::get("run_tests<BasicTesting>");
    for test in &self.common.tests {
      let (tst, cmd) = test_reg
        .get_test_params(*test)
        .ok_or(anyhow!("Inconsistent test registry"))?;
      lg.trace(format!("Start test {tst:?}"));
      let cmdline = cmd.iter().map(String::as_str).collect::<Vec<&str>>();

      let test_job = singular_test_job(
        meta.clone(),
        self.common.params.infra,
        tst,
        &cmdline,
        self.common.output_generator.clone(),
      );

      match test_job.await {
        Err(e) => errors.push(e),
        Ok(status)
          if matches!(
            status,
            stages::testing::TestStatus::GlobalTimeout
              | stages::testing::TestStatus::Spurious(_)
              | stages::testing::TestStatus::Fatal(_)
          ) =>
        {
          try_lock_anhw(&results)?.push(LogResult::from_test(
            test_reg.get_test_params(*test).unwrap().0,
            status,
          ));
        }
        Ok(other) => {
          lg.info(format!("Skipped test result: {other:?}"));
        }
      }
    }
    Ok((
      Arc::try_unwrap(results)
        .map_err(|_| anyhow!("Failed to free results out of Arc"))?
        .lock()
        .unwrap()
        .clone(),
      errors,
    ))
  }

  fn next_batch(&self) -> Option<()> {
    None
  }
}

#[derive(Copy, Clone, Debug)]
enum CheckpointState {
  PureStart,
  Checkpoint(ThreadLidT, CallIndexT),
  End,
}

#[derive(Debug)]
pub struct CheckpointPaths {
  originals: (PathBuf, PathBuf),
  // criu expects the original files on these paths
  criu_expected: (PathBuf, PathBuf),
}

impl CheckpointPaths {
  pub fn restore(&self) -> Result<()> {
    for (frm, to) in [
      (&self.originals.0, &self.criu_expected.0),
      (&self.originals.1, &self.criu_expected.1),
    ] {
      let lg = Log::get("output_restore");
      lg.trace(format!("{frm:?} -> {to:?}"));
      std::fs::copy(frm, to)
        .map_err(|e| anyhow!("Checkpoint restore, out: {e}, from: {frm:?}, to: {to:?}"))?;
    }

    Ok(())
  }

  pub fn persist(&self, out: &Path, err: &Path) -> Result<()> {
    for (frm, to) in [(&self.criu_expected.0, out), (&self.criu_expected.1, err)] {
      let lg = Log::get("output_persist");
      lg.trace(format!("{frm:?} -> {to:?}"));
      std::fs::copy(frm, to)
        .map_err(|e| anyhow!("Persist, out: {e}, from: {frm:?}, to: {to:?}"))?;
    }

    Ok(())
  }
}

pub struct CheckpointedTesting {
  common: CommonTestingPhaseStore,
  upcoming_checkpoint: CheckpointState,
  last_checkpoint_paths: Option<CheckpointPaths>,
  output_generator: Arc<TestOutputPathGen>,
  // test item & the command line
  // presence (as a whole)           => Perform checkpoint
  // presence of command line vector => Start from scratch, don't checkpoint
  // (conversly, restore a checkpoint and retarget if not present)
  checkpointing_testcase: Option<(TestRegisryItem, Vec<String>)>,
}

impl CheckpointedTesting {
  pub fn new(common_params: Arc<CommonStageParams>, out_gen: Arc<TestOutputPathGen>) -> Self {
    Self {
      common: CommonTestingPhaseStore::new(common_params, Arc::new(None)),
      upcoming_checkpoint: CheckpointState::PureStart,
      checkpointing_testcase: None,
      output_generator: out_gen,
      last_checkpoint_paths: None,
    }
  }
  fn make_checkpointing_case(
    &mut self,
    command: &[String],
    partial: &PartialRegistryItem,
  ) -> Result<bool> {
    const STOP: Result<bool> = Ok(false);
    let lg = Log::get("mk_case");
    lg.info(format!(
      "Next from last {:?} partial {partial:?}",
      self.upcoming_checkpoint
    ));

    let (lid, call_idx) = match self.upcoming_checkpoint {
      CheckpointState::PureStart => (0, 0),
      CheckpointState::Checkpoint(ThreadLidT(t), CallIndexT(i)) => (t, i),
      CheckpointState::End => {
        self.checkpointing_testcase = None;
        return STOP;
      }
    };

    let item = TestRegisryItem {
      uid: partial.uid,
      call_index: CallIndexT(call_idx),
      // "DC" = disregard / "don't care"
      packet_index: PacketIndexT(0), // DC
      thread_lid: ThreadLidT(lid),
      thread_count: u32::try_from(partial.call_counts.len())?,
      test_count: 0,                           // DC
      timeout_test: partial.test_case_timeout, // this should be "DC"
      timeout_process: partial.global_timeout,
      mode: stages::test_registry::TestingMode::CheckpointedTesting(true),
    };

    let mut new_ctcase = Some((item, command.to_vec()));
    mem::swap(&mut self.checkpointing_testcase, &mut new_ctcase);

    lg.trace(format!("{:?}", self.checkpointing_testcase));
    Ok(true)
  }
}

impl TestingPhase for CheckpointedTesting {
  fn prepare_cases(&mut self, command: &[String], partial: &PartialRegistryItem) -> Result<bool> {
    const STOP: Result<bool> = Ok(false);
    // prepare the checkpointing case
    if matches!(self.make_checkpointing_case(command, partial), Ok(false)) {
      return STOP;
    }
    ensure!(self.checkpointing_testcase.is_some(), "invariant broken");
    let tc = self.checkpointing_testcase.as_mut().unwrap();

    // then continue with normal testcases for that single call number
    self.common.test_registry.clear();
    self.common.tests.clear();

    let tests = &mut self.common.tests;
    let test_count = partial.test_count;
    let timeout_test = partial.test_case_timeout;
    let uid: NumFunUid = partial.uid;
    let call_counts = &partial.call_counts;

    let thread_lid = tc.0.thread_lid;
    // if checkpoint restore takes place, be sure to skip the right amount of calls
    let skip = tc.0.call_index.0;
    let max_calls_in_lid = call_counts[thread_lid.0 as usize];
    if skip as u64 >= max_calls_in_lid {
      return STOP;
    }

    for packet_index in 0..test_count {
      tests.push(self.common.test_registry.add_new_test(
        TestRegisryItem {
          uid,
          call_index: CallIndexT(skip),
          packet_index: PacketIndexT(packet_index.into()),
          thread_lid,
          thread_count: u32::try_from(call_counts.len())?,
          test_count,
          timeout_test,
          timeout_process: Some(timeout_test),
          mode: stages::test_registry::TestingMode::MTCompatTesting,
        },
        command,
      ));
    }

    // prepare for the next round
    let candidate_checkpoint = match self.upcoming_checkpoint {
      CheckpointState::PureStart => Some((0, 1)),
      CheckpointState::Checkpoint(ThreadLidT(t), CallIndexT(i)) => Some((t, i + 1)),
      CheckpointState::End => None,
    };
    self.upcoming_checkpoint = match candidate_checkpoint {
      None => CheckpointState::End,
      Some((t, i)) => {
        if i as u64 >= call_counts[t as usize] {
          if t as usize + 1 >= call_counts.len() {
            CheckpointState::End
          } else {
            CheckpointState::Checkpoint(ThreadLidT(t + 1), CallIndexT(0))
          }
        } else {
          CheckpointState::Checkpoint(ThreadLidT(t), CallIndexT(i))
        }
      }
    };
    Ok(true)
  }

  async fn run_tests(
    &mut self,
    meta: Arc<Mutex<MetadataPublisher>>,
  ) -> Result<(Vec<LogResult>, Vec<TestJobFailure>)> {
    // perform the checkpointing case (normal start when init state)
    // (stored checkpoint case)
    let lg = Log::get("run_tests<ChPnt>");
    let restoration_idx = {
      ensure!(
        self.checkpointing_testcase.is_some(),
        "invalid usage - checkpoint test case is None"
      );

      let (tst, cmd) = &self.checkpointing_testcase.as_ref().unwrap();

      let run_from_scratch = tst.call_index.0.is_zero();
      let checkpoint_mode = if run_from_scratch {
        CheckpointJobMode::New(cmd, tst.call_index.0 as usize)
      } else {
        CheckpointJobMode::Restore(tst.call_index.0 as usize)
      };

      lg.trace(format!("Starting checkpoint in mode {checkpoint_mode:?}"));
      let test_job = self.singular_checkpointing_job(meta.clone(), tst, checkpoint_mode);

      let job_res = test_job.await;
      lg.trace(format!("Chekpoint finished in state {job_res:?}"));
      match job_res {
        Err(e) => bail!("Failed checkpoint: {e:?}"),
        Ok(TestStatus::Fatal(e)) => bail!("Failed checkpoint: {e}"),
        Ok(TestStatus::Exit(242)) => bail!("Failed checkpoint - see job output"),
        _ => (),
      }

      self.last_checkpoint_paths = Some(self.output_generator.store_after_checkpoint(
        run_from_scratch,
        tst,
        self.last_checkpoint_paths.as_ref(),
      )?);
      lg.trace(format!(
        "Commited checkpoint paths: {:?}",
        self.last_checkpoint_paths
      ));
      tst.call_index.0
    };

    // after checkpoint case is done (checkpoint should exist under the ID)
    // run all test cases by restoring the checkpoint, retargeting the case
    // and monitoring it as in the normal MT support case

    let test_reg = &self.common.test_registry;
    let mut errors: Vec<TestJobFailure> = vec![];
    let results: Arc<Mutex<Vec<LogResult>>> = Arc::new(Mutex::new(vec![]));
    let lg = Log::get(&format!("run_tests<ChPnt>[{restoration_idx}]"));
    for test in &self.common.tests {
      let (tst, _) = test_reg
        .get_test_params(*test)
        .ok_or(anyhow!("Inconsistent test registry"))?;
      lg.trace(format!("Start test {tst:?}"));
      let dump_path = self.get_criu_path(&CRIUCheckpointIndex(restoration_idx as usize), tst);
      let dump_path = match dump_path {
        Err(e) => {
          errors.push(e);
          continue;
        }
        Ok(v) => v,
      };

      let test_job = self.singular_restore_job(meta.clone(), tst, &dump_path, None);

      match test_job.await {
        Err(e) => errors.push(e),
        Ok(status) => {
          try_lock_anhw(&results)?.push(LogResult::from_test(
            test_reg.get_test_params(*test).unwrap().0,
            status,
          ));
        }
      }
    }

    Ok((
      Arc::try_unwrap(results)
        .map_err(|_| anyhow!("Failed to free results out of Arc"))?
        .lock()
        .unwrap()
        .clone(),
      errors,
    ))
  }

  fn next_batch(&self) -> Option<()> {
    match self.upcoming_checkpoint {
      CheckpointState::PureStart | CheckpointState::Checkpoint(_, _) => Some(()),
      CheckpointState::End => None,
    }
  }
}
