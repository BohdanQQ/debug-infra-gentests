#include "llcap_state.h"
#include "protobuf/proto/main.pb.h"
#include "shm_commons.h"
#include "shm_oneshot_rx.h"
#include "shm_write_channel.h"
#include <fcntl.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <memory>
#include <semaphore.h>
#include <unistd.h>

#define PUSH_FALURE 230

static ShmMeta s_buff_info;
// should be initialized and updated such that
// - is counted down on each target fn call entry
// - never undeflows (underflow attempts are expected)
// - if == 1, then target call is reached
// => initialized to the target call number + 1
//    -> tgt call number = 1 => init to 2 => first decrement creates 1 -> hijack
static unsigned int s_call_countdown;

static WriteChannel s_channel;

static bool get_buffer_info(ShmMeta *target) {
  return oneshot_shm_read(META_SEM_DATA, META_SEM_ACK, META_MEM_NAME, target,
                          sizeof(ShmMeta));
}

// sets up the semaphores and information required for buffer management
// if this returns 0, s_channel is ready for use
static int setup_infra(void) {
  int rv = 1;

  if (!get_buffer_info(&s_buff_info)) {
    printf("Could not obtain buffer info\n");
    return rv;
  }

  s_call_countdown = s_buff_info.target_call_number + 1;

  ChannelInfo info;
  info.buff_count = s_buff_info.buff_count;
  info.buff_len = s_buff_info.buff_len;
  info.total_len = s_buff_info.total_len;
#ifdef DEBUG
  printf("Buffer info: cnt %u, len %u, tot %u, mod %u, fn %u, tests %u, args "
         "%u, mode %u\n",
         info.buff_count, info.buff_len, info.total_len,
         s_buff_info.target_modid, s_buff_info.target_fnid,
         s_buff_info.test_count, s_buff_info.arg_count, s_buff_info.mode);
#endif // DEBUG
  if (info.buff_count * info.buff_len != info.total_len) {
    printf("sanity check failed - buffer sizes\n");
    return -1;
  }

  if (in_testing_mode()) {
    return 0;
  }

  return init_write_channel_with_info("capture", "base", &info, &s_channel);
}

int init(void) {
  if (setup_infra() != 0) {
    printf("Failed to init infra\n");
    exit(-1);
  }
  if (in_testing_mode()) {
    return 0;
  }
  return channel_start(&s_channel);
}

int push_data(const void *source, uint32_t len) {
#ifdef DEBUG
  for (uint32_t i = 0; i < len; ++i) {
    printf("%02X", ((uint8_t *)source)[i]);
  }
  printf(" %u\n", len);
#endif
  if (channel_write(&s_channel, source, len) != 0) {
    exit(PUSH_FALURE);
  }
  return 0;
}

void deinit(void) {
  if (in_testing_mode()) {
    return;
  }

  deinit_channel(&s_channel);
}

bool in_testing_mode(void) { return s_buff_info.mode == 2; }
bool in_testing_fork(void) { return s_buff_info.forked != 0; }
uint16_t get_test_tout_secs(void) {
  return in_testing_mode() ? s_buff_info.test_timeout_seconds : 0;
}
uint32_t test_count(void) { return s_buff_info.test_count; }

void set_fork_flag(void) { s_buff_info.forked = 1; }

uint32_t get_call_num(void) {
  return s_buff_info.target_call_number + 1 - s_call_countdown;
}
void register_call(void) {
  if (s_call_countdown > 0) {
    // s_call_countdown 0 means, that the testing has already been performed
    // 1 means we will be testing the call that caused register_call to be
    // called otherwise "we are not at the desired call yet"
    s_call_countdown--;
  }
}

void disable_hijacking(void) { s_call_countdown = 0; }

bool should_hijack_arg(void) { return s_call_countdown == 1; }

bool is_fn_under_test(uint32_t mod, uint32_t fn) {
  return in_testing_mode() && s_buff_info.target_modid == mod &&
         s_buff_info.target_fnid == fn;
}

// local argument packet storage
static ::llcaproto::Arguments *sp_packet = NULL;
// how much data has been alread read
static int s_current_idx = 0;

#define PAYLOAD_T uint64_t

// parent socket
static int s_socket_fd = -1;
// packet index that will be replacing the arguments of the desired call
static PAYLOAD_T s_packet_idx = 0;

void init_packet_socket(int fd, PAYLOAD_T request_idx) {
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

  sp_packet = new ::llcaproto::Arguments;
  if (sp_packet == NULL) {
    perror("Failed to alloc packet");
    free(packet);
    return false;
  }
  sp_packet->ParseFromArray(packet, static_cast<int>(packet_size));
  s_current_idx = 0;
  free(packet);

  // the packet is freed once all of its bytes are read (see
  // get_next_arg)
  return true;
}

const llcaproto::SingleArgVariant *get_next_arg() {
  if (sp_packet == nullptr) {
    return nullptr;
  }
  if (sp_packet->values().size() <= s_current_idx) {
    free(sp_packet);
    return nullptr;
  }
  return std::addressof(sp_packet->values()[s_current_idx++]);
}

bool send_test_pass_to_monitor(bool exception) {
  PAYLOAD_T payload = exception ? HOOKLIB_TESTEXC_VAL : HOOKLIB_TESTPASS_VAL;
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
    printf("Failed to initialize FULL semaphore %s\n", name_full_sem);
    perror("");
    return 1;
  }
  // notice no channel_start - we don't want to gain a free buffer at start - we
  // are trying to flush an already dirty buffer left over by the crashed
  // process
  return termination_sequence_raw(sem_full, buff_count);
}
#endif // MANUAL_INIT_DEINIT
