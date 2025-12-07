use std::{
  collections::HashMap,
  fs::File,
  io::{BufReader, BufWriter, ErrorKind, Read, Write},
  path::{Path, PathBuf},
  sync::{Arc, Mutex, MutexGuard},
};

use anyhow::{Result, anyhow, bail, ensure};

use crate::{
  log::Log,
  modmap::{ExtModuleMap, IntegralFnId, IntegralModId, NumFunUid},
};

/// dumps argument packet data into a file
pub struct FunctionPacketDumper {
  _fnid: IntegralFnId,
  underlying_file: BufWriter<File>,
}

impl FunctionPacketDumper {
  /// creates a packet dumper for a function, data is persisted into the `root` folder
  pub fn new(function_id: IntegralFnId, root: &Path, buffer_capacity_hint: usize) -> Result<Self> {
    let name = function_id.hex_string();
    let path = root.join(name);
    let f = File::create_new(&path).map_err(|e| {
      anyhow!(
        "New function capture file creation failed: path: {:?} error: {e}",
        &path
      )
    })?;
    let b = BufWriter::with_capacity(buffer_capacity_hint, f);

    Ok(Self {
      _fnid: function_id,
      underlying_file: b,
    })
  }

  /// Dumps the raw packet
  ///
  /// Ok variant contains the total number of bytes written
  pub fn dump(&mut self, packet_payload: &mut [u8]) -> Result<usize> {
    let n = self
      .underlying_file
      .write(&(packet_payload.len() as u32).to_le_bytes())
      .map_err(|e| anyhow!("Packet length dump failed: {e}"))?;
    self
      .underlying_file
      .write(packet_payload)
      .map_err(|e| anyhow!(e))
      .map(|v| v + n)
  }
}

/// aggregates function packet dumpers associated with a module
pub struct ModulePacketDumper {
  _mod_id: IntegralModId,
  func_dumpers: HashMap<IntegralFnId, FunctionPacketDumper>,
  _module_root: PathBuf,
  _capacity_hint: usize,
}

impl ModulePacketDumper {
  /// creates a function packet dumper within a module
  ///
  /// `capacity_hint` is propagated to each individual function's writer,
  /// therefore the expected allocated buffers are of this size times the number
  /// of functions within the module
  pub fn new(
    module_id: IntegralModId,
    packet_root: &Path,
    function_ids: &mut dyn Iterator<Item = &IntegralFnId>,
    capacity_hint: usize,
  ) -> Result<Self> {
    let dir_name = module_id.hex_string();
    let module_root = packet_root.join(dir_name.clone());
    std::fs::create_dir_all(&module_root)
      .map_err(|e| anyhow!("Folder creation: {dir_name} failed: {e}"))?;

    let mut func_dumpers = HashMap::new();

    for fnid in function_ids {
      let fndumper = FunctionPacketDumper::new(*fnid, &module_root, capacity_hint)?;

      ensure!(
        func_dumpers.insert(*fnid, fndumper).is_none(),
        "Duplication of function IDs is not expected, duplicated fnid: {}",
        **fnid
      );
    }

    Ok(Self {
      _mod_id: module_id,
      func_dumpers,
      _module_root: module_root,
      _capacity_hint: capacity_hint,
    })
  }

  pub fn get_function_dumper(
    &mut self,
    function: IntegralFnId,
  ) -> Option<&mut FunctionPacketDumper> {
    self.func_dumpers.get_mut(&function)
  }
}

/// dumps argument packet data to a persistent (filesystem) structure
pub struct ArgPacketDumper {
  dumpers: HashMap<IntegralModId, ModulePacketDumper>,
  _root: PathBuf,
}

impl ArgPacketDumper {
  /// creates a dumper that writes data into the root_out_dir directory
  pub fn new(root_out_dir: &Path, module_maps: &ExtModuleMap, mem_limit: usize) -> Result<Self> {
    let mut result_map = HashMap::new();

    let capacity = (mem_limit / module_maps.modules().count().max(1)).max(4096 * 2);

    for module in module_maps.modules() {
      let functions = module_maps.functions(*module);
      ensure!(
        functions.is_some(),
        "Module {} did not map to any function set!",
        **module
      );
      let mut functions = functions.unwrap();

      result_map.insert(
        *module,
        ModulePacketDumper::new(*module, root_out_dir, &mut functions, capacity)?,
      );
    }

    Ok(Self {
      dumpers: result_map,
      _root: root_out_dir.to_owned(),
    })
  }

