use std::{
  path::PathBuf,
  rc::Rc,
  sync::{Arc, Mutex},
  time::Duration,
};

use anyhow::{Result, anyhow, ensure};

use crate::{
  args,
  log::Log,
  modmap::{ExtModuleMap, NumFunUid},
  shmem_capture::{
    MetadataPublisher, TracingInfra, arg_capture::perform_arg_capture,
    call_tracing::perform_call_tracing, send_arg_capture_metadata, send_call_tracing_metadata,
  },
  stages::{
    arg_capture::{ArgPacketDumper, PacketReader},
    call_tracing::{import_call_trace_data, import_tracing_selection},
    common::{
      CommonStageParams, cmd_from_args, drive_instrumented_application,
    },
    testing::{
      BasicTesting, CheckpointedTesting, LogResult, MTSupportTesting, PartialRegistryItem,
      TestJobFailure, TestOutputPathGen, TestingPhase,
    },
  },
};

// shorthands for the creation of the MetadataPublisher

fn create_meta_svr(params: &CommonStageParams) -> Result<Arc<Mutex<MetadataPublisher>>> {
  let (data_name, size_name) = params.shmem_path_cstr()?;
  Ok(Arc::new(Mutex::new(
    MetadataPublisher::new(
      data_name,
      size_name,
      &params.data_semaphore_name,
      &params.ack_semaphore_name,
    )
    .map_err(|e| anyhow!("{e}\ncleanup required..."))?,
  )))
}

fn try_meta_svr_arc_deinit(metadata_svr: Arc<Mutex<MetadataPublisher>>) -> Result<()> {
  Arc::try_unwrap(metadata_svr).map_or(
    Err(anyhow!("Failed to unwrap from arc... this is not expected")),
    |ms| ms.into_inner().unwrap().deinit(),
  )
}

pub async fn calltrace_phase(
  import_path: Option<PathBuf>,
  command: Option<Vec<String>>,
  modules: &ExtModuleMap,
  commons: Arc<CommonStageParams>,
  fd_prefix: &str,
) -> Result<Vec<(NumFunUid, u64)>> {
  let lg = Log::get("calltrace_phase");
  let pairs = if let Some(in_path) = import_path {
    lg.trace("Importing");
    let result = import_call_trace_data(in_path, modules)?;
    lg.progress("Import done");
    result
  } else {
    let command = command.ok_or(anyhow!(
      "command must be present if import_path is not specified"
    ))?;

    lg.progress("Initializing tracing infrastructure");
    let infra_params = commons.infra;
    let (mut tracing_infra, finalizer_info) = TracingInfra::try_new(fd_prefix, infra_params)?;
    let metadata_svr = create_meta_svr(&commons)?;

    let result = drive_instrumented_application(
      cmd_from_args(&command)?,
      finalizer_info,
      metadata_svr.clone(),
      send_call_tracing_metadata,
      || perform_call_tracing(&mut tracing_infra, modules),
      infra_params,
    )
    .await
    .map(|freqs| freqs.into_iter().collect::<Vec<(_, _)>>());

    lg.progress("Shutting down tracing infrastructure...");
    // we simply chain Results together to perform cleanup, in edge cases, even despite this effort, --cleanup is still requried
    let real_result = tracing_infra
      .deinit()
      .inspect_err(|e| lg.crit(format!("You might need to perform cleanup: {e}")))
      .map(|_| result)?;

    // this should not really fail unless metadata_svr is cloned and persisted somewhere it should not be (i.e we should be the sole owners of metadata_svr here)
    try_meta_svr_arc_deinit(metadata_svr)?;
    real_result?
  };
  Ok(pairs)
}

pub async fn arg_capture_phase(
  out_dir: PathBuf,
  modules: &ExtModuleMap,
  mem_limit: usize,
  commons: Arc<CommonStageParams>,
  fd_prefix: &str,
  command: &[String],
) -> Result<()> {
  let lg = Log::get("arg_capture_phase");
  lg.progress("Setting up function packet dumping");
  let mut dumper = ArgPacketDumper::new(&out_dir, modules, mem_limit)?;
  lg.progress("Initializing tracing infrastructure");
  let infra_params = commons.infra;
  let (mut tracing_infra, finalizer_info) = TracingInfra::try_new(fd_prefix, infra_params)?;
  let metadata_svr = create_meta_svr(&commons)?;

  // for comments, see the match arm for the TraceCalls subcommand
  let res = drive_instrumented_application(
    cmd_from_args(command)?,
    finalizer_info,
    metadata_svr.clone(),
    send_arg_capture_metadata,
    || perform_arg_capture(&mut tracing_infra, modules, &mut dumper),
    infra_params,
  )
  .await;

  if let Err(e) = &res {
    lg.crit(format!("error in capture {e}"));
  }

  lg.progress("Shutting down tracing infrastructure...");
  let cleanup = tracing_infra
    .deinit()
    .inspect_err(|e| lg.crit(format!("You might need to perform cleanup: {e}")));
  try_meta_svr_arc_deinit(metadata_svr)?;
  cleanup?;
  res
}

