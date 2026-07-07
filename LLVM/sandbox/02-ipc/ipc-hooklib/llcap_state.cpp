#include "llcap_state.h"
#include "checkpoint.hpp"
#include "debug.hpp"
#include "protobuf/proto/main.pb.h"
#include "shm_commons.h"
#include "shm_oneshot_rx.h"
#include "shm_write_channel.h"
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <iostream>
#include <memory>
#include <optional>
#include <print>
#include <semaphore.h>
#include <thread>
#include <threads.h>
#include <unistd.h>
#include <vector>

struct SRetargetInfo {
  unsigned int target_lid;
  unsigned long long new_target_call;
  unsigned int new_mode;
  unsigned int new_packet_index;
  std::array<char, CRIU_CHECKPOINT_DIR_PATH_MAXLEN_WZERO> criu_path;
};

constexpr int ID_FAILURE{229};
constexpr int PUSH_FALURE{230};

static ShmMeta s_buff_info{};
// temporary static space for thread counts that is used during initialization
static std::vector<uint64_t> s_thread_counts;

static WriteChannel s_channel;

static bool populate_static_metadata(const void *source, uint32_t size,
                                     [[maybe_unused]] void *data) {
  if (size < sizeof(s_buff_info)) {
    std::println("Unexpected size {}, expected {}\n", size,
                 sizeof(s_buff_info));
    return false;
  }
  memcpy(&s_buff_info, source, sizeof(s_buff_info));

  if constexpr (DBG) {
    std::print("Mode: ");
    switch (s_buff_info.mode) {
    case MODE_CALL_TRACE:
      std::println("call trace");
      break;
    case MODE_ARG_CAPTURE:
      std::println("argument capture");
      break;
    case MODE_TESTING:
      std::println("testing");
      break;
    case MODE_CHECKPOINT_TESTING_NOCHECKPOINT:
      std::println("testing - no checkpoint");
      break;
    case MODE_CHECKPOINT_TESTING_DO_CHECKPOINT:
      std::println("testing - checkpoint");
      break;
    default:
      std::println("{}", s_buff_info.mode);
    }

    std::println("Thread count: {}", s_buff_info.thread_count);
  }

  for (uint32_t i = 0; i < s_buff_info.thread_count; ++i) {
    if (i == s_buff_info.target_thread_lid) {
      s_thread_counts.push_back(s_buff_info.target_call_number);
    } else {
      s_thread_counts.push_back(0);
    }
  }

  return true;
}

static bool get_buffer_info() {
  return oneshot_shm_read(META_SEM_DATA, META_SEM_ACK, META_MEM_NAME,
                          META_MEM_SIZE_NAME, populate_static_metadata,
                          1024U * 1024U * 1024U * 2U, nullptr);
}

static bool receive_retarget_data(const void *source, uint32_t size,
                                  void *target) {
  if (size != sizeof(ShmMeta)) {
    std::println(std::cerr, "Reinit: invalid size received: {}, expected {}",
                 size, sizeof(ShmMeta));
    return false;
  } else if (target == nullptr) {
    std::println(std::cerr, "Reinit: invalid arg (null)");
    return false;
  }
  ShmMeta shm{};
  std::memcpy(&shm, source, size);

  auto *rtg = start_lifetime_as<SRetargetInfo>(target);
  rtg->new_target_call = shm.target_call_number;
  rtg->target_lid = shm.target_thread_lid;
  rtg->new_mode = shm.mode;
  rtg->new_packet_index = shm.test_count;
  std::memcpy(rtg->criu_path.data(), shm.checkpoint_dump_dir,
              rtg->criu_path.size());

  return true;
}

thread_local std::optional<uint32_t> t_logical_id = std::nullopt;

// obtains a new logical thread ID for the thread
static uint32_t get_new_logical_id() {
  static std::atomic<uint32_t> auto_increment{0};
  auto cpy = auto_increment.fetch_add(1);
  if constexpr (DBG) {
    std::println("Thread ID {} mapped to logical ID {}",
                 std::this_thread::get_id(), cpy);
  }
  return cpy;
}