  pub fn get_packet_dumper(&mut self, id: NumFunUid) -> Option<&mut FunctionPacketDumper> {
    if let Some(md) = self.dumpers.get_mut(&id.module_id) {
      md.get_function_dumper(id.function_id)
    } else {
      None
    }
  }
}

/*
  technically, the following structs and functions (for reading packets out of the fs) are only
  used in the `testing` phase

  however, "packet writing" and "packet reading" are semantically coupled - their data formats
  must be compatible - which is why the "readers" have been placed here
*/

/// facilitates read-only access to a single argument capture data stream
/// (multiple packets inside a capture for a single function)
struct CaptureReader {
  path: PathBuf,
  file: BufReader<File>,
  packets: usize,
  // the index of the argument packet that will be read
  // also, the number of packets read so far (if not reset)
  idx: usize,
}

/// exposes iteration over individual argument packets on individual function basis
/// and metadata (packet counts, ...)
pub struct PacketReader {
  captures: HashMap<NumFunUid, Arc<Mutex<dyn PacketIterator + Send>>>,
}

impl PacketReader {
  // verifies that argument packets can be read
  // returns the number of packets, fails if even one causes read failure
  fn validate_packets(path: &Path, capacity_hint: usize) -> Result<usize> {
    let mut record = CaptureReader {
      path: path.to_path_buf(),
      file: BufReader::with_capacity(capacity_hint, File::open(path)?),
      packets: 0,
      idx: 0,
    };
    let mut tests = 0;
    // a single pass on all packets to count them (and validate them)
    while record.read_next_packet()?.is_some() {
      tests += 1;
    }
    Ok(tests)
  }

  pub fn new(dir: &Path, module_maps: &ExtModuleMap, buff_limit: usize) -> Result<Self> {
    let lg = Log::get("PacketReader");
    let mod_count = module_maps.modules().count();
    ensure!(mod_count > 0, "No modules present, no reason to continue");
    let capacity = (buff_limit / module_maps.modules().count()).max(4096 * 2);
    let mut captures: HashMap<NumFunUid, Arc<Mutex<dyn PacketIterator + Send>>> = HashMap::new();
    for module in module_maps.modules() {
      let functions = module_maps.functions(*module);
      ensure!(
        functions.is_some(),
        "Module {} did not map to any function set!",
        **module
      );
      let functions = functions.unwrap();

      for function in functions {
        let path = dir.join(module.hex_string()).join(function.hex_string());
        let key = (*module, *function).into();
        if !path.exists() {
          lg.warn(format!(
            "Inserting dummy packet iterator for {:?} - missing capture file\n\tFunction name: {:?} \n\tModule name: {:?}",
            path,
            module_maps.get_function_name(key),
            module_maps.get_module_string_id(*module)
          ));
          captures.insert(key, Arc::new(Mutex::new(EmptyPacketIter {})));
          continue;
        }

        lg.trace(format!(
          "Setup {} {}",
          module.hex_string(),
          function.hex_string()
        ));
        let packet_count = Self::validate_packets(&path, capacity)?;
        lg.info(format!("\ttests {packet_count} "));
        let id = (*module, *function).into();

        let args = module_maps
          .get_function_arg_size_descriptors(id)
          .ok_or(anyhow!("Failed look up function argument types"))?;
        // get the number of non-zero-sized arguments (the number of actual arguments we will read)
        let args = args
          .iter()
          .filter(|x| !matches!(x, crate::sizetype_handlers::ArgSizeTypeRef::Empty))
          .count();
        lg.trace(format!("\targs {args} "));

        captures.insert(
          id,
          Arc::new(Mutex::new(CaptureReader {
            path: path.clone(),
            file: BufReader::with_capacity(capacity, File::open(path)?),
            packets: packet_count,
            idx: 0,
          })),
        );
      }
    }

    Ok(Self { captures })
  }

  /// a helper that performs a locking extraction of the desired packet iterator
  fn get_locked_capture_iterator(
    &mut self,
    id: NumFunUid,
  ) -> Result<MutexGuard<'_, dyn PacketIterator + Send + 'static>> {
    if let Some(v) = self.captures.get_mut(&id) {
      Ok(v.lock().unwrap())
    } else {
      bail!("Not found in packet reader m/f {id:?}")
    }
  }

