pub mod arg_capture;
pub mod call_tracing;
pub mod hooklib_commons;
pub mod mem_utils;
use anyhow::{Result, anyhow, bail, ensure};
use hooklib_commons::{META_MEM_NAME, META_MEM_SIZE_NAME, META_SEM_ACK, META_SEM_DATA, ShmMeta};
use std::ffi::{self, CStr, c_void};
use std::slice;

use crate::libc_wrappers::fd::try_shm_unlink_fd;
use crate::libc_wrappers::sem::{FreeFullSemNames, Semaphore};
use crate::libc_wrappers::shared_memory::ShmemHandle;
use crate::libc_wrappers::wrappers::to_cstr;
use crate::log::Log;
use crate::modmap::ExtModuleMap;
use crate::shmem_capture::hooklib_commons::{
  CRIU_CHECKPOINT_DIR_PATH_MAXLEN_WZERO, MODE_CHECKPOINT_TESTING_DO_CHECKPOINT,
  MODE_CHECKPOINT_TESTING_NOCHECKPOINT, MODE_MT_TESTING, MODE_TESTING,
};
use crate::shmem_capture::mem_utils::{ptr_add_nowrap, ptr_add_nowrap_mut};
use crate::stages::common::InfraParams;
use crate::stages::test_registry::{TestRegisryItem, TestingMode};
use crate::stages::testing::test_server_socket;
use libc::{O_CREAT, mempcpy};

/// a handle to all shared memory infrastructure necessary for function tracing (call tracing and argument capture)
pub struct TracingInfra {
  pub sem_free: Semaphore,
  pub sem_full: Semaphore,
  pub backing_buffer: ShmemHandle,
  logical_buffer_size: usize,
  logical_buffer_count: usize,
  current_index: usize,
  got_buff_flag: bool,
}

pub struct FinalizerInfraInfo {
  name: String,
  logical_buffer_count: usize,
}

impl FinalizerInfraInfo {
  pub fn try_open(self) -> Result<CrashFinalizerInfra> {
    Ok(CrashFinalizerInfra {
      sem_full: Semaphore::try_open(&self.name, 0, O_CREAT.into(), None)?,
      logical_buffer_count: self.logical_buffer_count,
    })
  }
}

pub struct CrashFinalizerInfra {
  sem_full: Semaphore,
  logical_buffer_count: usize,
}

impl CrashFinalizerInfra {
  pub fn finalization_flush(mut self) -> Result<()> {
    for _ in 0..self.logical_buffer_count * 2 {
      self.sem_full.try_post()?;
    }
    Ok(())
  }
}

/// a uniquely-typed wrapper around a start pointer to a logical buffer
/// which points to a valid size field
pub struct BufferStartPtr<'a> {
  ptr: ReadOnlyBufferPtr<'a>,
}

impl<'a> BufferStartPtr<'a> {
  pub fn new(slice: &'a [u8]) -> Result<Self> {
    ensure!(
      slice.len() > std::mem::size_of::<u32>(),
      "No space for length field"
    );
    Ok(Self {
      ptr: ReadOnlyBufferPtr::new(slice),
    })
  }

  /// read the size field and return a buffer that supports reading of data
  pub fn shift_init_data(mut self) -> Result<ReadOnlyBufferPtr<'a>> {
    let valid_size: u32 = self.ptr.unaligned_shift_num_read()?;
    Log::get("shift_init").trace(format!("Buffer payload size: {valid_size}"));
    Ok(self.ptr.constrain(valid_size as usize))
  }
}

/// a uniquely-typed wrapper around a logical buffer's inner data
pub struct ReadOnlyBufferPtr<'a> {
  slice: &'a [u8],
}

impl<'a> ReadOnlyBufferPtr<'a> {
  const fn len(&self) -> usize {
    self.slice.len()
  }

  pub fn empty(&self) -> bool {
    self.len() == 0
  }

  pub fn constrain(self, max_size: usize) -> Self {
    Self::new(self.slice.split_at(max_size).0)
  }

  const fn new(slice: &'a [u8]) -> Self {
    Self { slice }
  }

  pub const fn shift(&mut self, offset: usize) {
    self.slice = self.slice.split_at(offset).1;
  }

