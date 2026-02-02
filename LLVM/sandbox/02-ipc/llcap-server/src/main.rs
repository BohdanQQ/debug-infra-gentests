use std::{
  sync::{Arc, Mutex},
  time::Duration,
};

use anyhow::{Result, anyhow, bail};
use args::Cli;
use clap::Parser;
use log::Log;

mod args;
mod constants;
mod libc_wrappers;
mod log;
mod modmap;
mod shmem_capture;
mod sizetype_handlers;
mod stages;
use shmem_capture::{
  MetadataPublisher, arg_capture::perform_arg_capture, call_tracing::perform_call_tracing, cleanup,
  send_arg_capture_metadata,
};
use stages::{
  arg_capture::{ArgPacketDumper, PacketReader},
  call_tracing::{
    export_call_trace_data, export_tracing_selection, import_call_trace_data,
    import_tracing_selection, obtain_function_id_selection, print_call_tracing_summary,
  },
  testing::test_server_job,
};

use crate::{
  log::LogStrategy,
  modmap::NumFunUid,
  shmem_capture::{TracingInfra, send_call_tracing_metadata},
  stages::{
    common::{
      CommonStageParams, cmd_from_args, drive_instrumented_application, read_thread_counts,
    },
    test_registry::{TestRegisryItem, TestRegistry, TestingMode},
    testing::{
      CallIndexT, LogResult, PacketIndexT, TestJobFailure, TestOutputPathGen, TestStatus,
      ThreadLidT, singular_test_job,
    },
  },
};

// shorthands for the creation of the MetadataPublisher

