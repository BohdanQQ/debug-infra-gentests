use clap::{Parser, Subcommand};
use std::path::PathBuf;

use crate::{
  constants::Constants,
  modmap::{IntegralModId, NumFunUid},
};

#[derive(Clone, Copy)]
pub enum PktIdxSpec {
  Single(usize),
  All,
}

#[derive(Clone, Copy)]
pub struct PacketInspecSpec(pub NumFunUid, pub PktIdxSpec);

pub fn parse_pkt_inspect(inp: &str) -> Result<PacketInspecSpec, String> {
  let split = inp.split('-').collect::<Vec<&str>>();
  if split.len() != 3 && split.len() != 2 {
    return Err(format!(
      "Invalid format, expecting 2 or 3 parts, got {}",
      split.len()
    ));
  }
  let (mod_str, fn_str) = (split[0], split[1]);

  let parse16 = |s: &str| {
    u32::from_str_radix(s, 16)
      .map_err(|e| e.to_string())
      // HACK: relevant IDs are displayed in arch byte order, user input is in the arch order but string parsing is done in BE
      .map(|x| {
        // on BE arch, this is a noop, so this is correct
        x.to_be()
      })
  };

  let module = parse16(mod_str)?;
  let func = parse16(fn_str)?;

  let pkt_idx = if split.len() == 3 {
    PktIdxSpec::Single(split[2].parse::<usize>().map_err(|e| e.to_string())?)
  } else {
    PktIdxSpec::All
  };

  Ok(PacketInspecSpec(
    NumFunUid {
      function_id: crate::modmap::IntegralFnId(func),
      module_id: IntegralModId(module),
    },
    pkt_idx,
  ))
}

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
  /// Sets path to module map directory as produced by the instrumentation
  #[arg(short, long)]
  pub modmap: PathBuf,

  /// The stage to perform
  #[command(subcommand)]
  pub stage: Stage,

  /// File descriptor prefixes for resources
  #[arg(short = 'p', long, default_value = Constants::default_fd_prefix())]
  pub fd_prefix: String,

  /// Buffer count
  #[arg(short = 'c', long, default_value = Constants::default_buff_count_str())]
  pub buff_count: u32,

  /// Buffer size in bytes
  #[arg(short = 's', long, default_value = Constants::default_buff_size_bytes_str())]
  pub buff_size: u32,

  /// Enable verbose output, write up to 3x
  #[arg(short, long, action = clap::ArgAction::Count)]
  pub verbose: u8,

  /// Perform a cleanup of all possibly leftover resources related to the given stage and exit
  #[arg(long)]
  pub cleanup: bool,
}

#[derive(Subcommand)]
pub enum Stage {
  /// Set up a function tracing server
  TraceCalls {
    /// produce an export file
    #[arg(short = 'e', long, default_value = Constants::default_trace_out_path())]
    out_file: Option<PathBuf>,

    /// imports an export file, export file will not be created
    #[arg(short, long)]
    import_path: Option<PathBuf>,

    /// path where the function selection will be saved
    #[arg(short = 'o', long)]
    selection_path: Option<PathBuf>,

    /// Command to execute the binary whose calls we want to trace
    #[arg(trailing_var_arg(true))]
    command: Option<Vec<String>>,
  },

  CaptureArgs {
    // path to the function selection file (generated in the call-tracing phase)
    #[arg(short, long, default_value = Constants::default_selected_functions_path())]
    selection_file: PathBuf,

    /// the directory where function argument traces are saved (or offloaded)
    #[arg(short, long, default_value = Constants::default_capture_out_path())]
    out_dir: PathBuf,

    /// capture memory limit in MEBIBYTES - offloading will be performed to the output directory
    #[arg(short = 'l', long, default_value = "0")]
    mem_limit: u32,

    /// Command to execute the binary whose arguments we want to trace
    #[arg(trailing_var_arg(true))]
    command: Vec<String>,
  },

  Test {
    // path to the function selection file (generated in the call-tracing phase)
    #[arg(short, long, default_value = Constants::default_selected_functions_path())]
    selection_file: PathBuf,

    /// the directory where function argument traces have been saved (generated in the arg-capturing phase)
    #[arg(short, long, default_value = Constants::default_capture_out_path())]
    capture_dir: PathBuf,

    /// capture read memory limit in MEBIBYTES
    #[arg(short = 'l', long, default_value = "0")]
    mem_limit: u32,

    /// Redirects output to a file or a directory instead of the standard output (target directory must exist beforehand otherwise the path is treated as a file path)
    #[arg(short = 'o', long)]
    test_output: Option<PathBuf>,

    /// Timeout for each test case in seconds. The timeout is counted only for the instrumented function call
    #[arg(short, long, default_value = "3")]
    timeout: u16,

    /// A global timeout for each test job in seconds. The timeout is counted since around the launch of the instrumented application. Set this option if you expect that test cases will not be triggered
    #[arg(long)]
    global_timeout: Option<u16>,

    /// Optional path to a final testing report. Enconding is determined by the file extension.
    /// Supported formats: .txt/.out/.log for plain text and .json for JSON  
    #[arg(long)]
    report: Option<PathBuf>,

    /// Command to execute tested binary
    #[arg(trailing_var_arg(true))]
    command: Vec<String>,

    /// Print out information about a specific function argument packet and exit. Input format is in form MX-FX-DD where M/FX is 4-byte HEXAdecimal module/function id and DD is a DECIMAL index of the packet
    #[arg(long, value_parser=parse_pkt_inspect)]
    inspect_packets: Option<PacketInspecSpec>,
  },
}
