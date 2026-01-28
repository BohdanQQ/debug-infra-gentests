use std::{
  cell::{Ref, RefCell, RefMut},
  ffi::{CStr, c_void},
  io::Error,
  marker::PhantomData,
};

use anyhow::{Result, anyhow, ensure};
use libc::{
  MAP_FAILED, MAP_SHARED_VALIDATE, O_CREAT, O_EXCL, O_RDWR, PROT_READ, PROT_WRITE, ftruncate, mmap,
  munmap, shm_open,
};

use crate::log::Log;

use super::{
  fd::try_shm_unlink_fd,
  wrappers::{PERMS_PERMISSIVE, to_cstr},
};

#[derive(Debug)] // do not derive Clone or Copy!
pub struct ShmemHandle {
  /// this field is the centerpiece of (shared xor mutable) enforcement, while not strictly
  /// necessary (ensuring the exclusion via architectrue), it should detect gross misuse of shared
  /// memory on the side of llcap-server (I hope...)
  ///
  /// Any borrow through this field shall respect the *constness of the pointer returned
  /// (that is, it should not happen that an immutable borrow results in a *mut u8 created
  /// somewhere down the call stack)
  underlying_memory: RefCell<*mut u8>,
  /// number of bytes valid, starting from underlying_memory and spannign the entire mapped length
  len: u32,
  _fd: i32,
  /// null-char-terminated string
  cname: String,
  marker: PhantomData<[u8]>, // this type manages a shared memory buffer, it should act like it owns it
}

impl ShmemHandle {
  // safety: mem_ptr shall not be used again
  unsafe fn new(mem_ptr: *mut c_void, len: u32, fd: i32, name: String) -> Self {
    assert!(!mem_ptr.is_null());
    assert!(mem_ptr != MAP_FAILED);
    assert!(fd != -1);
    Self {
      underlying_memory: RefCell::new(mem_ptr as *mut u8),
      len,
      _fd: fd,
      cname: format!("{name}\x00"),
      marker: PhantomData,
    }
  }

  /// number of bytes allocated overall
  pub fn len(&self) -> u32 {
    self.len
  }

  pub fn borrow_ptr_mut(&mut self) -> Result<RefMut<'_, *mut u8>> {
    let borrow = self.underlying_memory.try_borrow_mut()?;
    Ok(borrow)
  }

  pub fn borrow_ptr(&self) -> Result<Ref<'_, *const u8>> {
    let borrow = self.underlying_memory.try_borrow()?;
    // SAFETY: we obtained an immutable borrow -> we can interpret the pointer as a pointer to
    // immutable data, regarding the transmute need - I honestly could not come up with why for
    // *mut u8 -> *const u8 cannot be writen via "as", at the same time I could not find a
    // reason why the transmute could be invalid (given we obtained the immutable borrow)
    let borrow = Ref::map(borrow, |v| unsafe {
      std::mem::transmute::<&*mut u8, &*const u8>(v)
    });
    Ok(borrow)
  }

  pub fn try_mmap(path: &CStr, len: u32) -> Result<Self> {
    Log::get("try_mmap").trace(format!("Mapping memory @[{len}] {path:?}"));

    let unlinking_handler = |error_string: String| {
      let unlink_res =
        try_shm_unlink_fd(path).map_err(|e| anyhow!("{e} - message: {error_string}"));

      match unlink_res {
        Ok(_) => anyhow!(error_string),
        Err(s) => anyhow!(s),
      }
    };
    // SAFETY: &CStr type, syscall docs
    let fd = unsafe { shm_open(path.as_ptr(), O_CREAT | O_EXCL | O_RDWR, PERMS_PERMISSIVE) };
    ensure!(
      fd != -1,
      "Failed to open FD for shmem {}: {}",
      path.to_string_lossy(),
      Error::last_os_error()
    );

    // SAFETY: documentation of the syscall, fd obtained beforehand
    let truncation = unsafe { ftruncate(fd, len as i64) };
    ensure!(
      truncation != -1,
      unlinking_handler(format!(
        "Failed to truncate FD for shmem {}, len: {}: {}",
        path.to_string_lossy(),
        len,
        Error::last_os_error()
      ))
    );

    // SAFETY: documentation of the syscall, fd obtained beforehand, len passed fntruncate above
    let mmap_res = unsafe {
      mmap(
        std::ptr::null_mut(),
        len as usize,
        PROT_READ | PROT_WRITE,
        MAP_SHARED_VALIDATE,
        fd,
        0,
      )
    };
    ensure!(
      mmap_res != MAP_FAILED,
      unlinking_handler(format!(
        "Failed to mmap {}, len: {}: {}",
        path.to_string_lossy(),
        len,
        Error::last_os_error()
      ))
    );

    // SAFETY: mmap_res is not used elsewhere
    Ok(unsafe { Self::new(mmap_res, len, fd, path.to_string_lossy().to_string()) })
  }

  pub fn try_unmap(mut self) -> Result<()> {
    // SAFETY: syscall docs, ShmHandle's invariant
    let len = self.len as usize;
    let unmap_res = unsafe { munmap(*self.borrow_ptr_mut()? as *mut c_void, len) };
    ensure!(
      unmap_res == 0,
      format!(
        "Failed to unmap memory of len {}: {}",
        self.len,
        Error::last_os_error()
      )
    );
    // SAFETY: cname from type's invariant
    let cstr_name = unsafe { to_cstr(&self.cname) };
    try_shm_unlink_fd(cstr_name)
  }
}
