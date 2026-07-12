use std::{ffi::CStr, io::Error};

use libc::shm_unlink;

use anyhow::{Result, ensure};

/// attempts to unlink a shared-memory file descriptor
/// which was opened under the `name`
pub fn try_shm_unlink_fd(name: &CStr) -> Result<()> {
  ensure!(
    // SAFETY: &CStr type, syscall docs
    unsafe { shm_unlink(name.as_ptr()) } != -1,
    "Failed to unlink FD {}: {}",
    name.to_string_lossy(),
    Error::last_os_error()
  );
  Ok(())
}
