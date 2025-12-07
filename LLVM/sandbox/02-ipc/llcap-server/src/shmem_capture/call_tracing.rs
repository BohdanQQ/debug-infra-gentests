use std::collections::HashMap;

use crate::{
  log::Log,
  modmap::{ExtModuleMap, IntegralFnId, IntegralModId, NumFunUid},
  shmem_capture::{BorrowedReadBuffer, CaptureLoop, CaptureLoopState, ReadOnlyBufferPtr},
  stages::call_tracing::Message,
};

use anyhow::{Result, anyhow, ensure};

use super::TracingInfra;

#[derive(Default)]
struct CallTraceMessageState {
  // we are guaranteed we can always read 4-byte sections
  // so we are in the state, when we either have the module ID or not
  // if not, we will read the module ID
  // if yes, we will read the function ID and finish (store result in `messages`)
  mod_id_wip: Option<IntegralModId>,
  messages: Vec<Message>,
  pub end_message_counter: usize,
}

impl CallTraceMessageState {
  pub fn extract_messages(&mut self) -> Vec<Message> {
    let mut msgs = vec![];
    std::mem::swap(&mut msgs, &mut self.messages);
    msgs
  }

  pub fn add_message(&mut self, msg: Message) {
    self.messages.push(msg);
  }
}

// obtains the "next" module id to process
fn receive_module_id(
  raw_buff: &mut ReadOnlyBufferPtr,
  state: &mut CallTraceMessageState,
) -> Result<IntegralModId> {
  Ok(if let Some(mod_id) = state.mod_id_wip {
    // module id from a previous buffer -> skip readnig for module id and instead read function id corresponding to the module id from a previous bufer
    state.mod_id_wip = None;
    mod_id
  } else {
    ensure!(
      std::mem::size_of::<u32>() == IntegralModId::byte_size(),
      "Sanity check on module ID size"
    );
    let mod_id: u32 = raw_buff
      .unaligned_shift_num_read()
      .map_err(|e| anyhow::anyhow!("module ID reception error: {e}"))?;
    IntegralModId(mod_id)
  })
}

pub fn perform_call_tracing(
  infra: &mut TracingInfra,
  modules: &ExtModuleMap,
) -> Result<HashMap<NumFunUid, u64>> {
  let result = CallTracing {
    recorded_frequencies: HashMap::new(),
  }
  .run(infra, modules)?;
  Ok(result.recorded_frequencies)
}

struct CallTracing {
  recorded_frequencies: HashMap<NumFunUid, u64>,
}

impl CaptureLoopState for CallTraceMessageState {
  fn get_end_message_count(&self) -> usize {
    self.end_message_counter
  }

  fn reset_end_message_count(&mut self) {
    self.end_message_counter = 0;
  }
}

impl CaptureLoop for CallTracing {
  type State = CallTraceMessageState;

  fn update_from_buffer<'b>(
    &mut self,
    mut state: Self::State,
    mut buffer: BorrowedReadBuffer<'b>,
    modules: &ExtModuleMap,
  ) -> Result<Self::State> {
    assert!(
      std::mem::size_of::<u32>() == IntegralFnId::byte_size(),
      "Sanity check on function ID size"
    );
    let lg = Log::get("update_from_buffer");
    let buff = &mut buffer.buffer;
    if buff.empty() {
      ensure!(
        state.mod_id_wip.is_none(),
        format!(
          "Comms corruption - partial state with empty message following it! Module id {}",
          *state.mod_id_wip.unwrap()
        )
      );
      lg.trace("End msg");
      state.end_message_counter += 1;
      return Ok(state);
    }

    while !buff.empty() {
      let mod_id = receive_module_id(buff, &mut state)?;
      ensure!(
        modules.get_module_string_id(mod_id).is_some(),
        "Unknown module ID: {}",
        mod_id.hex_string()
      );

      if buff.empty() {
        lg.trace(format!("Partial message with {:?}", mod_id.hex_string()));
        state.mod_id_wip = Some(mod_id);
        return Ok(state);
      }
      // during call tracing, only moduleID-functionID messages are sent to us
      let fn_id: u32 = buff
        .unaligned_shift_num_read()
        .map_err(|e| anyhow!("funciton id reception error: {e}"))?;
      lg.trace(format!("M {:02X}", *mod_id));
      lg.trace(format!("F {fn_id:02X}"));

      state.add_message(Message(NumFunUid::new(IntegralFnId(fn_id), mod_id)));
    }
    Ok(state)
  }

  fn process_state(&mut self, mut state: Self::State) -> Result<Self::State> {
    let messages = state.extract_messages();
    for m in messages {
      self
        .recorded_frequencies
        .entry(m.0)
        .and_modify(|v| *v += 1)
        .or_insert(1);
    }
    Ok(state)
  }
}
