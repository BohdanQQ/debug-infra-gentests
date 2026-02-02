use std::{
  fmt::Debug,
  fs::{self, File},
  mem,
  ops::DerefMut,
  os::unix::process::ExitStatusExt,
  path::PathBuf,
  process::{ExitStatus, Stdio},
  sync::{Arc, Mutex, atomic::AtomicBool},
  time::Duration,
};

use anyhow::{Result, anyhow, bail, ensure};
use tokio::{
  io::{AsyncReadExt, BufReader},
  net::{UnixListener, UnixStream, unix::OwnedWriteHalf},
  process::{Child, Command},
  sync::oneshot::{Receiver, Sender},
  time::{sleep, timeout},
};

use crate::{
  args::PacketInspecSpec,
  log::{IntoLogString, Log, LogStrategy},
  modmap::{ExtModuleMap, IntegralFnId, IntegralModId, NumFunUid},
  shmem_capture::{MetadataPublisher, hooklib_commons::*, send_test_metadata},
  stages::{arg_capture::PacketReader, common::*, test_registry::TestRegisryItem},
};

use super::arg_capture::PacketProvider;

#[derive(Clone)]
pub struct LogResult {
  pub call: CallIndexT,
  pub uid: NumFunUid,
  pub pkt: PacketIndexT,
  pub thread_lid: ThreadLidT,
  pub status: TestStatus,
}

impl LogResult {
  pub fn from_test(t: &TestRegisryItem, status: TestStatus) -> Self {
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
              .fold("".to_owned(), |acc, v| acc + &v);
            (modid, fnid, Some(hex))
          }
        };

        format!(
          "{}\n\t{{\n\t\t\"thread_id\":\"{}\",\n\t\t\"module_id\":\"{module_id}\",\n\t\t\"function_id\":\"{fn_id}\",\n\t\t\"call_n\":{},\n\t\t\"packet_idx\":{}{}\n\t\t\"status\":\"{status:?}\"\n\t}}",
          if *first { "" } else { "," },
          thread_lid.0,
          call.0,
          pkt.0,
          if let Some(hex) = packet_hex {
            format!(",\n\t\t\"packet_hex\":\"{hex}\",")
          } else {
            ",".to_owned()
          },
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
/// this future signals readiness (listening for connections) via ready_tx
/// and periodically checks end_rx which orders this future (and the server) to terminate
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
  ready_tx.send(()).map_err(|_| anyhow!("Receiver dropped"))?;
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
          packet_dir.to_path_buf(),
          modules.clone(),
          mem_limit,
          results,
          test_job_stop_flag.clone(),
        )));
      }
      Ok(Err(e)) => Err(anyhow!(e))?,
      Err(_) => continue, // timeout
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
  if bytes.len() < start + 4 {
    return Err("Not enough bytes".to_string());
  }
  let le_bytes = [
    *bytes.get(start).unwrap(),
    *bytes.get(start + 1).unwrap(),
    *bytes.get(start + 2).unwrap(),
    *bytes.get(start + 3).unwrap(),
  ];
  let num = u32::from_le_bytes(le_bytes);
  Ok(num)
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
  let (read, mut write) = stream.into_split();
  let mut buff_stream = BufReader::new(read);

  loop {
    // preallocate 16bytes for client messages
    let mut data = [0u8; MSG_SIZE];
    // polling with timeout to check for client status updates
    // note: this matcher "passes" only if data is received from the socket
    match timeout(Duration::from_millis(100), buff_stream.read(&mut data)).await {
      Ok(Ok(0)) => {
        bail!("Client closed connection - ending, state: {:?}", state);
      }
      Ok(Ok(n)) => {
        if n != data.len() {
          bail!(
            "Expected {} bytes, got {n} - ending, state: {:?}",
            data.len(),
            state
          );
        }
        // we received a message, we continue to message handling
      }
      Ok(Err(e)) => {
        bail!("Not readable {e} - ending, state: {:?}", state);
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

    let (new_state, response) = handle_client_msg(state, msg, &mut packets, results.clone())?;
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

    send_protocol_response(&mut write, &response.unwrap()).await?;
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
  results: Arc<Mutex<TestResults>>,
) -> Result<(ClientState, Option<Vec<u8>>)> {
  match state {
    ClientState::Init => match msg {
      TestMessage::Start(id, i) => Ok((ClientState::Started(id, i), None)),
      TestMessage::End => Ok((ClientState::Ended, None)),
      _ => bail!("Invalid transition from Init state with msg {msg:?}"),
    },
    ClientState::Started(id, call_idx) => match msg {
      TestMessage::TestEnd(test_index, thread_lid, status) => {
        Log::get("handle_client_msg").info(format!(
          "test {id:?} ended: {status:?}, idx: {}",
          test_index.0
        ));
        results.lock().unwrap().push(LogResult {
          uid: id,
          call: call_idx,
          pkt: test_index,
          status,
          thread_lid,
        });
        Ok((state, None))
      }
      TestMessage::PacketRequest(idx) => {
        Log::get("handle_client_msg").trace(format!("{msg:?}"));
        let response = packets.get_packet(id, idx.0 as usize);
        Ok((state, response))
      }
      TestMessage::End => Ok((ClientState::Ended, None)),
      _ => bail!("Invalid transition from Started state with msg {msg:?}"),
    },
    ClientState::Ended => bail!(
      "Client state is Ended, no more messages were expected msg: {msg:?}, from state: {state:?}"
    ),
  }
}

async fn raw_send_to_client(stream: &mut OwnedWriteHalf, data: &[u8]) -> Result<()> {
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
        continue;
      }
      Err(ref e) if e.kind() == tokio::io::ErrorKind::WouldBlock => {
        continue;
      }
      Err(e) => {
        bail!(e.to_string());
      }
    }
  }
}