static uint64_t ensure_logical_id() {
  if (!t_logical_id.has_value()) {
    if constexpr (DBG) {
      std::println("Ensuring L_ID for {}", std::this_thread::get_id());
    }
    t_logical_id = get_new_logical_id();
  }
  return *t_logical_id;
}

uint64_t get_thread_lid() {
  auto id = ensure_logical_id();
  return id;
}

/**
Interfaces for call counter; no checkpointing and single-threaded by default
 */
class CallCounter {
public:
  // purely virtual
  virtual void register_call() = 0;
  virtual uint32_t get_call_num() = 0;
  virtual void disable_hijacking() = 0;
  virtual bool should_hijack_arg() = 0;

  // defaulted
  virtual void void_retarget_info_if_present() {};
  virtual void retarget_after_restore(SRetargetInfo /*info*/) {
    std::cerr << "retarget_after_restore called in unsupported mode"
              << std::endl;
    std::quick_exit(3);
  };
  virtual bool is_thread_under_test() { return true; };
  virtual ~CallCounter() = default;
};

class SingleThreadCallCounter final : public CallCounter {
  // should be initialized and updated such that
  // - is counted down on each target fn call entry
  // - never undeflows (underflow attempts are expected)
  // - if == 1, then target call is reached
  // => initialized to the target call number + 1
  //    -> tgt call number = 1 => init to 2 => first decrement creates 1 -> hijack
  unsigned int m_call_countdown;

public:
  SingleThreadCallCounter(unsigned int countdown)
      : m_call_countdown(countdown) {}
  SingleThreadCallCounter(const SingleThreadCallCounter &) = default;
  SingleThreadCallCounter(SingleThreadCallCounter &&) = default;
  SingleThreadCallCounter &operator=(const SingleThreadCallCounter &) = default;
  SingleThreadCallCounter &operator=(SingleThreadCallCounter &&) = default;
  virtual void register_call() override {
    if (m_call_countdown > 0) {
      // s_call_countdown 0 means, that the testing has already been performed
      // 1 means we will be testing the call that caused register_call to be
      // called otherwise "we are not at the desired call yet"
      m_call_countdown--;
    }
  }
  virtual uint32_t get_call_num() override {
    return s_buff_info.target_call_number + 1 - m_call_countdown;
  }
  virtual void disable_hijacking() override { m_call_countdown = 0; }
  virtual bool should_hijack_arg() override { return m_call_countdown == 1; }
};

// Encapsulates the querying over call counts in the multithreaded-support mode
// only one global instance shall exist
class LogicalThreadCallCounter final : public CallCounter {
  // count-down vector
  std::vector<uint64_t> m_counts;
  // count-up vector (FIXME: can probably be just the count-up vector)
  std::vector<uint64_t> m_aux_counts;
  // retarget helper that ensures proper call indicies are reported to
  // llcap-server
  // - the restoration from a checkpoint performs "retargetting"
  // --> this fools the retargetted test to skip instrumentation of the upcoming
  // call and perform testing at the call after the upcoming call
  std::optional<uint32_t> m_reported_call_num;

public:
  LogicalThreadCallCounter(const LogicalThreadCallCounter &) = default;
  LogicalThreadCallCounter(LogicalThreadCallCounter &&) = default;
  LogicalThreadCallCounter &
  operator=(const LogicalThreadCallCounter &) = default;
  LogicalThreadCallCounter &operator=(LogicalThreadCallCounter &&) = default;
  explicit LogicalThreadCallCounter(std::vector<uint64_t> &&counts)
      : m_counts(std::move(counts)), m_aux_counts(m_counts.size(), 0) {}

  virtual void register_call() override {
    auto logId = ensure_logical_id();
    ;
    if constexpr (DBG) {
      std::println("Registering call for {}", logId);
    }

    if (logId >= m_counts.size()) {
      std::println(std::cerr, "Thread ID of unexpected value {} - size: {}",
                   logId, m_counts.size());
      std::quick_exit(ID_FAILURE);
    }

    if (logId == s_buff_info.target_thread_lid && m_counts[logId] > 0) {
      if constexpr (DBG) {
        std::println("Registered {} @ {}", logId, m_counts[logId]);
      }
      // m_counts[logId] 0 means, that the testing has already been performed
      // 1 means we will be testing the call that caused register_call to be
      // called otherwise "we are not at the desired call yet"
      m_counts[logId]--;
      ++m_aux_counts[logId];
    }
  }

