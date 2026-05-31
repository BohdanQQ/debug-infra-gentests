use std::collections::HashSet;
use std::ops::Deref;
use std::{collections::HashMap, fs, path::PathBuf};

use crate::Log;
use crate::constants::Constants;
use crate::sizetype_handlers::ArgSizeTypeRef;
use anyhow::{Result, anyhow, ensure};
use num_traits::Num;

// BEGIN SECTION function & module identifiers

fn u32_to_hex_string(num: u32) -> String {
  let [b1, b2, b3, b4] = num.to_le_bytes();
  format!("{b1:02X}{b2:02X}{b3:02X}{b4:02X}")
}

#[derive(Hash, Debug, PartialEq, Eq, Clone, Copy)]
/// a binary identifier of a function - this ID is the central identification point
/// for the hooking library, LLVM plugins as well as internal data structures of
/// llcap-server
///
/// It is usually used along with the IntegralModuleId as a unique function idefntifier
pub struct IntegralFnId(pub u32);

impl From<u32> for IntegralFnId {
  fn from(value: u32) -> Self {
    Self(value)
  }
}

impl IntegralFnId {
  pub fn hex_string(&self) -> String {
    u32_to_hex_string(self.0)
  }

  /// a compile check for the size, if this one fails, also see Self::size
  fn _helper_fn(x: Self) -> u32 {
    x.0
  }

  pub const fn byte_size() -> usize {
    std::mem::size_of::<u32>()
  }
}

#[derive(Hash, Debug, PartialEq, Eq, Clone, Copy)]
/// a binary identifier of an LLVM module - this ID is the central identification point
/// for the hooking library, LLVM plugins as well as internal data structures of
/// llcap-server
///
/// It is usually used along with the IntegralFnId as a unique function idefntifier
pub struct IntegralModId(pub u32);

impl From<u32> for IntegralModId {
  fn from(value: u32) -> Self {
    Self(value)
  }
}

impl IntegralModId {
  pub fn hex_string(&self) -> String {
    u32_to_hex_string(self.0)
  }

  /// a compile check for the size, if this one fails, also see Self::size
  fn _helper_fn(x: Self) -> u32 {
    x.0
  }

  pub const fn byte_size() -> usize {
    std::mem::size_of::<u32>()
  }
}

/// tries to convert a hexadecimal string to a u32 (the underlying type of IntegralMod/FnId)
fn try_from_hex_string<T: Num + std::ops::ShlAssign<u32> + std::ops::AddAssign<u32>>(
  value: &str,
) -> Result<T> {
  ensure!(
    value.chars().count() == std::mem::size_of::<T>() * 2,
    "Invalid size"
  );

  let mut inner: T = T::zero();
  for v in value.chars() {
    inner <<= 4; // in order to not "over shift" in the last iteration

    ensure!(v.is_ascii(), "Invalid module id (ascii): {value}");

    let v = v.to_ascii_uppercase();
    let num_val = match v {
      '0'..='9' => v as u32 - '0' as u32,
      'A'..='F' => v as u32 - 'A' as u32 + 10,
      _ => return Err(anyhow!("Invalid module id (char): {value}")),
    };

    inner += num_val;
  }
  Ok(inner)
}

// convenience implementations of deref to permit simpler usage in the place of u32, ...

impl Deref for IntegralModId {
  type Target = u32;

  fn deref(&self) -> &Self::Target {
    &self.0
  }
}

impl Deref for IntegralFnId {
  type Target = u32;

  fn deref(&self) -> &Self::Target {
    &self.0
  }
}

impl TryFrom<&str> for IntegralModId {
  type Error = anyhow::Error;

  fn try_from(value: &str) -> Result<Self, Self::Error> {
    try_from_hex_string(value).map(Self)
  }
}

impl TryFrom<&str> for IntegralFnId {
  type Error = anyhow::Error;

  fn try_from(value: &str) -> Result<Self, Self::Error> {
    try_from_hex_string(value).map(Self)
  }
}

#[derive(Hash, PartialEq, Eq, Debug, Copy, Clone)]
/// numeric unique function identifier
pub struct NumFunUid {
  pub function_id: IntegralFnId,
  pub module_id: IntegralModId,
}