  // splits the underlying slice at offset, returning the first part
  // if successful (and shifting the underlying slice to the second part)
  fn shift_split(&mut self, size: usize) -> Result<&[u8]> {
    let (res, rest) = self.slice.split_at(size);
    ensure!(res.len() == size, "Buff does not contain enough bytes");
    self.slice = rest;
    Ok(res)
  }

  // performs an unaligned read of bytes into a numerical value
  pub fn unaligned_shift_num_read<S: num_traits::FromBytes>(&mut self) -> Result<S>
  where
    for<'x> &'x <S as num_traits::FromBytes>::Bytes: TryFrom<&'x [u8]>,
    for<'y> <&'y <S as num_traits::FromBytes>::Bytes as TryFrom<&'y [u8]>>::Error:
      Into<anyhow::Error>,
  {
    Ok(S::from_le_bytes(
      self
        .shift_split(std::mem::size_of::<S>())?
        .try_into()
        .map_err(|e: <&<S as num_traits::FromBytes>::Bytes as TryFrom<&[u8]>>::Error| anyhow!(e))?,
    ))
  }

  // caller must ensure that the slice's pointer is never exposed or (worse) used as ptr to
  // mutable
  pub const fn as_slice(&self) -> &[u8] {
    self.slice
  }
}

pub struct BorrowedReadBuffer<'a> {
  _borrow_handle: std::cell::Ref<'a, *const u8>,
  pub buffer: ReadOnlyBufferPtr<'a>,
}

pub struct BorrowedOneshotWritePtr<'a, T> {
  _borrow_handle: std::cell::RefMut<'a, *mut u8>,
  data: *mut T,
}

impl<'a, T> BorrowedOneshotWritePtr<'a, T> {
  /// safety: ptr + offset must be a valid, unaliased pointer to T, caller also ensures T is
  /// trivial (as in a memcpy to size of T is a valid representation of T) and size of T is not less than 4 (see the write function for explanation)
  pub unsafe fn at(ptr: std::cell::RefMut<'a, *mut u8>, offset: usize) -> Result<Self> {
    // safety: caller
    let data_ptr = unsafe { ptr.add(offset) };
    ensure!(data_ptr.cast::<T>().is_aligned(), "Unaligned offset");

    Ok(Self {
      _borrow_handle: ptr,
      data: data_ptr.cast::<T>(),
    })
  }

  pub fn write(self, value: T) {
    // safety: construction
    // this will panic for unaligned buffers - we require the alignment of at least 4 bytes as that is the smallest atomic read performed by the llcap-server
    // the minimum buffer size is checked when constructing the comms infra
    unsafe { *self.data = value };
  }
}