  virtual uint32_t get_call_num() override {
    auto id = ensure_logical_id();
    if (m_reported_call_num) {
      return *m_reported_call_num;
    }
    return static_cast<uint32_t>(s_buff_info.target_call_number + 1 -
                                 m_counts[id]);
  }

  virtual void disable_hijacking() override {
    auto id = ensure_logical_id();
    m_counts[id] = 0;
  }

  virtual bool should_hijack_arg() override {
    auto id = ensure_logical_id();
    return is_lid_tested() && m_counts[id] == 1;
  }

  virtual void void_retarget_info_if_present() override {
    m_reported_call_num = std::nullopt;
  }

  // adjusts the internal state after checkpoint_restore
  virtual void retarget_after_restore(SRetargetInfo info) override {
    s_buff_info.target_thread_lid = info.target_lid;
    if (m_aux_counts[info.target_lid] > info.new_target_call) {
      std::println(std::cerr,
                   "warning: requested call number target won't be hit!");
    }
    m_counts[info.target_lid] = std::max(
        0ULL, info.new_target_call - m_aux_counts[info.target_lid] + 1);
    m_reported_call_num = info.new_target_call;
    s_buff_info.mode = info.new_mode;
    s_buff_info.test_count = info.new_packet_index;
    std::memcpy(&s_buff_info.checkpoint_dump_dir[0], info.criu_path.data(),
                info.criu_path.size());

    if constexpr (DBG) {
      std::println(std::cerr,
                   "Retargeted to\n\tmode {}\n\tindex {}\n\tnew tgt call "
                   "{}\n\tcounts value {}\n",
                   s_buff_info.mode, s_buff_info.test_count,
                   info.new_target_call, m_counts[info.target_lid]);
    }
  }

  virtual bool is_thread_under_test() override { 
    return is_lid_tested(); 
  };

  static bool is_lid_tested() {
    return ensure_logical_id() == s_buff_info.target_thread_lid;
  }
};

static std::unique_ptr<CallCounter> s_call_countdown_instance{};

// sets up the semaphores and information required for buffer management
// if this returns 0, s_channel is ready for use
static int setup_infra(void) {
  int rv = 1;

  if (!get_buffer_info()) {
    std::println("Could not obtain buffer info");
    return rv;
  }

  if (mt_compat_testing()) {
    if constexpr (DBG) {
      std::println("MT compat... Thread counts:");
    }
    for (auto &v : s_thread_counts) {
      if constexpr (DBG) {
        std::print("{} ", v);
      }
      v += 1;
    }
    if constexpr (DBG) {
      std::println("");
    }
    s_call_countdown_instance =
        std::make_unique<LogicalThreadCallCounter>(std::move(s_thread_counts));
  } else {
    s_call_countdown_instance = std::make_unique<SingleThreadCallCounter>(
        s_buff_info.target_call_number + 1);
  }

  ChannelInfo info;
  info.buff_count = s_buff_info.buff_count;
  info.buff_len = s_buff_info.buff_len;
  info.total_len = s_buff_info.total_len;
#ifdef DEBUG
  std::println(
      "Buffer info: cnt {}, len {}, tot {}, mod {}, fn {}, tests {}, mode {}",
      info.buff_count, info.buff_len, info.total_len, s_buff_info.target_modid,
      s_buff_info.target_fnid, s_buff_info.test_count, s_buff_info.mode);
#endif // DEBUG
  if (info.buff_count * info.buff_len != info.total_len) {
    std::println("sanity check failed - buffer sizes");
    return -1;
  }

  if (in_testing_mode()) {
    return 0;
  }

  return init_write_channel_with_info("capture", "base", &info, &s_channel);
}

