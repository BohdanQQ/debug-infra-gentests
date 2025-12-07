use anyhow::{Result, anyhow, bail, ensure};
use std::{
  collections::HashSet,
  fs::File,
  io::{self, BufRead, BufReader, Write},
  path::{Path, PathBuf},
};

use crate::{
  constants::Constants,
  log::Log,
  modmap::{ExtModuleMap, IntegralFnId, IntegralModId, NumFunUid, TextFunUid},
};

/// Call tracing message received from instrumented application
pub struct Message(pub NumFunUid);

pub fn print_call_tracing_summary(frequencies: &mut [(NumFunUid, u64)], mods: &ExtModuleMap) {
  let lg = Log::get("summary");
  let mut seen_modules: HashSet<IntegralModId> = HashSet::new();

  for (idx, (fninfo, freq)) in frequencies.iter().enumerate() {
    let modstr = mods.get_module_string_id(fninfo.module_id);
    let fn_name = mods.get_function_name(*fninfo);
    seen_modules.insert(fninfo.module_id);
    if modstr.and(fn_name).is_none() {
      lg.warn(format!(
        "Function ID or module ID confusion. Fun ID: {} {:?} Mod ID: {} {:?}",
        *fninfo.function_id, fn_name, *fninfo.module_id, modstr
      ));
      continue;
    }

    println!(
      "{idx} - {freq} - {} (module {})",
      fn_name.unwrap(),
      modstr.unwrap()
    );
  }
  mods.print_summary();
  println!(
    "Total traced calls: {}",
    frequencies.iter_mut().map(|x| x.1).sum::<u64>()
  );
  println!("Traces originated from {} modules", seen_modules.len());
}

fn get_line_input() -> Result<String> {
  let mut user_input = String::new();
  std::io::stdin().read_line(&mut user_input)?;
  Ok(user_input)
}

type SelectionResult = Result<Vec<TextFunUid>>;

/// returns the list of textual identifiers of functions `fn_uids` whose function name contains the `substring`
fn try_name_selection(
  substring: &str,
  fn_uids: &[NumFunUid],
  mapping: &ExtModuleMap,
) -> SelectionResult {
  let lg = Log::get("try_name_selection");
  if substring.is_empty() {
    lg.warn("Empty substring selection selects all functions");
  }

  let mut result = vec![];

  for id in fn_uids {
    if let Some(fn_name) = mapping
      .get_function_name(*id)
      .filter(|n| n.contains(substring))
      .cloned()
      && let Some(fn_module) = mapping.get_module_string_id(id.module_id).cloned()
    {
      result.push(TextFunUid { fn_name, fn_module });
    }
  }

  Ok(result)
}

// parses the input as list of ' '-separated indicies into `fn_uids`
// returns `fn_uids` selected in the input
fn try_index_selection(
  input: &str,
  fn_uids: &[NumFunUid],
  mapping: &ExtModuleMap,
) -> SelectionResult {
  let lg = Log::get("try_index_selection");
  let mut result = vec![];

  for inp in input.trim().split(' ').map(|v| v.trim()) {
    let i = inp.parse::<usize>()?;

    if i < fn_uids.len() {
      let id = fn_uids[i];
      let fn_str = mapping.get_function_name(id);
      let mod_str = mapping.get_module_string_id(id.module_id);

      match (fn_str, mod_str) {
        (None, _) | (_, None) => {
          lg.crit(format!("Skipping index {inp} - invalid fn-id or mod-id"));
        }
        (Some(fs), Some(ms)) => result.push(TextFunUid {
          fn_name: fs.clone(),
          fn_module: ms.clone(),
        }),
      }
    } else {
      lg.crit(format!("Skipping index {inp} - out of range"));
    }
  }

  Ok(result)
}

/// a CLI "dialog" that collects a list of functions the user wishes to trace
pub fn obtain_function_id_selection(
  ordered_traces: &[NumFunUid],
  mapping: &ExtModuleMap,
) -> SelectionResult {
  const NAME_SEARCH_START: &str = "N:";
  println!(
    "Enter indicies of function to test (single line, numbers separated by spaces) or a string to match in funcion's name (in the format {NAME_SEARCH_START}<name>):"
  );
  let user_input = get_line_input().expect("Error collecting input");

  if let Some((_, after)) = user_input.split_once(NAME_SEARCH_START) {
    try_name_selection(after.trim(), ordered_traces, mapping)
  } else {
    try_index_selection(user_input.trim(), ordered_traces, mapping)
  }
}

fn user_input_or_default(default: &str, prompt: &str) -> Result<PathBuf> {
  println!("{prompt}: ({default} is default)");

  let inp = get_line_input()?;
  let inp = inp.trim();

  Ok(if inp.is_empty() {
    Log::get("user_input").info("Using default path");
    PathBuf::from(default)
  } else {
    PathBuf::from(inp)
  })
}