impl NumFunUid {
  pub fn new(fn_id: IntegralFnId, mod_id: IntegralModId) -> Self {
    Self {
      function_id: fn_id,
      module_id: mod_id,
    }
  }
}

impl From<(IntegralFnId, IntegralModId)> for NumFunUid {
  fn from(value: (IntegralFnId, IntegralModId)) -> Self {
    Self {
      function_id: value.0,
      module_id: value.1,
    }
  }
}

impl From<(IntegralModId, IntegralFnId)> for NumFunUid {
  fn from(value: (IntegralModId, IntegralFnId)) -> Self {
    (value.1, value.0).into()
  }
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
/// textual unique function identifier
pub struct TextFunUid {
  pub fn_name: String,
  pub fn_module: String,
}

// END SECTION function & module identifiers

#[derive(Debug)]
/// Contains information about functions (in a single module)
pub struct FunctionMap {
  fnid_to_demangled_name: HashMap<IntegralFnId, String>,
  demangled_name_to_fnid: HashMap<String, IntegralFnId>,
  fnid_to_argument_sizes: HashMap<IntegralFnId, Vec<ArgSizeTypeRef>>,
}

impl FunctionMap {
  pub fn new(
    values: &[(IntegralFnId, String)],
    readers: HashMap<IntegralFnId, Vec<ArgSizeTypeRef>>,
  ) -> Self {
    Self {
      fnid_to_argument_sizes: readers,
      demangled_name_to_fnid: HashMap::from_iter(values.iter().map(|(x, y)| (y.clone(), *x))),
      fnid_to_demangled_name: HashMap::from_iter(values.to_owned()),
    }
  }

  pub fn is_empty(&self) -> bool {
    self.fnid_to_argument_sizes.is_empty()
  }

  pub fn functions(
    &self,
  ) -> std::collections::hash_map::Keys<'_, IntegralFnId, std::string::String> {
    self.fnid_to_demangled_name.keys()
  }

  pub fn get_name(&self, id: IntegralFnId) -> Option<&String> {
    self.fnid_to_demangled_name.get(&id)
  }

  pub fn get_arg_size_ref(&self, id: IntegralFnId) -> Option<&Vec<ArgSizeTypeRef>> {
    self.fnid_to_argument_sizes.get(&id)
  }

  pub fn get_id(&self, name: &String) -> Option<&IntegralFnId> {
    self.demangled_name_to_fnid.get(name)
  }

  /// masks (removes) function IDs in this object that are NOT present in fn_ids
  pub fn mask_include(&mut self, fn_ids: &HashSet<IntegralFnId>) -> Result<()> {
    let counter_ids = self
      .fnid_to_argument_sizes
      .keys()
      .filter(|k| !fn_ids.contains(k))
      .cloned()
      .collect::<Vec<_>>();
    let lg = Log::get("mask_include");
    for counter_id in counter_ids {
      let expected_name = match self.fnid_to_demangled_name.remove(&counter_id) {
        Some(x) => Ok(x),
        None => Err(anyhow!("Could not find function {}", counter_id.0)),
      }?;
      ensure!(
        self.demangled_name_to_fnid.remove(&expected_name).is_some(),
        "Inconsistent structures: demangled -> id missing {}",
        expected_name
      );
      ensure!(
        self.fnid_to_argument_sizes.remove(&counter_id).is_some(),
        "Inconsistent structures: id -> argsizes missing {} {}",
        counter_id.0,
        expected_name
      );
      lg.trace(format!("Masked out function {}", counter_id.hex_string()));
    }
    Ok(())
  }
}

fn bytes_to_num<T: num_traits::Num>(inp: &[u8]) -> Result<T>
where
  <T as num_traits::Num>::FromStrRadixErr: ToString,
{
  let radix10 = Constants::parse_fnid_radix();
  String::from_utf8(inp.to_vec())
    .map_err(|e| anyhow!(e))
    .and_then(|v| T::from_str_radix(&v, radix10).map_err(|e| anyhow!(e.to_string())))
}