int init(void) {
  if (setup_infra() != 0) {
    std::println("Failed to init infra");
    std::exit(-1);
  }
  if (in_testing_mode()) {
    return 0;
  }
  return channel_start(&s_channel);
}

int push_data(const void *source, uint32_t len) {
  if constexpr (DBG) {
    std::print("{} | ", std::this_thread::get_id());
    for (uint32_t i = 0; i < len; ++i) {
      std::print("{:02X} ", ((uint8_t *)source)[i]);
    }
    std::println("| {}", len);
  }
  if (channel_write(&s_channel, source, len) != 0) {
    std::exit(PUSH_FALURE);
  }
  return 0;
}

void deinit(void) {
  if (in_testing_mode()) {
    return;
  }

  deinit_channel(&s_channel);
}

static unsigned int test_mode() { return s_buff_info.mode; }

bool performs_retarget() {
  const auto mode = test_mode();
  return mode == MODE_CHECKPOINT_TESTING_DO_CHECKPOINT;
}

bool in_testing_mode(void) {
  auto mode = test_mode();
  return mode == MODE_TESTING || mode == MODE_MT_TESTING ||
         mode == MODE_CHECKPOINT_TESTING_DO_CHECKPOINT ||
         mode == MODE_CHECKPOINT_TESTING_NOCHECKPOINT;
}

bool shall_perform_checkpoint() {
  return performs_retarget() && s_buff_info.checkpoint_dump_dir[0] != 0 &&
         s_buff_info.checkpoint_id != 0ULL &&
         LogicalThreadCallCounter::is_lid_tested() &&
         test_mode() == MODE_CHECKPOINT_TESTING_DO_CHECKPOINT;
}

static std::optional<SRetargetInfo> get_retarget_data() {
  SRetargetInfo rv;
  bool readres = oneshot_shm_read(META_SEM_DATA, META_SEM_ACK, META_MEM_NAME,
                                  META_MEM_SIZE_NAME, receive_retarget_data,
                                  1024U * 1024U * 1024U * 2U, &rv);
  return readres ? std::optional{rv} : std::nullopt;
}

bool perform_checkpoint() {
  if (!mt_compat_testing()) {
    std::println(std::cerr, "Unexpected mode");
    return false;
  }

  s_call_countdown_instance->void_retarget_info_if_present();
  // normally, we would like to "deinitialize" every single shared resource
  // (e.g. the shared memory mapping)
  // but since checkpointing is a testing-only feature and we don't use
  // shared memory (after initialization) in the testing phase, we don't need to
  // do anything here
  auto rv =
      performCheckpoint(s_buff_info.checkpoint_dump_dir,
                        s_buff_info.checkpoint_id, s_buff_info.shell_job != 0);
  if (!rv) {
    std::println(std::cerr, "Checkpoint failure: {}", rv.error());
    return false;
  }

  // likewise, as we did not deinit anything, we don't need to reinit anything
  // server connection will be established as per our normal testing protocol

  if (*rv) {
    // we just need to retarget the test run if we have been restored from a
    // checkpoint
    auto retarget_data = get_retarget_data();
    if (!retarget_data) {
      std::println(std::cerr, "Failed restore");
      return false;
    }
    s_call_countdown_instance->retarget_after_restore(*retarget_data);
  }
  return true;
}

bool mt_compat_testing() {
  auto mode = test_mode();
  return mode == MODE_MT_TESTING ||
         mode == MODE_CHECKPOINT_TESTING_DO_CHECKPOINT ||
         mode == MODE_CHECKPOINT_TESTING_NOCHECKPOINT;
}

bool in_testing_fork(void) { return s_buff_info.forked != 0; }
uint16_t get_test_tout_secs(void) {
  return in_testing_mode() ? s_buff_info.test_timeout_seconds : 0;
}
uint32_t test_count(void) { return s_buff_info.test_count; }

uint32_t arg_pkt_index_to_fetch(void) {
  /* this field access is intentional despite the name */
  return s_buff_info.test_count;
}

void set_fork_flag(void) { s_buff_info.forked = 1; }

