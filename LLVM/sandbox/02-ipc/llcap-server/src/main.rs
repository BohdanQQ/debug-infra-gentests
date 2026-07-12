use std::{
  path::Path,
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
mod phase;
mod shmem_capture;
mod sizetype_handlers;
mod stages;
use shmem_capture::{MetadataPublisher, cleanup};
use stages::{
  arg_capture::PacketReader,
  call_tracing::{
    export_call_trace_data, export_tracing_selection, obtain_function_id_selection,
    print_call_tracing_summary,
  },
  testing::test_server_job,
};

use crate::{
  log::LogStrategy,
  modmap::NumFunUid,
  phase::{arg_capture_phase, calltrace_phase, mask_fn_selection, testing_phase},
  stages::{
    common::{CommonStageParams, read_thread_counts},
    testing::{LogResult, TestJobFailure, TestStatus},
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

fn skip_tracing_selection(selection_arg: &Path) -> bool {
  format!("{}", selection_arg.display()) == "skip"
}

#[allow(clippy::too_many_lines)]
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

  let (common_params, modules) =
    CommonStageParams::try_initialize(cli.buff_count, cli.buff_size, &cli.modmap)?;
  let common_params = Arc::new(common_params);
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
      let mut fn_freqs = calltrace_phase(
        import_path,
        command,
        &modules,
        common_params,
        &cli.fd_prefix,
      )
      .await?;

      fn_freqs.sort_by(|a, b| b.1.cmp(&a.1));
      print_call_tracing_summary(&mut fn_freqs, &modules);

      if let Some(out_path) = out_file {
        lg.trace("Exporting");

        let _ = export_call_trace_data(&fn_freqs, &out_path)
          .inspect_err(|e| lg.crit(format!("Export failed: {e}")));

        lg.progress("Export done");
      }

      let traces = fn_freqs.iter().map(|x| x.0).collect::<Vec<NumFunUid>>();
      let selected_fns = loop {
        let sel = obtain_function_id_selection(&traces, &modules);
        match sel {
          Ok(selection) => break selection,
          Err(e) => lg.crit(e.to_string()),
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
      let modules = mask_fn_selection(&selection_file, modules)?;

      arg_capture_phase(
        out_dir,
        &modules,
        mem_limit as usize,
        common_params,
        &cli.fd_prefix,
        &command,
      )
      .await?;
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
      testing_mode,
    } => {
      let modules = Arc::new(mask_fn_selection(&selection_file, modules)?);
      let command = Arc::new(command);
      lg.progress("Setting up function packet reader");

      let packet_reader = PacketReader::new(&capture_dir, &modules, mem_limit as usize)
        .map_err(|e| anyhow!("Packet reader setup failed: {e}"))?;
      let thread_counts = Arc::new(
        read_thread_counts(&capture_dir)
          .map_err(|e| anyhow!("Thread counter parsing failed: path: {e}"))?,
      );
      if let Some(inspection_spec) = inspect_packet {
        return crate::stages::testing::inspect_packet(&inspection_spec, &modules, &packet_reader);
      }
      // redeclare as immutable
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
      // wait for server to be ready
      match tokio::time::timeout(Duration::from_secs(10), ready_rx).await {
        Ok(Ok(())) => lg.trace("Server ready"),
        Err(_) => bail!("server ready timeout"),
        Ok(Err(e)) => bail!("server ready error: {e}"),
      }
      let metadata_svr = create_meta_svr(&common_params)?;

      let result = testing_phase(
        testing_mode,
        common_params,
        global_timeout.map(|v| Duration::from_secs(v.into())),
        Duration::from_secs(timeout.into()),
        &modules,
        &packet_reader,
        command,
        test_output.as_ref(),
        thread_counts,
        results.clone(),
        metadata_svr.clone(),
      )
      .await;

      lg.progress("Waiting for server to exit...");
      let defer_res_end_svr = end_tx
        .send(())
        .map_err(|()| anyhow!("failed to end server"));
      let defer_res_joins = svr.await.map_err(|e| anyhow!("joins: {e}"));
      let errors = result?;

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
            let mut merged_results = merge_results(unwrapped_res);
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
fn merge_results(results: Vec<LogResult>) -> Vec<LogResult> {
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
    let res = if let Some(s) = results.first().cloned() {
      s.0
    } else {
      return merged;
    };
    let mut same_refs = results
      .iter_mut()
      .filter(|(v, _)| {
        v.call == res.call && v.pkt == res.pkt && v.thread_lid == res.thread_lid && v.uid == res.uid
      })
      .collect::<Vec<&mut (LogResult, bool)>>();

    same_refs.sort_by(|v1, v2| status_prio(&v1.0.status).cmp(&status_prio(&v2.0.status)));
    same_refs.iter_mut().for_each(|x| x.1 = false);
    if let Some(last) = same_refs.last() {
      merged.push(last.0.clone());
    }
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