/// parses a function metadata tuple
///
/// expected format:
///
/// `name | fnId | argCnt | [argSpec: argCnt \0 delimited strings ]`
///
/// Note that this format is **not** binary, the `name`, `fnId`, `argCnt`, `...` are all
/// parsed as [`String`]
fn parse_fn_id_tuple(inp: &[&[u8]]) -> Result<(IntegralFnId, String, Vec<u16>)> {
  ensure!(
    inp.len() >= 3,
    "Invalid ID format, expecting at least 3 items per line"
  );

  let try_name = String::from_utf8(inp[0].to_vec())?;
  let fnid: IntegralFnId = IntegralFnId(bytes_to_num(inp[1])?);

  let arg_count: u64 = bytes_to_num(inp[2])?;
  ensure!(
    3 + arg_count == inp.len() as u64,
    "Invalid argument count - not in sync with the data"
  );

  let mut argument_specifiers: Vec<u16> = Vec::new();
  for v in inp.iter().skip(3) {
    let arg_spec: u16 = bytes_to_num(v)?;
    argument_specifiers.push(arg_spec);
  }

  Ok((fnid, try_name, argument_specifiers))
}

impl TryFrom<&[&[u8]]> for FunctionMap {
  type Error = anyhow::Error;
  /// parses the function metadata "lines"
  ///
  /// Each "line" is a \0-delimited list of bytes (format specified in [`parse_fn_id_tuple`])
  fn try_from(value: &[&[u8]]) -> Result<Self, Self::Error> {
    let newline_split = value.iter().filter(|v| !v.is_empty());
    let zero_splits: Vec<Vec<&[u8]>> = newline_split
      .map(|v: &&[u8]| v.split(|v| *v == 0x0).collect::<Vec<&[u8]>>())
      .filter(|v| !v.is_empty())
      .collect();

    let parsed_pairs = zero_splits
      .iter()
      .map(|split_pair| parse_fn_id_tuple(split_pair));

    let mut target = Self::new(&[], HashMap::new());

    let lg = Log::get("FunctionMap::TryFrom[u8]");
    for pair_res in parsed_pairs {
      match pair_res {
        Err(e) => lg.warn(format!("Could not parse function ID pair: {e}")),
        Ok((id, name, specifiers)) => {
          if let Some(old_name) = target.fnid_to_demangled_name.insert(id, name.clone()) {
            lg.warn(format!(
              "Duplicate function ID - demangled name, this should not happen within a module! Function ID: {}, name 1: {}, name 2: {}",
              *id, old_name, name
            ));
          }
          target.demangled_name_to_fnid.insert(name.clone(), id);

          let mut size_types: Vec<ArgSizeTypeRef> = Vec::with_capacity(specifiers.len());

          for sz_type in &specifiers {
            let spec = ArgSizeTypeRef::try_from(*sz_type)?;
            // FIXME: temporary workaround to allow for protobuf packet
            if let ArgSizeTypeRef::Custom = spec {
              size_types.push(spec);
              break;
            }
          }
          if size_types.is_empty() {
            lg.warn(format!("No dumpable args detected in fn {name}"));
          }

          lg.trace(format!(
            "Added fn:\n{}:\n\tid:{}\targs: {:?}",
            name, *id, size_types
          ));
          if let Some(old_thing) = target.fnid_to_argument_sizes.insert(id, size_types) {
            lg.warn(format!(
              "Duplicate function ID - argument size type, this should not happen within a module! Function ID: {}, name: {}, old types: {:?}, new specifiers: {:?}",
              *id, name, old_thing, specifiers
            ));
          }
        }
      }
    }

    Ok(target)
  }
}

#[derive(Debug)]
/// "External Module Mapping" is a simple data structure where function data is stored in a
/// Module -> Function hierarchy
///
/// This object holds function and module metadata that can be parsed from module maps as generated
/// by the accompanying LLVM plugin
pub struct ExtModuleMap {
  function_ids: HashMap<IntegralModId, FunctionMap>,
  /// Identifiers of modules as exposed by LLVM (currently those are the paths of source files)
  module_paths: HashMap<IntegralModId, String>,
}

impl Default for ExtModuleMap {
  fn default() -> Self {
    Self::new()
  }
}

impl ExtModuleMap {
  pub fn new() -> Self {
    Self {
      function_ids: HashMap::new(),
      module_paths: HashMap::new(),
    }
  }