/// exports function identifiers for later reuse (in instrumentation, re-importing between llcap-server re-executions)
pub fn export_tracing_selection(
  selection: &[TextFunUid],
  mapping: &ExtModuleMap,
  output: Option<PathBuf>,
) -> Result<()> {
  let lg = Log::get("export_tracing_selection");
  let default_path = Constants::default_selected_functions_path();
  let path = if let Some(out_path) = output {
    out_path
  } else {
    user_input_or_default(
      default_path,
      "Enter path (relative to working directory) where selected traces should be saved",
    )?
  };

  ensure!(
    !path.is_dir(),
    "Path {} a directory",
    path.to_string_lossy()
  );

  let mut file =
    File::create(&path).map_err(|e| anyhow!("export_tracing_seleciton failed: {e}"))?;
  for selected in selection {
    let mod_hash = mapping.get_module_hash_by_name(&selected.fn_module);
    ensure!(
      mod_hash.is_some(),
      "Could not resolve module hash from {}",
      selected.fn_module
    );
    let mod_hash = mod_hash.unwrap();
    let fn_id = mapping.get_function_id(mod_hash, &selected.fn_name);
    ensure!(
      fn_id.is_some(),
      "Could not resolve id of function {}, mod: {}, mapping: {:?}",
      selected.fn_name,
      selected.fn_module,
      mapping
    );
    let fn_id = *fn_id.unwrap();

    let to_write = format!(
      "{}\x00{}\x00{}\x00{}\n",
      selected.fn_module, mod_hash.0, selected.fn_name, *fn_id
    )
    .into_bytes();

    let wr_res = file.write(&to_write)?;
    ensure!(
      wr_res == to_write.len(),
      "Exporting of selection written unexpected amount: {wr_res} compared to {}",
      to_write.len()
    );
  }
  lg.info(format!("Successfully exported to {path:?}"));
  Ok(())
}

/// imports selection (as exported by [`export_tracing_selection`])
pub fn import_tracing_selection(path: &Path) -> Result<Vec<TextFunUid>> {
  let mut result = Vec::with_capacity(8);

  let mut f =
    BufReader::new(File::open(path).map_err(|e| anyhow!("import_tracing_seleciton  failed: {e}"))?);

  let mut line = String::with_capacity(256);
  while let Ok(len) = f.read_line(&mut line) {
    if len == 0 {
      break;
    }

    let mut it = line.trim().split('\x00');
    let module = it.next();
    let function = it.nth(1);
    ensure!(module.is_some(), "Module name not found in {:?}", line);
    ensure!(function.is_some(), "Function name not found in {:?}", line);

    result.push(TextFunUid {
      fn_module: module.unwrap().to_string(),
      fn_name: function.unwrap().to_string(),
    });

    line.clear();
  }

  Ok(result)
}

pub type CallTraceImportExport = Vec<(NumFunUid, u64)>;

/// exports traced information for later reuse (e.g. for importing later to generate a new function selection without the need to call-trace the target program)
pub fn export_call_trace_data(
  sorted_data: &CallTraceImportExport,
  out_path: PathBuf,
) -> Result<()> {
  let lg = Log::get("call_tracing::export_data");

  let mut f = File::create(&out_path)?;
  for (fninfo, freq) in sorted_data {
    let module_hash = fninfo.module_id;

    f.write_fmt(format_args!(
      "{}-{}-{}\n",
      freq, *fninfo.function_id, module_hash.0
    ))
    .map_err(|e| anyhow!("export_data failed: {e}"))?;
  }

  lg.info(format!("Exported call tracing data to {out_path:?}"));
  Ok(())
}

/// imports call tracing results as exported by [`export_data`]
pub fn import_call_trace_data(
  in_path: PathBuf,
  modmap: &ExtModuleMap,
) -> Result<CallTraceImportExport> {
  let mut result = vec![];

  let f = File::open(&in_path)?;
  let mut reader = io::BufReader::new(f);
  let mut line = String::new();

  while let Ok(c) = reader.read_line(&mut line) {
    if c == 0 {
      break;
    } else if c == 1 {
      continue;
    }

    let split: Vec<&str> = line.trim().split('-').collect();
    ensure!(
      split.len() == 3,
      "Invalid import format, split len: {} of {}",
      split.len(),
      line
    );

    let parse_res: (Result<u64, _>, Result<u32, _>, Result<u32, _>) = match split.as_slice() {
      &[s1, s2, s3] => (s1.parse(), s2.parse(), s3.parse()),
      _ => unreachable!("Split must be of length 3!"),
    };
    line.clear();

    if let (Ok(fr), Ok(fnid), Ok(modhash)) = parse_res {
      let id = (IntegralFnId(fnid), IntegralModId(modhash)).into();
      ensure!(
        modmap.get_function_arg_size_descriptors(id).is_some(),
        "Function not found, fn: {id:?}"
      );
      result.push((id, fr));
    } else {
      bail!(
        "Failed to parse import, invalid format in one of the numbers {:?}",
        parse_res
      );
    }
  }

  Ok(result)
}