impl TracingInfra {
  /// blocks until a buffer has been filled by the instrumented applicaiton
  pub fn wait_for_full_buffer(&mut self) -> Result<BorrowedReadBuffer<'_>> {
    self
      .sem_full
      .try_wait(None)
      .map_err(|e| anyhow!("in wait_for_full_buffer: {e}"))?;
    self.got_buff_flag = true;
    Log::get("get_buff").trace(format!("get @ index {}", self.current_index));
    self.get_checked_base_ptr()
  }

  pub fn finish_buffer(&mut self) -> Result<()> {
    {
      let buffer = self.get_checked_base_ptr_mut()?;
      buffer.write(0);
    }

    Log::get("finish_buffer").trace(format!(
      "cleared {}/{}",
      self.current_index,
      self.buffer_count()
    ));
    let sem_res = self.sem_free.try_post();
    sem_res.map_err(|e| {
      anyhow!(
        "While posting a free buffer (idx {}): {e}",
        self.current_index
      )
    })?;
    self.got_buff_flag = false;
    self.current_index += 1;
    self.current_index %= self.logical_buffer_count;
    Ok(())
  }

  pub fn deinit(self) -> Result<()> {
    let (semfree, semfull, buffers_shm) = (self.sem_free, self.sem_full, self.backing_buffer);
    let mem_deinit = deinit_shmem(buffers_shm);
    let sem_uninit = deinit_semaphores(semfree, semfull);

    let goodbye_errors = [mem_deinit, sem_uninit]
      .iter()
      .fold(String::new(), |acc, v| {
        if let Err(e) = v {
          acc + &e.to_string()
        } else {
          acc
        }
      });
    ensure!(
      goodbye_errors.is_empty(),
      "Deinit failures: {}",
      goodbye_errors
    );
    Ok(())
  }

  fn init_semaphores(
    n_buffs: u32,
    free_name: &str,
    full_name: &str,
  ) -> Result<(Semaphore, Semaphore)> {
    let free_sem = Semaphore::try_open_exclusive(free_name, n_buffs)?;
    let full_sem = Semaphore::try_open_exclusive(full_name, 0);

    match full_sem {
      Err(e) => match deinit_semaphore_single(free_sem) {
        Ok(()) => Err(anyhow!(e)),
        Err(e2) => Err(anyhow!(
          "Failed cleanup after FULL semaphore init failure: {e2}, init failure: {e}"
        )),
      },
      Ok(v) => Ok((free_sem, v)),
    }
  }

  pub fn try_new(resource_prefix: &str, params: InfraParams) -> Result<(Self, FinalizerInfraInfo)> {
    let (buff_count, buff_len) = (params.buff_count, params.buff_len);
    let lg = Log::get("init_tracing");
    let FreeFullSemNames {
      free: free_name,
      full: full_name,
    } = FreeFullSemNames::new(resource_prefix, "capture", "base");

    let (sem_free, sem_full) = Self::init_semaphores(buff_count, &free_name, &full_name)?;
    lg.info("Initializing shmem");
    lg.warn(format!(
      "Cleanup arguments for finalizer: {} {}",
      sem_full.cname().trim_end_matches('\x00'),
      buff_count
    ));

    let backing_buffer = init_shmem(resource_prefix, buff_count, buff_len)?;

    let logical_buffer_count = buff_count as usize;
    let tracing_infra = Self {
      sem_free,
      sem_full,
      backing_buffer,
      logical_buffer_size: buff_len as usize,
      logical_buffer_count,
      current_index: 0,
      got_buff_flag: false,
    };

    let finalizing_infra = FinalizerInfraInfo {
      name: full_name,
      logical_buffer_count,
    };
    Ok((tracing_infra, finalizing_infra))
  }

  fn buffer_offset(&self, idx: usize) -> Result<usize> {
    ensure!(
      idx < self.logical_buffer_count,
      "Invalid buffer index {}",
      idx
    );
    Ok(idx * self.logical_buffer_size)
  }

  pub const fn buffer_count(&self) -> usize {
    self.logical_buffer_count
  }

  /// returns a buffer pointing tho the base of a logical buffer (incl. length field)
  fn get_checked_base_ptr_mut(&self) -> Result<BorrowedOneshotWritePtr<'_, u32>> {
    let buff_offset = self.buffer_offset(self.current_index)?;
    let backing_mem_len = self.backing_buffer.len() as usize;
    ensure!(
      buff_offset < backing_mem_len,
      "Offset too large: {}, compared to the (mut) buffers len {}",
      buff_offset,
      backing_mem_len
    );
    let base_mem = self.backing_buffer.borrow_ptr_mut()?;
    let buffer_start = ptr_add_nowrap_mut(*base_mem, buff_offset)?;
    ensure!(
      !buffer_start.is_null() && buffer_start >= *base_mem,
      "Buffer mut pointer is invalid: {:?}, offset: {}",
      buffer_start,
      buff_offset
    );
    {
      let test_value_len = ptr_add_nowrap(buffer_start, std::mem::size_of::<u32>())?;
      ensure!(
        !test_value_len.is_null()
          && test_value_len >= *base_mem
          && test_value_len < ptr_add_nowrap(*base_mem, backing_mem_len)?,
        "Buffer mut pointer is invalid (u32 test): {:?}, offset: {}",
        test_value_len,
        buff_offset
      );
    }
    Log::get("writerptr").trace(format!("Ptr {buffer_start:?}"));
    // safety: base_mem RefMut, above checks, T is u32
    Ok(unsafe { BorrowedOneshotWritePtr::at(base_mem, buff_offset)? })
  }

  /// returns Ok variant if base pointer + `buff_offset` are valid offset to a logical buffer
  fn get_checked_base_ptr(&self) -> Result<BorrowedReadBuffer<'_>> {
    let buffers: &ShmemHandle = &self.backing_buffer;
    let buff_offset = self.buffer_offset(self.current_index)?;
    ensure!(
      buff_offset < buffers.len() as usize,
      "Offset too large: {}, compared to the buffers len {}",
      buff_offset,
      buffers.len()
    );
    let base_mem = buffers.borrow_ptr()?;
    {
      // arithmetic operations on pointers that check we can do what follows after this block
      // the pointers here carry no/incorrect provenance and are thus unreferencable
      // even by the slice::from_raw_parts

      let test_value = ptr_add_nowrap(*base_mem, buff_offset)?;
      ensure!(
        !test_value.is_null() && test_value >= *base_mem,
        "Buffer const pointer is invalid: {:?}, offset: {}",
        test_value,
        buff_offset
      );
      let test_value_end = ptr_add_nowrap(test_value, self.logical_buffer_size)?;
      ensure!(
        !test_value_end.is_null()
          && (test_value_end as usize) - (test_value as usize)
            <= self.backing_buffer.len() as usize,
        "Buffer const pointer is invalid: {:?}, {:?}, offset: {}, backing size: {}",
        test_value_end,
        test_value,
        buff_offset,
        self.backing_buffer.len()
      );
    }
    // base_mem should carry no provenance as it originates from mmpaed memory

    // by the following, I hope to introduce provenance to the pointer (slices) that follow

    // this slice only says we can fit all previous buffer and the target buffer
    // SAFETY: via refcell borrow checking and checks done above
    let up_to_target_buff: &[u8] =
      unsafe { slice::from_raw_parts(*base_mem, buff_offset + self.logical_buffer_size) };
    let target_buffer = up_to_target_buff.split_at(buff_offset).1;
    Log::get("readptr").trace(format!("Ptr {:?}", (*base_mem).wrapping_add(buff_offset)));
    assert!(target_buffer.len() == self.logical_buffer_size);
    let buffer = BufferStartPtr::new(target_buffer)?.shift_init_data()?;

    Ok(BorrowedReadBuffer {
      _borrow_handle: base_mem,
      buffer,
    })
  }
}

