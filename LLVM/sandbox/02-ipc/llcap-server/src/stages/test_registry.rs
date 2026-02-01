use std::{collections::HashMap, time::Duration};

use crate::{
  modmap::NumFunUid,
  stages::testing::{CallIndexT, PacketIndexT, ThreadLidT},
};

#[derive(Debug, Clone, Copy)]
pub enum TestingMode {
  #[allow(dead_code)]
  Testing,
  MTCompatTesting,
}

#[derive(Debug, Clone)]
pub struct TestRegisryItem {
  pub uid: NumFunUid,
  pub call_index: CallIndexT,
  pub packet_index: PacketIndexT,
  pub thread_lid: ThreadLidT,
  pub thread_count: u32,
  pub test_count: u32,
  pub timeout_test: Duration,
  pub timeout_process: Option<Duration>,
  pub mode: TestingMode,
}

impl TestRegisryItem {
  pub fn target_call_number(&self) -> u32 {
    self.call_index.0 + 1
  }

  pub fn test_timeout_s(&self) -> u16 {
    self.timeout_test.as_secs() as u16
  }

  pub fn terminate_child(&self) -> bool {
    !matches!(self.mode, TestingMode::MTCompatTesting)
  }
}

#[derive(PartialEq, Eq, Hash, Debug, Clone, Copy)]
pub struct TestID(u64);

pub struct TestRegistry {
  auto_increment: TestID,
  map: HashMap<TestID, TestRegisryItem>,
  cmd_map: HashMap<TestID, usize>,
  cmds: Vec<Vec<String>>,
}

impl TestRegistry {
  pub fn new() -> Self {
    Self {
      auto_increment: TestID(0),
      map: HashMap::new(),
      cmd_map: HashMap::new(),
      cmds: Vec::new(),
    }
  }
  pub fn get_test_params(&self, id: TestID) -> Option<(&TestRegisryItem, &[String])> {
    self.map.get(&id).zip(self.get_test_commandline(id))
  }

  pub fn add_new_test(&mut self, item: TestRegisryItem, cmdline: &[String]) -> TestID {
    self.auto_increment.0 += 1;
    let new_id = TestID(self.auto_increment.0);
    self.map.insert(new_id, item);
    let index = self
      .cmds
      .iter()
      .enumerate()
      .find(|(_, v)| v.len() == cmdline.len() && v.iter().zip(cmdline).all(|(v1, v2)| v1 == v2));
    let index = match index {
      Some(idx) => idx.0,
      None => {
        self.cmds.push(Vec::from(cmdline));
        self.cmds.len() - 1
      }
    };
    self.cmd_map.insert(new_id, index);
    new_id
  }

  pub fn get_test_commandline(&self, id: TestID) -> Option<&[String]> {
    self.cmd_map.get(&id).map(|v| &self.cmds[*v]).map(|v| &**v)
  }
}