  pub fn modules(&self) -> std::collections::hash_map::Keys<'_, IntegralModId, FunctionMap> {
    self.function_ids.keys()
  }

  pub fn functions(
    &self,
    mod_id: IntegralModId,
  ) -> Option<std::collections::hash_map::Keys<'_, IntegralFnId, std::string::String>> {
    self.function_ids.get(&mod_id).map(|f| f.functions())
  }

  /// performs masking (removal) of functions whose IDs are NOT included in the targets argument
  ///
  /// the postcondition of this function is that only the intersection of self and targets
  /// is preserved in self while other functions (and modules, if they become empty / are not included in targets) are removed
  pub fn mask_include(&mut self, targets: &[TextFunUid]) -> Result<()> {
    let lg = Log::get("mask_include");
    lg.info(format!("Masking {} values", targets.len()));

    // map targets into hash map & sets of numerical IDs
    let mut allowlist_fn: HashMap<IntegralModId, HashSet<IntegralFnId>> = HashMap::new();
    for id in targets {
      let (m, f) = (&id.fn_module, &id.fn_name);
      let mod_id = match self.get_module_hash_by_name(m) {
        Some(x) => x,
        None => {
          lg.warn(format!("Module hash for name {m} not found"));
          continue;
        }
      };

      if let Some(fn_id) = self.get_function_id(mod_id, f).cloned() {
        allowlist_fn
          .entry(mod_id)
          .and_modify(|set| {
            set.insert(fn_id);
          })
          .or_insert(HashSet::from_iter([fn_id].into_iter()));
      } else {
        lg.warn(format!("Function {f} not found in module {:02X}", mod_id.0));
      }
    }
    let unknown = "unknown".to_owned();

    // perform filtering for each module in targets
    for modid in self
      .module_paths
      .keys()
      .filter(|m| allowlist_fn.contains_key(*m))
    {
      let allowed = allowlist_fn
        .get(modid)
        .ok_or(anyhow!("Unknown module ID {:?}", modid))?;
      let functions = self
        .function_ids
        .get_mut(modid)
        .ok_or(anyhow!("Functions not found for {:?}", modid))?;
      lg.info(format!("Module {}:", modid.hex_string()));
      functions.mask_include(allowed)?;
      lg.info(format!(
        "Functions {:?} remain in module {}",
        allowed
          .iter()
          .map(|x| (
            x.hex_string(),
            self
              .get_function_name((*modid, *x).into())
              .unwrap_or(&unknown)
          ))
          .collect::<Vec<_>>(),
        modid.hex_string()
      ));
    }

    // remove modules NOT included in targets
    let mods = self.function_ids.keys().cloned().collect::<Vec<_>>();
    for md in mods {
      let fun = &self.function_ids[&md];
      if fun.is_empty() || !allowlist_fn.contains_key(&md) {
        self.function_ids.remove(&md);
        self.module_paths.remove(&md);
        lg.trace(format!("Removed module {}", md.hex_string()));
      }
    }

    Ok(())
  }

  /// inserts a new LLVM module from a module-map file (as generated by the LLVM plugin)
  ///
  /// The filename suffix of the path to the module file is expected to be in the hexadecimal, directly convertible to [`IntegralModId`]
  ///  
  /// Each module map file is a newline-separated list:
  ///
  /// first line is reserved for module metadata (module path for now)
  /// all other lines are function metadata lines (for futher format info,
  /// see [`FunctionMap`])
  pub fn add_module(&mut self, path_to_modfile: &PathBuf) -> Result<()> {
    let modhash = if let Some(hash_res) = path_to_modfile
      .file_name()
      .and_then(|v| v.to_str())
      .and_then(|v| IntegralModId::try_from(v).into())
    {
      hash_res
    } else {
      Err(anyhow!("Invalid path {:?}", path_to_modfile))
    }?;

    ensure!(
      !self.function_ids.contains_key(&modhash),
      "Duplicate module hash {}",
      modhash.hex_string()
    );

    let contents = fs::read(path_to_modfile).map_err(|e| {
      anyhow!(
        "add_module file read {} error: {e}",
        path_to_modfile.to_string_lossy()
      )
    })?;
    let lines: Vec<&[u8]> = contents.split(|x| x == &0xa).collect();

    let (module_str_id, fn_map) = if let Some((head, tail)) = lines.split_first() {
      String::from_utf8(head.to_vec())
        .map_err(|e| anyhow!("add_module cannot not parse string id of a module: {head:?}\n{e}",))
        .map(|v| (v, tail))
    } else {
      Err(anyhow!("Empty module file"))
    }?;

    let fn_map = FunctionMap::try_from(fn_map)?;
    self.function_ids.insert(modhash, fn_map);
    self.module_paths.insert(modhash, module_str_id);
    Ok(())
  }

  /// module path (LLVM) -> IntegralModId
  pub fn get_module_hash_by_name(&self, name: &String) -> Option<IntegralModId> {
    self
      .module_paths
      .iter()
      .find(|(_, v)| *v == name)
      .map(|v| *v.0)
  }

  pub fn get_function_name(&self, id: NumFunUid) -> Option<&String> {
    self
      .function_ids
      .get(&id.module_id)?
      .get_name(id.function_id)
  }

  pub fn get_function_id(&self, mod_id: IntegralModId, fn_name: &String) -> Option<&IntegralFnId> {
    self.function_ids.get(&mod_id)?.get_id(fn_name)
  }

  pub fn get_function_arg_size_descriptors(&self, id: NumFunUid) -> Option<&Vec<ArgSizeTypeRef>> {
    self
      .function_ids
      .get(&id.module_id)?
      .get_arg_size_ref(id.function_id)
  }

  pub fn get_module_string_id(&self, mod_id: IntegralModId) -> Option<&String> {
    self.module_paths.get(&mod_id)
  }

  pub fn print_summary(&self) {
    println!("Module map summary:");
    println!("Total Modules loaded: {}", self.function_ids.len());
    println!(
      "Total Functions loaded: {}",
      self
        .function_ids
        .values()
        .map(|fnids| fnids.fnid_to_demangled_name.len())
        .sum::<usize>()
    );
  }
}

