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
    testing::{
      CallIndexT, ForkingTestJobParams, LogResult, MultithreadTestJobParams, PacketIndexT,
      TestGenerator, TestJobFailure, TestOutputPathGen, TestStatus,
    },
  },
};

// shorthands for the creation of the MetadataPublisher

fn create_meta_svr(
  params: &CommonStageParams,
  thread_counters: Option<&Vec<u64>>,
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
  const LOGGER_NAME: &str = "main";
  let lg = Log::get(LOGGER_NAME);
  let cli = Cli::try_parse()?;
  Log::set_verbosity(cli.verbose);
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
      mt_support,
    } => {
      let log_out = match report {
        None => LogStrategy::StdOut,
        Some(x) => LogStrategy::create(&x).await?,
      };
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

      let mut futs = vec![];
      let metadata_svr = create_meta_svr(&common_params, Some(&thread_counts))?;
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

          let fn_uid = NumFunUid {
            function_id: *function,
            module_id: *module,
          };
          lg.progress(format!("Run program for fn {fn_uid:?}"));

          if mt_support {
            // TODO: thread loop
            for test_index in 0..test_count {
              let test_job = MultithreadTestJobParams {
                fn_uid,
                test_case_timeout,
                test_count,
                job_timeout: global_timeout,
                command: command.clone(),
                thread_counters: thread_counts.clone(),
              }
              .into_job(
                metadata_svr.clone(),
                common_params.infra,
                output_gen.clone(),
              );

              for res in test_job.await? {
                match res {
                  Err(e) => errors.push(e),
                  Ok(v) => results.lock().unwrap().push(LogResult {
                    uid: fn_uid,
                    call: CallIndexT(test_index + 1),
                    pkt: PacketIndexT(test_count as u64),
                    status: v,
                  }),
                }
              }
            }
          } else {
            let test_job = ForkingTestJobParams {
              fn_uid,
              test_count,
              test_case_timeout,
              job_timeout: global_timeout,
              command: command.clone(),
            }
            .into_job(
              metadata_svr.clone(),
              common_params.infra,
              output_gen.clone(),
            );
            futs.push(test_job);
          }
        }
      }
      lg.progress("Waiting for jobs to finish...");
      for fut in futs {
        for r in fut.await? {
          if let Err(e) = r {
            errors.push(e);
          }
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
            let mut unwrapped_res = val.into_inner()?;
            report_results(log_out, &mut unwrapped_res, errors).await
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
    // skip errors - prefer outputs
    let _ = lg.result(result).await;
  }
  for error in errors {
    let uid = match error.params {
      stages::testing::AnyTestJobParam::F(forking_test_job_params) => {
        forking_test_job_params.fn_uid
      }
      stages::testing::AnyTestJobParam::M(multithread_test_job_params) => {
        multithread_test_job_params.fn_uid
      }
    };
    let _ = lg
      .result(&LogResult {
        uid,
        call: stages::testing::CallIndexT(error.call_number),
        pkt: stages::testing::PacketIndexT(0),
        status: match error.status {
          Some(st) => st,
          None => TestStatus::Fatal(error.message),
        },
      })
      .await;
  }
  lg.finish().await?;
  Ok(())
}
