pub mod fd;
pub mod sem;
pub mod shared_memory;
pub mod wrappers;

use libc::{WNOHANG, waitpid};
use std::{io, os::unix::process::ExitStatusExt, process::ExitStatus, time::Duration};
use tokio::time::Instant;

/// Returns Ok(None) if timeout elapsed, Ok(ExitStatus) if child exited within timeout.
/// Err otherwise.
pub fn get_child_exit_code_with_timeout(
  pid: libc::pid_t,
  timeout: Duration,
) -> Result<Option<ExitStatus>, io::Error> {
  // Google's Gemini Flash improved this code with edge-case detection
  let start = Instant::now();
  // A small polling interval prevents 100% CPU usage while keeping responsiveness high.
  let pause = Duration::from_millis(50);

  loop {
    let mut status: libc::c_int = 0;
    let res = unsafe { waitpid(pid, &mut status, WNOHANG) };

    if res == -1 {
      let err = io::Error::last_os_error();
      if err.kind() == io::ErrorKind::Interrupted {
        continue;
      }
      return Err(err);
    }

    if res == 0 {
      let elapsed = start.elapsed();
      if elapsed >= timeout {
        return Ok(None);
      }
      let remains = timeout - elapsed;
      std::thread::sleep(pause.min(remains));
      continue;
    }

    if res == pid {
      return Ok(Some(ExitStatus::from_raw(status)));
    }

    return Err(io::Error::other("Unexpected PID returned from waitpid"));
  }
}