impl TryFrom<&PathBuf> for ExtModuleMap {
  type Error = anyhow::Error;

  /// tries to parse module maps from a **directory**
  fn try_from(path: &PathBuf) -> Result<Self, Self::Error> {
    ensure!(
      path.exists() && path.is_dir(),
      "{} is not a directory",
      path.to_string_lossy()
    );
    let mut target = ExtModuleMap::new();

    let dir = std::fs::read_dir(path)
      .map_err(|e| anyhow!("Cannot open directory {} ({e})", path.to_string_lossy()))?;

    for file in dir {
      let res = match file {
        Err(e) => Err(anyhow!(
          "Module file {} could not be listed: {e}",
          path.to_string_lossy()
        )),
        Ok(entry) => target.add_module(&entry.path()),
      };

      if let Err(e) = res {
        Log::get("ExtmoduleMap::try_from(PathBuff)").warn(format!("Failed to read module: {e}"));
      }
    }

    Ok(target)
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn basic_fn_meta_line_parse() {
    let fn_name = "somefn";
    let fn_id = 0u32;
    let arg_list = [1u16, 234u16, 44u16];
    let underlying = format!(
      "{}\0{}\0{}\0{}",
      fn_name,
      fn_id,
      arg_list.len(),
      arg_list.map(|x| x.to_string()).join("\0")
    );
    let data = underlying.as_bytes();
    let split = data.split(|x| *x == b'\0').collect::<Vec<&[u8]>>();
    let parsed = parse_fn_id_tuple(&split);
    let parsed = match parsed {
      Err(e) => {
        println!("{}", e);
        assert!(false);
        unreachable!()
      }
      Ok(v) => v,
    };
    let (pfid, pname, pargs) = parsed;
    assert!(*pfid == fn_id);
    assert!(pname == fn_name);
    assert!(pargs.len() == arg_list.len());
    assert!(arg_list.iter().enumerate().all(|(i, v)| { pargs[i] == *v }));
  }

  fn setup_test_map() -> ExtModuleMap {
    let function_ids = HashMap::from_iter(vec![
      (
        IntegralModId(1),
        FunctionMap {
          fnid_to_demangled_name: HashMap::from_iter(vec![
            (IntegralFnId(1), "Name1".to_string()),
            (IntegralFnId(2), "Name2".to_string()),
          ]),
          demangled_name_to_fnid: HashMap::from_iter(vec![
            ("Name1".to_string(), IntegralFnId(1)),
            ("Name2".to_string(), IntegralFnId(2)),
          ]),
          fnid_to_argument_sizes: HashMap::from_iter(vec![
            (IntegralFnId(1), vec![]),
            (IntegralFnId(2), vec![]),
          ]),
        },
      ),
      (
        IntegralModId(2),
        FunctionMap {
          fnid_to_demangled_name: HashMap::from_iter(vec![
            (IntegralFnId(1), "Name3".to_string()),
            (IntegralFnId(2), "Name4".to_string()),
          ]),
          demangled_name_to_fnid: HashMap::from_iter(vec![
            ("Name3".to_string(), IntegralFnId(1)),
            ("Name4".to_string(), IntegralFnId(2)),
          ]),
          fnid_to_argument_sizes: HashMap::from_iter(vec![
            (IntegralFnId(1), vec![]),
            (IntegralFnId(2), vec![]),
          ]),
        },
      ),
    ]);

    let module_paths = HashMap::from_iter(vec![
      (IntegralModId(1), "path1".to_string()),
      (IntegralModId(2), "path2".to_string()),
    ]);

    ExtModuleMap {
      function_ids,
      module_paths,
    }
  }

  #[test]
  fn mask_include_removes_fn() {
    let mut map = setup_test_map();

    assert!(
      map
        .mask_include(&[TextFunUid {
          fn_name: "Name1".to_string(),
          fn_module: "path1".to_string()
        }])
        .is_ok()
    );

    assert!(
      map
        .get_function_id(IntegralModId(1), &"Name2".to_string())
        .is_none()
    );
    assert!(
      map
        .get_function_arg_size_descriptors((IntegralModId(1), IntegralFnId(2)).into())
        .is_none()
    );
    assert!(
      map
        .get_function_name((IntegralModId(1), IntegralFnId(2)).into())
        .is_none()
    );

    assert!(
      map
        .get_function_id(IntegralModId(2), &"Name3".to_string())
        .is_none()
    );
    assert!(
      map
        .get_function_arg_size_descriptors((IntegralModId(2), IntegralFnId(1)).into())
        .is_none()
    );
    assert!(
      map
        .get_function_name((IntegralModId(2), IntegralFnId(1)).into())
        .is_none()
    );
  }

  #[test]
  fn mask_include_removes_mod() {
    let mut map = setup_test_map();

    assert!(
      map
        .mask_include(&[TextFunUid {
          fn_name: "Name1".to_string(),
          fn_module: "path1".to_string()
        }])
        .is_ok()
    );

    assert!(
      map
        .get_function_id(IntegralModId(2), &"Name3".to_string())
        .is_none()
    );
    assert!(
      map
        .get_function_arg_size_descriptors((IntegralModId(2), IntegralFnId(1)).into())
        .is_none()
    );
    assert!(
      map
        .get_function_name((IntegralModId(2), IntegralFnId(1)).into())
        .is_none()
    );

    assert!(
      map
        .get_function_id(IntegralModId(2), &"Name4".to_string())
        .is_none()
    );
    assert!(
      map
        .get_function_arg_size_descriptors((IntegralModId(2), IntegralFnId(2)).into())
        .is_none()
    );
    assert!(
      map
        .get_function_name((IntegralModId(2), IntegralFnId(2)).into())
        .is_none()
    );

    assert!(map.get_module_string_id(IntegralModId(2)).is_none());
  }

  #[test]
  fn mask_include_keeps() {
    let mut map = setup_test_map();
    let _ = map.mask_include(&[TextFunUid {
      fn_name: "Name1".to_string(),
      fn_module: "path1".to_string(),
    }]);

    assert!(
      matches!(map.get_function_id(IntegralModId(1), &"Name1".to_string()), Some(v) if **v == 1)
    );
    assert!(
      map
        .get_function_arg_size_descriptors((IntegralModId(1), IntegralFnId(1)).into())
        .is_some()
    );
    assert!(
      matches!(map.get_function_name((IntegralModId(1), IntegralFnId(1)).into()), Some(v) if v == "Name1")
    );
  }
}