fn cleanup_sems(prefix: &str) {
  let lg = Log::get("cleanup_sems");
  let FreeFullSemNames { free, full } = FreeFullSemNames::new(prefix, "capture", "base");
  let value_or_err = |v: &[u8]| -> Option<String> {
    match v
      .split_last()
      .ok_or_else(|| "Wrong format of data sem name".to_owned())
      .and_then(|v| String::from_utf8(v.1.to_vec()).map_err(|e| format!("Invalid string {e}")))
    {
      Ok(x) => Some(x),
      Err(e) => {
        lg.crit(e);
        None
      }
    }
  };
  let (Some(nonull_data), Some(nonull_ack)) =
    (value_or_err(META_SEM_DATA), value_or_err(META_SEM_ACK))
  else {
    return;
  };
  for name in &[free, full, nonull_data, nonull_ack] {
    lg.info(format!("Cleanup {name}"));
    let res = Semaphore::try_open(name, 0, O_CREAT.into(), None)
      .map_err(|e| format!("Cleanup {name}: {e}"))
      .and_then(|sem| {
        deinit_semaphore_single(sem).map_err(|e| format!("Cleanup of opened {name}: {e}"))
      });
    if let Err(e) = res {
      lg.crit(e);
    }
  }
}

pub fn cleanup(prefix: &str) -> Result<()> {
  cleanup_sems(prefix);
  cleanup_shared_mem(prefix)
}

fn deinit_semaphore_single(sem: Semaphore) -> Result<()> {
  match sem.try_close() {
    Ok(sem) => sem,
    Err((_, err)) => bail!(err),
  }
  .try_destroy()
  .map_err(|(_, s)| anyhow!(s))
}

pub fn deinit_semaphores(free_handle: Semaphore, full_handle: Semaphore) -> Result<()> {
  deinit_semaphore_single(free_handle)
    .map_err(|e| anyhow!("When closing free semaphore: {e}"))
    .and_then(|()| deinit_semaphore_single(full_handle))
    .map_err(|e| anyhow!("When closing full semaphore: {e}"))
}

fn get_shmem_name(prefix: &str) -> String {
  format!("{prefix}-capture-base-buffmem\x00")
}

