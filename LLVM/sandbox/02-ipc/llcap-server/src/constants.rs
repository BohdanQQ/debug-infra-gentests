pub struct Constants {}

impl Constants {
  pub const fn min_buff_size() -> u32 {
    16
  }
  pub const fn parse_fnid_radix() -> u32 {
    10
  }

  pub const fn default_fd_prefix() -> &'static str {
    "/llcap"
  }
  pub const fn default_buff_count_str() -> &'static str {
    "10"
  }

  pub const fn default_buff_size_bytes_str() -> &'static str {
    "4194304" // 4MiB 
  }

  pub const fn default_trace_out_path() -> &'static str {
    "./trace.out"
  }

  pub const fn default_capture_out_path() -> &'static str {
    "./capture-out/"
  }

  pub const fn default_selected_functions_path() -> &'static str {
    "./selected-fns.bin"
  }
}
