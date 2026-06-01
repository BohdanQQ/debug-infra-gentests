use anyhow::{Result, anyhow};

use crate::log::Log;

#[derive(Debug)]
pub enum ReadProgress {
  /// reading of a value is done
  Done {
    /// result to be saved
    payload: Vec<u8>,
    /// nr of bytes consumed from input
    consumed_bytes: usize,
  },
  /// all bytes from input consumed, result not complete yet, send another buffer
  NotYet,
  /// buffer left untouched, you should call `reset()`
  Nop,
}

#[derive(Debug, Copy, Clone)]
pub enum ArgSizeTypeRef {
  Empty,
  Custom,
}

impl TryFrom<u16> for ArgSizeTypeRef {
  type Error = anyhow::Error;

  fn try_from(id: u16) -> Result<Self, Self::Error> {
    // FIXME: temporary workaround for protobuf compatibility!
    match id {
      0 => Ok(Self::Empty),
      1..=1027 => Ok(Self::Custom),
      _ => Err(anyhow!("Unsupported argument size type: {id}")),
    }
  }
}

// interface for argument readers
pub trait SizeTypeReader {
  /// resets the reader, acts as a no-op if reader is not finished
  ///
  /// returns true if reader was reset
  fn read_reset(&mut self) -> bool;
  /// consumes bytes from data
  /// may consume any number of bytes (up to length), refer to `ReadProgress`
  /// for return value information
  fn read(&mut self, data: &[u8]) -> Result<ReadProgress>;
  /// indicates that reader has finished reading, data is ready
  fn done(&self) -> bool;
}

/// Returns start + number of bytes consumed
fn take_num_into_slice(n: usize, start: usize, out: &mut [u8; 8], inp: &[u8]) -> usize {
  let mut offs = start;
  for i in inp.iter().take(n) {
    out[offs] = *i;
    offs += 1;
  }

  offs
}

// the size of the mandatory "length" field of the LLSZ_CUSTOM types
const CUSTOM_TYPE_SIZE_SPEC_SIZE: usize = 8;
#[derive(Debug)]
pub enum CustomTypeReader {
  Start,
  // in the middle of reading the 8-byte size
  ReadingTgtSize {
    idx: u8,
    bytes: [u8; CUSTOM_TYPE_SIZE_SPEC_SIZE],
  },
  // finished reading the size, now reading the payload (of length target_size)
  Reading {
    target_size: u64,
    payload: Vec<u8>,
  },
  Finished,
}
impl CustomTypeReader {
  pub const fn new() -> Self {
    Self::Start
  }
}

impl SizeTypeReader for CustomTypeReader {
  fn read_reset(&mut self) -> bool {
    if !self.done() {
      return false;
    }
    *self = Self::Start;
    true
  }

  fn read(&mut self, data: &[u8]) -> Result<ReadProgress> {
    let (newself, result) = match self {
      Self::Start => {
        let mut tgt_sz_buff = [0u8; 8];
        let idx = take_num_into_slice(8, 0, &mut tgt_sz_buff, data);
        if idx == tgt_sz_buff.len() && tgt_sz_buff.len() == data.len() {
          (
            Some(Self::Reading {
              target_size: u64::from_le_bytes(tgt_sz_buff),
              payload: vec![],
            }),
            ReadProgress::NotYet,
          )
        } else if idx == tgt_sz_buff.len() && tgt_sz_buff.len() < data.len() {
          let mut payload = vec![];
          perform_reading_stage(
            data,
            idx,
            u64::from_le_bytes(tgt_sz_buff),
            &mut payload,
            tgt_sz_buff.len(),
          )
        } else {
          (
            Some(Self::ReadingTgtSize {
              idx: idx as u8,
              bytes: tgt_sz_buff,
            }),
            ReadProgress::NotYet,
          )
        }
      }
      Self::ReadingTgtSize { idx, bytes } => {
        let uidx = *idx as usize;
        let offs = take_num_into_slice(8 - uidx, uidx, bytes, data);
        if offs == bytes.len() {
          (
            Some(Self::Reading {
              target_size: u64::from_le_bytes(*bytes),
              payload: vec![],
            }),
            ReadProgress::NotYet,
          )
        } else {
          *idx = offs as u8;
          (None, ReadProgress::NotYet)
        }
      }
      Self::Reading {
        target_size,
        payload,
      } => perform_reading_stage(data, 0, *target_size, payload, 0),
      Self::Finished => (None, ReadProgress::Nop),
    };
    if let Some(newself) = newself {
      *self = newself;
    }
    Ok(result)
  }

  fn done(&self) -> bool {
    matches!(self, Self::Finished)
  }
}