fn init_shmem(prefix: &str, buff_count: u32, buff_len: u32) -> Result<ShmemHandle> {
  let buffs_tmp: String = get_shmem_name(prefix); // keep type annotation for safety
  // SAFETY: line above
  let buffscstr = unsafe { to_cstr(&buffs_tmp) };

  ShmemHandle::try_mmap(buffscstr, buff_count * buff_len)
}

fn cleanup_shared_mem(prefix: &str) -> Result<()> {
  let lg = Log::get("cleanup_shared_mem");
  let metadata_shm_name = String::from_utf8(META_MEM_NAME.to_vec())?;
  let metadata_shm_size_name = String::from_utf8(META_MEM_SIZE_NAME.to_vec())?;
  let buffs_shm_name: String = get_shmem_name(prefix); // keep type annotation for safety
  // SAFETY: line above
  for name in unsafe {
    [
      to_cstr(&metadata_shm_name),
      to_cstr(&buffs_shm_name),
      to_cstr(&metadata_shm_size_name),
    ]
  } {
    lg.info(format!("Cleanup {name:?}"));
    if let Err(e) = try_shm_unlink_fd(name) {
      lg.info(format!("Cleanup error: {name:?}: {e}"));
    }
  }
  let svr_sock_name = test_server_socket();
  lg.info(format!("Cleanup {svr_sock_name:?}"));
  let _ = std::fs::remove_file(svr_sock_name.clone())
    .inspect_err(|e| lg.info(format!("Cleanup error: {svr_sock_name}: {e}")));
  Ok(())
}

fn deinit_shmem(buffers_mem: ShmemHandle) -> Result<()> {
  buffers_mem
    .try_unmap()
    .map_err(|e| anyhow!("deinit_shmem failed: {e}"))
}

pub fn send_call_tracing_metadata(chnl: &mut MetadataPublisher, infra: InfraParams) -> Result<()> {
  send_metadata(
    chnl,
    ShmMeta {
      buff_count: infra.buff_count,
      buff_len: infra.buff_len,
      total_len: infra.buff_count * infra.buff_len,
      mode: 0,
      target_fnid: 0,
      target_modid: 0,
      forked: 0,
      test_count: 0,
      target_call_number: 0,
      test_timeout_seconds: 0,
      thread_count: 0,
      target_thread_lid: 0,
      checkpoint_dump_dir: [0; 512],
      checkpoint_id: 0,
      shell_job: 0,
    },
  )
}

pub fn send_arg_capture_metadata(chnl: &mut MetadataPublisher, infra: InfraParams) -> Result<()> {
  send_metadata(
    chnl,
    ShmMeta {
      buff_count: infra.buff_count,
      buff_len: infra.buff_len,
      total_len: infra.buff_count * infra.buff_len,
      mode: 1,
      target_fnid: 0,
      target_modid: 0,
      forked: 0,
      test_count: 0,
      target_call_number: 0,
      test_timeout_seconds: 0,
      thread_count: 0,
      target_thread_lid: 0,
      checkpoint_dump_dir: [0; 512],
      checkpoint_id: 0,
      shell_job: 0,
    },
  )
}

pub trait ToLlcapRaw {
  fn to_raw(&self) -> ffi::c_uint;
}

impl ToLlcapRaw for TestingMode {
  fn to_raw(&self) -> ffi::c_uint {
    match self {
      Self::Testing => MODE_TESTING,
      Self::MTCompatTesting => MODE_MT_TESTING,
      Self::CheckpointedTesting(on) => {
        if *on {
          MODE_CHECKPOINT_TESTING_DO_CHECKPOINT
        } else {
          MODE_CHECKPOINT_TESTING_NOCHECKPOINT
        }
      }
    }
  }
}

