use anyhow::{Result, ensure};

/// adds an offset to pointer, prohibiting overflow
pub fn ptr_add_nowrap(ptr: *const u8, sz: usize) -> Result<*const u8> {
  let mb_wrapped = ptr.wrapping_add(sz);
  ensure!(mb_wrapped >= ptr, "Wraparound for ptr {ptr:?} len {sz}");
  Ok(mb_wrapped)
}

/// adds an offset to pointer, prohibiting overflow
pub fn ptr_add_nowrap_mut(ptr: *mut u8, sz: usize) -> Result<*mut u8> {
  let mb_wrapped = ptr.wrapping_add(sz);
  ensure!(mb_wrapped >= ptr, "Wraparound for mut ptr {ptr:?} len {sz}");
  Ok(mb_wrapped)
}

// bunch of sanity-check tests, nothing complex to see here
#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  pub fn nowrap_ok_case() {
    let data: u32 = 12312;
    let ptr: *const u32 = std::ptr::from_ref(&data);
    assert!(ptr_add_nowrap(ptr as *const u8, 1).is_ok())
  }

  #[test]
  pub fn nowrap_simple() {
    let ptr: *const u8 = 0xffffffffffffffff as *const u8;
    assert!(ptr_add_nowrap(ptr, 1).is_err())
  }

  #[test]
  pub fn nowrap_larger() {
    let sz: usize = 4;
    let ptr: *const u8 = (0xffffffffffffffff - sz) as *const u8;
    assert!(ptr_add_nowrap(ptr, sz + 1).is_err())
  }
}
