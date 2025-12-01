use std::{path::PathBuf, sync::atomic::AtomicU8};

use tokio::{fs::File, io::AsyncWriteExt};

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum LogLevel {
  Critical,
  Warn,
  Info,
  Trace,
}

impl From<u8> for LogLevel {
  fn from(value: u8) -> Self {
    match value {
      0 => LogLevel::Critical,
      1 => LogLevel::Warn,
      2 => LogLevel::Info,
      _ => LogLevel::Trace,
    }
  }
}

impl From<LogLevel> for u8 {
  fn from(value: LogLevel) -> Self {
    match value {
      LogLevel::Critical => 0,
      LogLevel::Warn => 1,
      LogLevel::Info => 2,
      LogLevel::Trace => 3,
    }
  }
}

#[derive(Clone, Copy)]
pub struct Log {
  level: LogLevel,
}

// bad, wrong, terrible but I don't think extensive customizable logging is needed
static LOG_CRIT: Log = Log {
  level: LogLevel::Critical,
};
static LOG_WARN: Log = Log {
  level: LogLevel::Warn,
};
static LOG_INFO: Log = Log {
  level: LogLevel::Info,
};
static LOG_TRACE: Log = Log {
  level: LogLevel::Trace,
};

static LOG_LEVEL: AtomicU8 = AtomicU8::new(0);

pub struct ResultLogger {
  result_log: LogStrategy,
}
impl ResultLogger {
  pub fn new(result_log: LogStrategy) -> Self {
    Self { result_log }
  }

  pub fn result<T: IntoLogString>(
    &mut self,
    msg: &T,
  ) -> impl Future<Output = Result<(), anyhow::Error>> {
    self.result_log.put_loggable(msg)
  }

  pub async fn finish(self) -> anyhow::Result<()> {
    self.result_log.finish().await?;
    Ok(())
  }
}

pub struct Logger {
  name: String,
  inner_log: &'static Log,
}

impl Logger {
  pub fn new(name: &str) -> Self {
    let log = match LogLevel::from(LOG_LEVEL.load(std::sync::atomic::Ordering::Relaxed)) {
      LogLevel::Critical => &LOG_CRIT,
      LogLevel::Warn => &LOG_WARN,
      LogLevel::Info => &LOG_INFO,
      LogLevel::Trace => &LOG_TRACE,
    };
    Self {
      name: name.to_string(),
      inner_log: log,
    }
  }

  fn formatted(&self, msg: &str) -> String {
    format!("[{}] {}", self.name, msg)
  }

  pub fn crit<T: AsRef<str>>(&self, msg: T) {
    self
      .inner_log
      .log(LogLevel::Critical, &self.formatted(msg.as_ref()));
  }
  pub fn warn<T: AsRef<str>>(&self, msg: T) {
    self
      .inner_log
      .log(LogLevel::Warn, &self.formatted(msg.as_ref()));
  }
  pub fn info<T: AsRef<str>>(&self, msg: T) {
    self
      .inner_log
      .log(LogLevel::Info, &self.formatted(msg.as_ref()));
  }
  pub fn trace<T: AsRef<str>>(&self, msg: T) {
    self
      .inner_log
      .log(LogLevel::Trace, &self.formatted(msg.as_ref()));
  }

  // an unconditional log
  pub fn progress<T: AsRef<str>>(&self, msg: T) {
    self.inner_log.log_progress(&self.formatted(msg.as_ref()))
  }
}

impl Log {
  pub fn set_verbosity(verbosity: u8) -> u8 {
    LOG_LEVEL.swap(verbosity, std::sync::atomic::Ordering::Relaxed)
  }

  pub fn get(name: &str) -> Logger {
    Logger::new(name)
  }

  pub async fn result_logger(mut strategy: LogStrategy) -> anyhow::Result<ResultLogger> {
    strategy.put_header().await?;
    Ok(ResultLogger::new(strategy))
  }

  fn log_level_preamble(lvl: LogLevel) -> &'static str {
    match lvl {
      LogLevel::Critical => "C |",
      LogLevel::Warn => "W |",
      LogLevel::Info => "I |",
      LogLevel::Trace => "T |",
    }
  }

  fn log(&self, lvl: LogLevel, msg: &str) {
    if u8::from(lvl) > u8::from(self.level) {
      return;
    }
    eprintln!("{} {}", Log::log_level_preamble(lvl), msg);
  }

  fn log_progress(&self, msg: &str) {
    println!("P | {msg}");
  }
}

pub enum LogStrategy {
  StdOut,
  PlainText(File),
  Json(File, bool),
}

impl LogStrategy {
  pub async fn create(file: &PathBuf) -> anyhow::Result<Self> {
    let ext = file.extension();
    match ext {
      Some(e) if (e == "json") => Self::json(file).await,
      Some(e) if (e == "txt" || e == "out" || e == "log") => Self::plain_text(file).await,
      _ => Ok(Self::StdOut),
    }
  }

  async fn json(path: &PathBuf) -> anyhow::Result<Self> {
    let f = File::create(path).await?;
    Ok(Self::Json(f, true))
  }

  async fn plain_text(path: &PathBuf) -> anyhow::Result<Self> {
    let f = File::create(path).await?;
    Ok(Self::PlainText(f))
  }

  pub async fn put_header(&mut self) -> anyhow::Result<()> {
    self
      .put_str_format(
        "{ \"results\":\n[",
        "Module ID | Function ID |  Call  | Packet | Result",
      )
      .await?;
    Ok(())
  }

  pub async fn finish(mut self) -> anyhow::Result<()> {
    self.put_str_format("]\n}", "").await
  }

  async fn put_str_format(&mut self, json_str: &str, stdout_str: &str) -> anyhow::Result<()> {
    match self {
      Self::StdOut => {
        if !stdout_str.is_empty() {
          self.put_str(stdout_str).await
        } else {
          Ok(())
        }
      }
      Self::PlainText(_) => self.put_str(stdout_str).await,
      Self::Json(_, _) => self.put_str(json_str).await,
    }?;
    Ok(())
  }

  async fn put_str(&mut self, s: &str) -> anyhow::Result<()> {
    match self {
      Self::StdOut => println!("{s}"),
      Self::Json(file, _) | Self::PlainText(file) => {
        file.write_all(s.as_bytes()).await?;
      }
    };
    Ok(())
  }

  pub async fn put_loggable<T: IntoLogString>(&mut self, loggable: &T) -> anyhow::Result<()> {
    let str = loggable.get_log_string(self);
    self.put_str(&str).await?;
    if let Self::Json(_, first) = self {
      *first = false
    }
    Ok(())
  }
}

pub trait IntoLogString {
  fn get_log_string(&self, log_strat: &LogStrategy) -> String;
}