/// sends response in accordance with the comms protocol between the test client and this server
/// (length + payload)
async fn send_protocol_response(write_stream: &mut OwnedWriteHalf, response: &[u8]) -> Result<()> {
  // send response length + response
  raw_send_to_client(
    write_stream,
    &(response.as_ref().len() as u32).to_le_bytes(),
  )
  .await?;

  raw_send_to_client(write_stream, response).await
}

pub struct TestJobFailure {
  pub log_res: LogResult,
}

impl TestJobFailure {
  pub fn from_test(t: &TestRegisryItem, message: String, status: Option<TestStatus>) -> Self {
    Self {
      log_res: LogResult::from_test(t, status.unwrap_or(TestStatus::Fatal(message.clone()))),
    }
  }
}

pub async fn singular_test_job(
  metadata_svr: Arc<Mutex<MetadataPublisher>>,
  infra_params: InfraParams,
  test: &TestRegisryItem,
  cmdline: &[String],
  output_gen: Arc<Option<TestOutputPathGen>>,
) -> Result<TestStatus, TestJobFailure> {
  let (m, f) = (test.uid.module_id, test.uid.function_id);
  let packet_idx = test.packet_index;
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

    send_test_metadata(guard.deref_mut(), infra_params, test)
      .map_err(|e| mk_error(e, TestStatus::Timeout))?;
  }
  // prepare the "command", mainly the std out/err
  let mut cmd = cmd_from_args(cmdline)
    .map_err(|e| mk_error(e, TestStatus::Fatal("Command creation".to_owned())))?;
  if let Some(output_gen) = output_gen.as_ref() {
    let id = format!(
      "t{}-c{}-i{}",
      test.thread_lid.0,
      test.target_call_number(),
      packet_idx.0
    );
    let out_path = output_gen.get_out_path(m, f, &id);
    let err_path = output_gen.get_err_path(m, f, &id);
    cmd.stdout(Stdio::from(File::create(out_path.clone()).map_err(
      |e| {
        mk_error(
          anyhow!("Stdout file creation failed: {e} {out_path:?}"),
          TestStatus::Fatal("Stdout create".to_owned()),
        )
      },
    )?));
    cmd.stderr(Stdio::from(File::create(err_path).map_err(|e| {
      mk_error(
        anyhow!("Stderr file creation failed {e} {out_path:?}"),
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

/// waits for or terminates the test instance (child process representing the entire test process) based on a timeout
async fn wait_or_terminate(mut process: Child, test: &TestRegisryItem) -> TestStatus {
  let lg = Log::get("wait_or_terminate");
  let process_timeout = test.timeout_process;
  let kill_children = test.terminate_child();
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
}

impl TestOutputPathGen {
  pub fn new(base: Option<PathBuf>) -> Option<Self> {
    if let Some(base) = base {
      Self {
        dir: base.clone().is_dir(),
        base,
      }
      .into()
    } else {
      None
    }
  }

  pub fn get_out_path(&self, m: IntegralModId, f: IntegralFnId, id: &str) -> PathBuf {
    self.get_path(format!(
      "M{}-F{}-{}.out",
      m.hex_string(),
      f.hex_string(),
      id
    ))
  }

  pub fn get_err_path(&self, m: IntegralModId, f: IntegralFnId, id: &str) -> PathBuf {
    self.get_path(format!(
      "M{}-F{}-{}.err",
      m.hex_string(),
      f.hex_string(),
      id
    ))
  }

  fn get_path(&self, dir_append_variant: String) -> PathBuf {
    if self.dir {
      self.base.join(dir_append_variant)
    } else {
      self.base.clone()
    }
  }
}

pub fn inspect_packet(
  spec: &PacketInspecSpec,
  modules: &ExtModuleMap,
  reader: &mut PacketReader,
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