pub fn send_test_metadata(
  chnl: &mut MetadataPublisher,
  infra: InfraParams,
  test: &TestRegisryItem,
  checkpoint_path: Option<&str>,
) -> Result<()> {
  const MAX_CHARS: usize = (CRIU_CHECKPOINT_DIR_PATH_MAXLEN_WZERO - 1) as usize;
  let checkpoint_id = u64::from(test.call_index.0) * 1000 * 1000 * 1000
    + test.packet_index.0 * 1000 * 1000
    + u64::from(test.target_call_number());

  let checkpoint_path = checkpoint_path.unwrap_or("");
  ensure!(
    checkpoint_path.len() <= MAX_CHARS,
    "Path to CRIU dumps too long!"
  );

  let mut dump_dir: [i8; CRIU_CHECKPOINT_DIR_PATH_MAXLEN_WZERO as usize] = [0; 512];

  let v: Vec<u8> = checkpoint_path
    .as_bytes()
    .iter()
    .take(MAX_CHARS)
    .copied()
    .collect();

  assert!(v.len() <= MAX_CHARS);
  v.iter().enumerate().for_each(
    #[allow(clippy::cast_possible_wrap)]
    |(i, val)| {
      dump_dir[i] = *val as i8;
    },
  );

  send_metadata(
    chnl,
    ShmMeta {
      buff_count: infra.buff_count,
      buff_len: infra.buff_len,
      total_len: infra.buff_count * infra.buff_len,
      mode: test.mode.to_raw(),
      target_fnid: *test.uid.function_id,
      target_modid: *test.uid.module_id,
      forked: 0,
      test_count: match test.mode {
        TestingMode::Testing => test.test_count,
        // in compat testing, testcount is always 1 and is re-used
        // for the packet index
        TestingMode::MTCompatTesting | TestingMode::CheckpointedTesting(_) => {
          test.packet_index.0 as u32
        }
      },
      target_call_number: test.target_call_number(),
      test_timeout_seconds: test.test_timeout_s(),
      thread_count: test.thread_count,
      target_thread_lid: test.thread_lid.0 as u32,
      checkpoint_dump_dir: dump_dir,
      checkpoint_id,
      shell_job: 1,
    },
  )
}

// sends the communication/testing parameters to the hooklib
#[allow(clippy::large_types_passed_by_value)]
fn send_metadata(meta_pub: &mut MetadataPublisher, target_descriptor: ShmMeta) -> Result<()> {
  Log::get("send_metadata").info("Waiting for a cooperating program");
  meta_pub.publish(target_descriptor)
}

// do not derive clone/copy or define functions with similar semantics
pub struct MetadataPublisher {
  size_shm: ShmemHandle,
  shm: ShmemHandle,
  data_rdy_sem: Semaphore,
  data_ack_sem: Semaphore,
}

impl MetadataPublisher {
  // metadata is published via shared memory and 2 semaphores:
  // a "data_rdy" semaphore that signals that the metadata is ready to be read
  // a "data_ack" semaphore that indicates that the data has been read and can be rewritten/discarded

  fn mk_sems(rdy_sem_path: &str, ack_sem_path: &str) -> Result<(Semaphore, Semaphore)> {
    // initialize ready semaphore to zero as no data is ready
    let rdy = Semaphore::try_open_exclusive(rdy_sem_path, 0)?;
    // !! ack semaphore is initialized to ONE - this will be waited on in the first call of
    // Self::publish
    let ack = Semaphore::try_open_exclusive(ack_sem_path, 1)?;
    Ok((rdy, ack))
  }

  pub fn new(
    mem_path: &CStr,
    size_mem_path: &CStr,
    rdy_sem_path: &str,
    ack_sem_path: &str,
  ) -> Result<Self> {
    let (rdy, ack) = Self::mk_sems(rdy_sem_path, ack_sem_path)?;

    // share the size-to-be allocated
    let to_alloc = std::mem::size_of::<ShmMeta>() as u32;
    let size_shm = ShmemHandle::try_mmap(size_mem_path, 4)?;
    {
      let mem = size_shm.borrow_ptr_mut()?;
      unsafe { (*mem).cast::<u32>().write_unaligned(to_alloc) };
    }

    let shm = ShmemHandle::try_mmap(mem_path, to_alloc)?;
    Ok(Self {
      size_shm,
      shm,
      data_rdy_sem: rdy,
      data_ack_sem: ack,
    })
  }