fn perform_reading_stage(
  data: &[u8],
  offset: usize,
  target_size: u64,
  payload: &mut Vec<u8>,
  previous_read: usize,
) -> (Option<CustomTypeReader>, ReadProgress) {
  let lg = Log::get("perform_reading_stage");
  lg.trace(format!("Reading up to {target_size}"));
  let to_read = target_size as usize - payload.len();
  lg.trace(format!("Reading {to_read}"));
  for b in data.iter().skip(offset).take(to_read) {
    payload.push(*b);
  }

  if to_read == 0 || to_read <= data.len() - offset {
    let mut exhg = vec![];
    lg.trace("Done - consumed ");
    std::mem::swap(&mut exhg, payload);
    (
      Some(CustomTypeReader::Finished),
      ReadProgress::Done {
        payload: exhg,
        consumed_bytes: to_read + previous_read,
      },
    )
  } else {
    lg.trace("Consumed - might need more data");
    let mut swp = vec![];
    std::mem::swap(&mut swp, payload);
    (
      Some(CustomTypeReader::Reading {
        target_size,
        payload: swp,
      }),
      ReadProgress::NotYet,
    )
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  // various tests checking (among other things) that the readers output
  // the correct state when presented with differently chunked data

  #[test]
  fn cus_r_read_init_not_done() {
    let reader = CustomTypeReader::new();
    assert!(!reader.done());
  }
  #[test]
  fn cus_r_read_zero_size() {
    let mut reader = CustomTypeReader::new();
    let res = reader.read(&(0u8).to_le_bytes());
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
  }

  fn make_packet(data: &[u8]) -> Vec<u8> {
    let mut res: Vec<u8> = vec![];
    res.append(&mut data.len().to_le_bytes().to_vec());
    res.append(&mut data.to_vec());
    res
  }

  #[test]
  fn cus_r_read_size_less() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let (len, _) = pkt.split_at(8);
    let (len_less, _) = len.split_at(4);
    let res = reader.read(len_less);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
    assert!(matches!(
      reader,
      CustomTypeReader::ReadingTgtSize { idx: _, bytes: _ }
    ));
  }

  fn assert_reader_finished(
    res: Result<ReadProgress>,
    reader: CustomTypeReader,
    expected_parsed: u32,
    expected_consumed: usize,
  ) {
    assert!(
      matches!(res, Ok(ReadProgress::Done { payload, consumed_bytes }) if payload.len() == 4 && u32::from_le_bytes([payload[0], payload[1], payload[2], payload[3]]) == expected_parsed && consumed_bytes == expected_consumed)
    );
    assert!(matches!(reader, CustomTypeReader::Finished));
  }
  #[test]
  fn cus_r_read_size_exact_w_data_exact() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let (len, data) = pkt.split_at(8);
    let res = reader.read(len);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));

    let res = reader.read(data);
    assert_reader_finished(res, reader, num_data, data.len());
  }

  #[test]
  fn cus_r_read_size_less_exact_w_data_exact() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let (len, data) = pkt.split_at(8);
    let (len_less, len_ex) = len.split_at(4);
    let res = reader.read(len_less);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
    assert!(matches!(
      reader,
      CustomTypeReader::ReadingTgtSize { idx: _, bytes: _ }
    ));

    let res = reader.read(len_ex);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
    assert!(matches!(
      reader,
      CustomTypeReader::Reading {
        target_size: _,
        payload: _
      }
    ));

    let res = reader.read(data);
    assert_reader_finished(res, reader, num_data, data.len());
  }

  #[test]
  fn cus_r_read_size_exact_w_data_less() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let (len, data) = pkt.split_at(8);
    let res = reader.read(len);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));

    let (data_less, _) = data.split_at(1);
    let res = reader.read(data_less);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
    assert!(matches!(
      reader,
      CustomTypeReader::Reading {
        target_size: _,
        payload: _
      }
    ));
  }

  #[test]
  fn cus_r_read_size_less_exact_w_data_less() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let (len, data) = pkt.split_at(8);
    let (len_less, len_ex) = len.split_at(4);
    let res = reader.read(len_less);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
    assert!(matches!(
      reader,
      CustomTypeReader::ReadingTgtSize { idx: _, bytes: _ }
    ));
    let res = reader.read(len_ex);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
    assert!(matches!(
      reader,
      CustomTypeReader::Reading {
        target_size: _,
        payload: _
      }
    ));

    let (data_less, _) = data.split_at(1);
    let res = reader.read(data_less);
    assert!(matches!(res, Ok(ReadProgress::NotYet)));
    assert!(matches!(
      reader,
      CustomTypeReader::Reading {
        target_size: _,
        payload: _
      }
    ));
  }

  #[test]
  fn cus_r_read_size_exact_w_data_less_exact() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let (len, data) = pkt.split_at(8);
    let _ = reader.read(len);

    let (data_less, data_exact) = data.split_at(1);
    let _ = reader.read(data_less);
    let res = reader.read(data_exact);
    assert_reader_finished(res, reader, num_data, data_exact.len());
  }

  #[test]
  fn cus_r_read_size_less_exact_w_data_less_exact() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let (len, data) = pkt.split_at(8);
    let (len_less, len_ex) = len.split_at(4);
    let _ = reader.read(len_less);
    let _ = reader.read(len_ex);

    let (data_less, data_exact) = data.split_at(1);
    let _ = reader.read(data_less);
    let res = reader.read(data_exact);
    assert_reader_finished(res, reader, num_data, data_exact.len());
  }

  #[test]
  fn cus_r_read_payload_exact() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let res = reader.read(&pkt);
    assert_reader_finished(res, reader, num_data, pkt.len());
  }

  #[test]
  fn cus_r_read_payload_exact_reset() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let _ = reader.read(&pkt);
    assert!(reader.read_reset());
  }

  #[test]
  fn cus_r_read_empty_reset() {
    let mut reader = CustomTypeReader::new();
    assert!(!reader.read_reset());
  }

  #[test]
  fn cus_r_read_after_reset() {
    let mut reader = CustomTypeReader::new();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let _ = reader.read(&pkt);
    reader.read_reset();
    let num_data = 123098u32;
    let pkt = make_packet(&num_data.to_le_bytes());
    let res = reader.read(&pkt);
    assert_reader_finished(res, reader, num_data, pkt.len());
  }
}