  pub fn read_next_packet(&mut self, id: NumFunUid) -> Result<Option<Vec<u8>>> {
    let mut it = self
      .get_locked_capture_iterator(id)
      .map_err(|e| anyhow!("read_next_packet failed: {e}"))?;
    it.read_next_packet()
  }

  pub fn try_reset(&mut self, id: NumFunUid) -> Result<()> {
    let mut it = self
      .get_locked_capture_iterator(id)
      .map_err(|e| anyhow!("try_reset failed: {e}"))?;
    it.try_reset()
  }

  pub fn get_packet_count(&self, id: NumFunUid) -> Option<u32> {
    self
      .captures
      .get(&id)
      .map(|v| v.lock().unwrap().packet_count())
  }

  pub fn get_upcoming_pkt_idx(&self, id: NumFunUid) -> Option<usize> {
    self
      .captures
      .get(&id)
      .map(|v| v.lock().unwrap().upcoming_packet_idx())
  }
}

/// represents a forward packet iterator that may be reset to the begining
trait PacketIterator {
  fn read_next_packet(&mut self) -> Result<Option<Vec<u8>>>;
  fn packet_count(&self) -> u32;
  fn try_reset(&mut self) -> Result<()>;
  fn upcoming_packet_idx(&mut self) -> usize;
}

/// see [`FunctionPacketDumper::dump`]
impl PacketIterator for CaptureReader {
  fn read_next_packet(&mut self) -> Result<Option<Vec<u8>>> {
    let mut buf = [0u8; std::mem::size_of::<u32>()];
    // reads packet size - the initial 8B
    match self.file.read_exact(&mut buf) {
      Ok(()) => (),
      Err(e) => {
        if e.kind() == ErrorKind::UnexpectedEof {
          self.idx += 1;
          return Ok(None);
        }
        bail!("Failed to read packet len: {}", e)
      }
    };
    let len = u32::from_le_bytes(buf);
    if len == 0 {
      self.idx += 1;
      return Ok(None);
    }

    // reads the packet payload
    let mut result = vec![0; len as usize];
    match self.file.read_exact(&mut result) {
      Ok(_) => {
        self.idx += 1;
        Ok(Some(result))
      }
      Err(e) => Err(anyhow!("Error when reading {} packet len, err {}", len, e)),
    }
  }

  fn packet_count(&self) -> u32 {
    self.packets as u32
  }

  fn try_reset(&mut self) -> Result<()> {
    self.file = BufReader::with_capacity(self.file.capacity(), File::open(self.path.clone())?);
    self.idx = 0;
    Ok(())
  }

  fn upcoming_packet_idx(&mut self) -> usize {
    self.idx
  }
}

struct EmptyPacketIter {}

impl PacketIterator for EmptyPacketIter {
  fn read_next_packet(&mut self) -> Result<Option<Vec<u8>>> {
    Ok(None)
  }

  fn packet_count(&self) -> u32 {
    0
  }

  fn try_reset(&mut self) -> Result<()> {
    Ok(())
  }

  fn upcoming_packet_idx(&mut self) -> usize {
    0
  }
}

pub trait PacketProvider {
  /// returns the packet at a specific index
  /// if `index` overflows, the first packet is returned (if exists)
  fn get_packet(&mut self, id: NumFunUid, index: usize) -> Option<Vec<u8>>;
}

impl PacketProvider for PacketReader {
  fn get_packet(&mut self, id: NumFunUid, index: usize) -> Option<Vec<u8>> {
    // we expect the packets will be accessed in sequential order as the test instance progresses
    // through each packet in series (waiting for each test case to finish)

    // still, in case of a non-sequential access, the reader should be able to fall back to
    // an inefficient yet non-error path
    let packets = self.get_packet_count(id)?;
    if packets == 0 {
      return None;
    } else if index as u32 >= packets {
      // tries to return the first packet
      self.try_reset(id).ok()?;
    } else if self.get_upcoming_pkt_idx(id)? != index {
      // index is within packet count, the following should return the desired packet
      // albeit with linear delay
      self.try_reset(id).ok()?;
      while self.get_upcoming_pkt_idx(id)? < index {
        self.read_next_packet(id).ok()?;
      }
    }
    self.read_next_packet(id).ok()?
  }
}