fn create_meta_svr(
  params: &CommonStageParams,
  thread_counters: Option<Vec<u64>>,
) -> Result<Arc<Mutex<MetadataPublisher>>> {
  let (data_name, size_name) = params.shmem_path_cstr()?;
  Ok(Arc::new(Mutex::new(
    MetadataPublisher::new(
      data_name,
      size_name,
      &params.data_semaphore_name,
      &params.ack_semaphore_name,
      thread_counters,
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

#[tokio::main()]
async fn main() -> Result<()> {
  let cli = Cli::try_parse()?;
  Log::set_verbosity(cli.verbose);
  let lg = Log::get("main");
  lg.progress(format!("Verbosity: {}", cli.verbose));

  if cli.cleanup {
    lg.progress("Cleanup");
    return cleanup(&cli.fd_prefix);
  }

  let mut common_params =
    CommonStageParams::try_initialize(cli.buff_count, cli.buff_size, &cli.modmap)?;
  let mut modules = common_params.extract_module_maps()?;
  match cli.stage {
    args::Stage::TraceCalls {
      mut out_file,
      import_path,
      selection_path,
      command,
    } => {
      if import_path.is_some() {
        out_file = None;
      }

      let mut pairs = if let Some(in_path) = import_path {
        lg.trace("Importing");
        let result = import_call_trace_data(in_path, &modules)?;
        lg.progress("Import done");
        result
      } else {
        let command = command.ok_or(anyhow!(
          "command must be present if import_path is not specified"
        ))?;

        lg.progress("Initializing tracing infrastructure");
        let infra_params = common_params.infra;
        let (mut tracing_infra, finalizer_info) =
          TracingInfra::try_new(&cli.fd_prefix, infra_params)?;
        let metadata_svr = create_meta_svr(&common_params, None)?;

        let result = drive_instrumented_application(
          cmd_from_args(&command)?,
          finalizer_info,
          metadata_svr.clone(),
          send_call_tracing_metadata,
          || perform_call_tracing(&mut tracing_infra, &modules),
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

      pairs.sort_by(|a, b| b.1.cmp(&a.1));
      print_call_tracing_summary(&mut pairs, &modules);

      if let Some(out_path) = out_file {
        lg.trace("Exporting");

        let _ = export_call_trace_data(&pairs, out_path)
          .inspect_err(|e| lg.crit(format!("Export failed: {e}")));

        lg.progress("Export done");
      }

      let traces = pairs.iter().map(|x| x.0).collect::<Vec<NumFunUid>>();
      let selected_fns = loop {
        let sel = obtain_function_id_selection(&traces, &modules);
        if let Ok(selection) = sel {
          break selection;
        } else {
          lg.crit(sel.unwrap_err().to_string());
        }
      };
      export_tracing_selection(&selected_fns, &modules, selection_path)?;
    }
    args::Stage::CaptureArgs {
      selection_file,
      out_dir,
      mem_limit,
      command,
    } => {
      lg.progress("Reading function selection");
      let selection = import_tracing_selection(&selection_file)?;

      lg.progress("Masking");
      modules.mask_include(&selection)?;

      lg.progress("Setting up function packet dumping");
      let mut dumper = ArgPacketDumper::new(&out_dir, &modules, mem_limit as usize)?;
      lg.progress("Initializing tracing infrastructure");
      let infra_params = common_params.infra;
      let (mut tracing_infra, finalizer_info) =
        TracingInfra::try_new(&cli.fd_prefix, infra_params)?;
      let metadata_svr = create_meta_svr(&common_params, None)?;

      // for comments, see the match arm for the TraceCalls subcommand
      let result = drive_instrumented_application(
        cmd_from_args(&command)?,
        finalizer_info,
        metadata_svr.clone(),
        send_arg_capture_metadata,
        || perform_arg_capture(&mut tracing_infra, &modules, &mut dumper),
        infra_params,
      )
      .await;
      if let Err(e) = &result {
        lg.crit(format!("error in capture {e}"));
      }

      lg.progress("Shutting down tracing infrastructure...");
      let real_result = tracing_infra
        .deinit()
        .inspect_err(|e| lg.crit(format!("You might need to perform cleanup: {e}")))
        .map(|_| result);
      try_meta_svr_arc_deinit(metadata_svr)?;
      let _ = real_result?;
    }
    args::Stage::Test {
      selection_file,
      capture_dir,
      mem_limit,
      test_output,
      timeout,
      global_timeout,
      command,
      inspect_packets: inspect_packet,
      report,
      detailed_report,
      mt_support,
    } => {
      let command = Arc::new(command);
      lg.progress("Reading function selection");
      let selection = import_tracing_selection(&selection_file)?;
      lg.progress("Masking");
      modules.mask_include(&selection)?;

      let modules = Arc::new(modules);
      lg.progress("Setting up function packet reader");

      let mut packet_reader = PacketReader::new(&capture_dir, &modules, mem_limit as usize)
        .map_err(|e| anyhow!("Packet reader setup failed: {e}"))?;
      let thread_counts = read_thread_counts(&capture_dir)
        .map_err(|e| anyhow!("Thread counter parsing failed: path: {e}"))?;
      if let Some(inspection_spec) = inspect_packet {
        return crate::stages::testing::inspect_packet(
          &inspection_spec,
          &modules,
          &mut packet_reader,
        );
      }
      // redeclare as immutable since mutability is not needed further
      let packet_reader = packet_reader;

      lg.progress("Setting up function packet server");
      let (end_tx, end_rx) = tokio::sync::oneshot::channel::<()>();
      let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<()>();
      let results = Arc::new(Mutex::new(Vec::with_capacity(500)));
      let svr = tokio::spawn(test_server_job(
        capture_dir,
        modules.clone(),
        mem_limit as usize,
        (ready_tx, end_rx),
        results.clone(),
      ));
      let output_gen = Arc::new(TestOutputPathGen::new(test_output));
      // wait for server to be ready
      match tokio::time::timeout(Duration::from_secs(10), ready_rx).await {
        Ok(Ok(())) => lg.trace("Server ready"),
        Err(_) => bail!("server ready timeout"),
        Ok(Err(e)) => bail!("server ready error: {}", e),
      }

      let metadata_svr = create_meta_svr(&common_params, Some(thread_counts.clone()))?;
      let mut errors = vec![];
      let global_timeout = global_timeout.map(|v| Duration::from_secs(v as u64));
      let test_case_timeout = Duration::from_secs(timeout as u64);

      for module in modules.modules() {
        for function in modules.functions(*module).unwrap() {
          let uid = (*module, *function).into();
          let test_count = packet_reader.get_packet_count(uid).ok_or(anyhow!(
            "Not found tests: {} {}",
            module.hex_string(),
            function.hex_string()
          ))?;

          if test_count == 0 {
            Log::get("send_test_metadata").warn(format!(
              "Skipping M: {} F: {} due to zero test count t:{}",
              module.hex_string(),
              function.hex_string(),
              test_count
            ));
            continue;
          }

          let mut test_reg = TestRegistry::new();
          let mut tests = vec![];
          let _test_reg = if mt_support {
            for (thread_idx, call_count) in thread_counts.iter().enumerate() {
              for call_index in 0..*call_count {
                for packet_index in 0..test_count {
                  let thread_idx = thread_idx as u32;
                  tests.push(test_reg.add_new_test(
                    TestRegisryItem {
                      uid,
                      call_index: CallIndexT(call_index as u32),
                      packet_index: PacketIndexT(packet_index as u64),
                      thread_lid: ThreadLidT(thread_idx as u64),
                      thread_count: thread_counts.len() as u32,
                      test_count,
                      timeout_test: test_case_timeout,
                      timeout_process: Some(test_case_timeout),
                      mode: stages::test_registry::TestingMode::MTCompatTesting,
                    },
                    &command,
                  ));
                }
              }
            }

            let test_reg = Arc::new(test_reg);
            for test in tests {
              let (tst, cmd) = test_reg
                .get_test_params(test)
                .ok_or(anyhow!("Inconsistent test registry"))?;
              let test_job = singular_test_job(
                metadata_svr.clone(),
                common_params.infra,
                tst,
                cmd,
                output_gen.clone(),
              );

              match test_job.await {
                Err(e) => errors.push(e),
                Ok(status) => results.lock().unwrap().push(LogResult::from_test(
                  test_reg.get_test_params(test).unwrap().0,
                  // maps GlobalTimeout to just Timeout - in the MT compat testing, we don't distinguish those
                  if matches!(status, stages::testing::TestStatus::GlobalTimeout) {
                    stages::testing::TestStatus::Timeout
                  } else {
                    status
                  },
                )),
              }
            }
            test_reg.clone()
          } else {
            let mut tests = vec![];
            for call_idx in 0..test_count {
              tests.push(test_reg.add_new_test(
                TestRegisryItem {
                  uid,
                  call_index: CallIndexT(call_idx),
                  packet_index: PacketIndexT(0_u64),
                  thread_lid: ThreadLidT(0_u64),
                  thread_count: thread_counts.len() as u32,
                  test_count,
                  timeout_test: test_case_timeout,
                  timeout_process: global_timeout,
                  mode: stages::test_registry::TestingMode::Testing,
                },
                &command,
              ));
            }

            let test_reg = Arc::new(test_reg);
            for test in tests {
              let (tst, cmd) = test_reg
                .get_test_params(test)
                .ok_or(anyhow!("Inconsistent test registry"))?;
              lg.trace(format!("Start test {tst:?}"));

              let test_job = singular_test_job(
                metadata_svr.clone(),
                common_params.infra,
                tst,
                cmd,
                output_gen.clone(),
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
                  results.lock().unwrap().push(LogResult::from_test(
                    test_reg.get_test_params(test).unwrap().0,
                    status,
                  ))
                }
                _ => {}
              }
            }
            test_reg.clone()
          };
        }
      }
      lg.progress("Waiting for server to exit...");
      let defer_res_end_svr = end_tx.send(()).map_err(|_| anyhow!("failed to end server"));
      let defer_res_joins = svr.await.map_err(|e| anyhow!("joins: {e}"));
      lg.progress("reporting results");
      let delayed_err = {
        let rmx: Result<Mutex<Vec<LogResult>>, Arc<Mutex<Vec<LogResult>>>> =
          Arc::try_unwrap(results);
        match rmx {
          Ok(val) => {
            let unwrapped_res = val.into_inner()?;
            let log_out = match report {
              None => LogStrategy::StdOut,
              Some(x) => {
                LogStrategy::create(
                  &x,
                  if detailed_report {
                    log::Detail::Detailed(modules, packet_reader)
                  } else {
                    log::Detail::Normal
                  },
                )
                .await?
              }
            };
            let mut merged_results = merge_results(
              if mt_support {
                TestingMode::MTCompatTesting
              } else {
                TestingMode::Testing
              },
              unwrapped_res,
            );
            report_results(log_out, &mut merged_results, errors).await
          }
          Err(_) => Err(anyhow!("Failed to synchronize with the server")),
        }
      };

      lg.trace("Cleaning up");
      defer_res_end_svr?;
      defer_res_joins??;
      try_meta_svr_arc_deinit(metadata_svr)?;
      delayed_err.map_err(|e| anyhow!("When logging results: {e}"))?;
    }
  }
  lg.progress("Exiting...");
  Ok(())
}

// handles cases where 2 results are produced (one local, one asynchronous, especially in the MT-support mode)
// we create a precedence of statuses, where the priority is thus:
// Pass/Exception
// Timeout
// GlobalTimeout
// Exit/Signal
// Fatal
// Spurious
fn merge_results(_mode: TestingMode, results: Vec<LogResult>) -> Vec<LogResult> {
  let status_prio = |st: &TestStatus| -> u8 {
    match st {
      TestStatus::Pass | TestStatus::Exception => 100,
      TestStatus::Timeout => 95,
      TestStatus::GlobalTimeout => 90,
      TestStatus::Exit(_) => 85,
      TestStatus::Signal(_) => 80,
      TestStatus::Fatal(_) => 75,
      TestStatus::Spurious(_) => 70,
    }
  };
  let mut results = results
    .into_iter()
    .map(|v| (v, true))
    .collect::<Vec<(LogResult, bool)>>();

  let mut merged = vec![];
  loop {
    // remove all "marked as trash"
    results.retain(|v| v.1);
    if results.is_empty() {
      return merged;
    }
    let res = results.first().cloned().unwrap().0;
    let mut same_refs = results
      .iter_mut()
      .filter(|(v, _)| {
        v.call == res.call && v.pkt == res.pkt && v.thread_lid == res.thread_lid && v.uid == res.uid
      })
      .collect::<Vec<&mut (LogResult, bool)>>();

    same_refs.sort_by(|v1, v2| status_prio(&v1.0.status).cmp(&status_prio(&v2.0.status)));
    same_refs.iter_mut().for_each(|x| x.1 = false);

    merged.push(same_refs.last().unwrap().0.clone());
  }
}

async fn report_results(
  strategy: LogStrategy,
  results: &mut [LogResult],
  errors: Vec<TestJobFailure>,
) -> Result<()> {
  let mut lg = Log::result_logger(strategy).await?;
  results.sort_by(|a, b| a.pkt.0.cmp(&b.pkt.0));
  results.sort_by(|a, b| a.call.0.cmp(&b.call.0));
  results.sort_by(|a, b| a.uid.function_id.cmp(&b.uid.function_id));
  results.sort_by(|a, b| a.uid.module_id.cmp(&b.uid.module_id));
  for result in results.iter() {
    // skip I/O errors - prefer outputs
    let _ = lg.result(result).await;
  }
  for error in errors {
    let _ = lg.result(&error.log_res).await;
  }
  lg.finish().await?;
  Ok(())
}