uint32_t get_call_num(void) {
  return s_call_countdown_instance->get_call_num();
}

void register_call(void) {
  s_call_countdown_instance->register_call();
}

void disable_hijacking(void) {
  s_call_countdown_instance->disable_hijacking();
}

bool should_hijack_arg(void) {
  return s_call_countdown_instance->should_hijack_arg();
}

static bool is_thread_under_test() {
  return s_call_countdown_instance->is_thread_under_test();
}

bool is_fn_under_test(uint32_t mod, uint32_t fn) {
  return in_testing_mode() && s_buff_info.target_modid == mod &&
         s_buff_info.target_fnid == fn && is_thread_under_test();
}

// local argument packet storage
static ::llcaproto::Arguments sp_packet;
// how much data has been alread read
static int s_current_idx = 0;

bool locally_initialize_arg_packet(void *owning_packet, int packet_size) {
  sp_packet.ParseFromArray(owning_packet, packet_size);
  s_current_idx = 0;
  free(owning_packet);
  return true;
}

using PacketIdxT = uint64_t;

// parent socket
static int s_socket_fd = -1;
// packet index that will be replacing the arguments of the desired call
static PacketIdxT s_packet_idx = 0;

void init_packet_socket(int fd, PacketIdxT request_idx) {
  s_socket_fd = fd;
  s_packet_idx = request_idx;
}

bool receive_packet_proto(void) {
  // send a request to the test coordinator (parent)
  if (write(s_socket_fd, &s_packet_idx, sizeof(s_packet_idx)) !=
      sizeof(s_packet_idx)) {
    return false;
  }
  // read the lenght and the payload
  uint32_t packet_size;
  if (read(s_socket_fd, &packet_size, sizeof(packet_size)) !=
      sizeof(packet_size)) {
    perror("Failed to recv packet sz");
    return false;
  }

  auto *packet = malloc(packet_size);
  if (packet == NULL) {
    perror("Failed to alloc packet");
    return false;
  }

  if (read(s_socket_fd, packet, packet_size) != packet_size) {
    perror("Failed to recv packet data");
    free(packet);
    return false;
  }

  // the packet is freed once all of its bytes are read (see
  // get_next_arg)
  return locally_initialize_arg_packet(packet, static_cast<int>(packet_size));
}

// returns the pointer to the next argument
const llcaproto::SingleArgVariant *get_next_arg() {
  if (sp_packet.values().size() <= s_current_idx) {
    return nullptr;
  }
  return std::addressof(sp_packet.values()[s_current_idx++]);
}

bool send_test_pass_to_monitor(bool exception) {
  PacketIdxT payload = exception ? HOOKLIB_TESTEXC_VAL : HOOKLIB_TESTPASS_VAL;
  static_assert(sizeof(s_packet_idx) == sizeof(payload), "sanity check");

  return write(s_socket_fd, &payload, sizeof(payload)) == sizeof(payload);
}

#ifdef MANUAL_INIT_DEINIT
// after a crash, there can be a buffer, that needs to be flushed
// we find this by looking at the payload length of a buffer (the first 4 bytes)
// if there is 0 -> buffer has been flushed (responsibility of the other side)
//  -> we do "nothing" and only signal on the full semaphore (to make sure the
//  other side reads a "zero-length" buffer and terminates)
// if there is non-zero -> buffer was used and not flushed (due to a crash)
//  -> we signal 2 times on the semaphore, once for the outgoing data and once
//  for the terminating message
int init_finalize_after_crash(const char *name_full_sem, uint32_t buff_count) {
  sem_t *sem_full = sem_open(name_full_sem, O_CREAT, SEMPERMS, 0);
  if (sem_full == SEM_FAILED) {
    std::println("Failed to initialize FULL semaphore {}\n", name_full_sem);
    perror("");
    return 1;
  }
  // notice no channel_start - we don't want to gain a free buffer at start - we
  // are trying to flush an already dirty buffer left over by the crashed
  // process
  return termination_sequence_raw(sem_full, buff_count);
}
#endif // MANUAL_INIT_DEINIT