  /// refreshes internals of the publisher so as to properly handle cases when
  /// a faulty test case times out or terminates
  ///
  /// Note that this especially applies in the MT compat mode
  pub fn re_new(&mut self) -> Result<()> {
    let rdy_sem_path = self.data_rdy_sem.cname().clone();
    let ack_sem_path = self.data_ack_sem.cname().clone();

    // hack because try_destroy takes ownership
    let mut tmp_rdy = Semaphore::Closed {
      cname: String::new(),
    };
    let mut tmp_ack = Semaphore::Closed {
      cname: String::new(),
    };
    std::mem::swap(&mut tmp_rdy, &mut self.data_rdy_sem);
    std::mem::swap(&mut tmp_ack, &mut self.data_ack_sem);
    tmp_rdy.try_destroy().map_err(|e| anyhow!(e.1))?;
    tmp_ack.try_destroy().map_err(|e| anyhow!(e.1))?;

    let (rdy, ack) = Self::mk_sems(&rdy_sem_path, &ack_sem_path)?;
    self.data_rdy_sem = rdy;
    self.data_ack_sem = ack;
    Ok(())
  }

  #[allow(clippy::large_types_passed_by_value)]
  pub fn publish(&mut self, meta: ShmMeta) -> Result<()> {
    if !self.data_ack_sem.try_wait(Some(5))? {
      bail!("Publish timed out");
    }

    {
      Log::get("MetadataPublisher::publish").trace(format!(
        "Threads: {}, Tests: {}",
        meta.thread_count, meta.test_count
      ));

      let mem = self.shm.borrow_ptr_mut()?;
      unsafe {
        // SAFETY: allocation of self.shm (mainly the size)
        // unaligned write just to be sure
        (*mem).cast::<ShmMeta>().write_unaligned(meta);
      }
    }
    self.data_rdy_sem.try_post()
  }

  pub fn deinit(self) -> Result<()> {
    self.size_shm.try_unmap()?;
    self.shm.try_unmap()?;
    self.data_ack_sem.try_destroy().map_err(|e| anyhow!(e.1))?;
    self.data_rdy_sem.try_destroy().map_err(|e| anyhow!(e.1))?;
    Ok(())
  }
}

// SAFETY: we do not give access to shared memory handle
// and semaphores to the outside, furthermore, no suspension points
// are present in associated functions & named
// semaphores should be sharable between threads
unsafe impl Send for MetadataPublisher {}
// note: the type may never become Sync (deinit is not compatible) and
// publish was designed around synchronization of 2 processes, so
// multithreaded contention inside the function was not really considered
// (the type is Arc-Mutexed anyway)

trait CaptureLoopState: Default {
  /// returns the number of empty buffers received
  fn get_end_message_count(&self) -> usize;

  /// resets the counter of empty buffers to zero
  fn reset_end_message_count(&mut self);
}

trait CaptureLoop: Sized {
  type State: CaptureLoopState;
  /// updates the incoming state according to the buffer contents, consumes all the buffer contents
  ///
  /// the function is also responsible for incrementing a State-internal end-message counter
  /// which is used to detect the capture loop's termination sequence
  /// (see the implementation of [`Self::State::get_end_message_count`] for reference
  fn update_from_buffer(
    &mut self,
    state: Self::State,
    buffer: BorrowedReadBuffer<'_>,
    modules: &ExtModuleMap,
  ) -> Result<Self::State>;

  /// performs data processing on intermediate data that may be present in the current state
  ///
  /// returns state that may be used for another [`Self::update_from_buffer`] iteration  
  fn process_state(&mut self, state: Self::State) -> Result<Self::State>;

  // runs the capture until the terminating condition is reached
  // (received #buffers empty messages)
  fn run(mut self, infra: &mut TracingInfra, modules: &ExtModuleMap) -> Result<Self> {
    let lg = Log::get("CaptureLoop::run");
    let mut state = Self::State::default();
    // bookkeeping variable to be able to call reset_end_message_count
    let mut last_end_msg_count = 0;
    loop {
      lg.trace(format!("Waiting for free buff: {last_end_msg_count}"));
      let base_ptr: BorrowedReadBuffer<'_> = infra.wait_for_full_buffer()?;
      lg.trace("Received buffer");
      let st = self.update_from_buffer(state, base_ptr, modules)?;
      infra.finish_buffer()?;

      let mut st: <Self as CaptureLoop>::State = self.process_state(st)?;
      if st.get_end_message_count() == infra.buffer_count() {
        return Ok(self);
      } else if last_end_msg_count == st.get_end_message_count() {
        // only count consecutive messages
        st.reset_end_message_count();
        last_end_msg_count = 0;
      } else {
        last_end_msg_count = st.get_end_message_count();
      }
      state = st;
    }
  }
}