pub async fn testing_phase(
  mode: args::TestingMode,
  common_params: Arc<CommonStageParams>,
  global_timeout: Option<Duration>,
  test_case_timeout: Duration,
  modules: &ExtModuleMap,
  packet_reader: &PacketReader,
  command: Arc<Vec<String>>,
  test_output: &Option<PathBuf>,
  thread_counts: Rc<Vec<u64>>,
  results: Arc<Mutex<Vec<LogResult>>>,
  metadata_svr: Arc<Mutex<MetadataPublisher>>,
) -> Result<Vec<TestJobFailure>> {
  let mut errors = vec![];

  for module in modules.modules() {
    for function in modules.functions(*module).unwrap() {
      let uid = (*module, *function).into();
      let test_count = packet_reader.get_packet_count(uid).ok_or(anyhow!(
        "Not found tests: {} {}",
        module.hex_string(),
        function.hex_string()
      ))?;

      if test_count == 0 {
        Log::get("testing_phase").warn(format!(
          "Skipping M: {} F: {} due to zero test count t:{}",
          module.hex_string(),
          function.hex_string(),
          test_count
        ));
        continue;
      }

      match mode {
        args::TestingMode::Basic => {
          let output_gen = Arc::new(TestOutputPathGen::make(test_output.clone())?);
          let p = BasicTesting::new(common_params.clone(), output_gen.clone());
          run_test_case(
            p,
            command.clone(),
            PartialRegistryItem {
              uid,
              test_count,
              test_case_timeout,
              global_timeout,
              thread_counts: thread_counts.clone(),
            },
            metadata_svr.clone(),
            results.clone(),
            &mut errors,
          )
          .await
        }
        args::TestingMode::MTSupport => {
          let output_gen = Arc::new(TestOutputPathGen::make(test_output.clone())?);
          let p = MTSupportTesting::new(common_params.clone(), output_gen.clone());
          run_test_case(
            p,
            command.clone(),
            PartialRegistryItem {
              uid,
              test_count,
              test_case_timeout,
              global_timeout,
              thread_counts: thread_counts.clone(),
            },
            metadata_svr.clone(),
            results.clone(),
            &mut errors,
          )
          .await
        }
        args::TestingMode::Criu => {
          // TODO TODO TODO
          // TODO TODO TODO
          // TODO TODO TODO
          // TODO TODO TODO
          // Require running as root (restoration requires it)
          // - or allow nonroot but warn regarding the --unpriviliged option usage
          // (and propagate the info that the option is used)
          let output_gen = TestOutputPathGen::make(test_output.clone())?;
          ensure!(output_gen.is_some(), "Output must be specified");
          let output_gen = Arc::new(output_gen.unwrap());
          let mut p =
            CheckpointedTesting::new(common_params.clone(), output_gen, test_count as usize);
          // TODO: use run_test_case (either adapt it for next_batch or get rid of nxt_btch)
          while let Some(()) = p.next_batch() {
            let has_tests = p.prepare_cases(
              &command,
              &PartialRegistryItem {
                uid,
                test_count,
                test_case_timeout,
                global_timeout,
                thread_counts: thread_counts.clone(),
              },
            )?;
            if !has_tests {
              break;
            }

            let (oks, fails) = p.run_tests(metadata_svr.clone()).await?;
            results.lock().unwrap().extend_from_slice(&oks);
            errors.extend_from_slice(&fails);
          }
          Ok(())
        }
      }?;
    }
  }
  Ok(errors)
}

pub fn mask_fn_selection(
  selection_file: PathBuf,
  mut modules: ExtModuleMap,
) -> Result<ExtModuleMap> {
  //lg.progress("Reading function selection");
  let selection = import_tracing_selection(&selection_file)?;
  //lg.progress("Masking");
  modules.mask_include(&selection)?;
  Ok(modules)
}

async fn run_test_case(
  mut test_phase: impl TestingPhase,
  cmd: Arc<Vec<String>>,
  template: PartialRegistryItem,
  metadata_svr: Arc<Mutex<MetadataPublisher>>,
  results: Arc<Mutex<Vec<LogResult>>>,
  errors: &mut Vec<TestJobFailure>,
) -> Result<()> {
  while let Ok(v) = test_phase.prepare_cases(&cmd, &template) {
    if !v {
      break;
    }
    let (mut res, mut err) = test_phase.run_tests(metadata_svr.clone()).await?;

    results.lock().unwrap().append(&mut res);
    errors.append(&mut err);

    if test_phase.next_batch().is_none() {
      break;
    }
  }
  Ok(())
}
