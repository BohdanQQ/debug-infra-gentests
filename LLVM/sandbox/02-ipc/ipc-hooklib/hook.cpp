#include "hook.h"
#include "llcap_state.h"
#include "protoTraits.hpp"
#include "protobuf/proto/main.pb.h"
#include "shm_commons.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cassert>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <format>
#include <google/protobuf/arena.h>
#include <iostream>
#include <mutex>
#include <ostream>
#include <ranges>
#include <string>
#include <string_view>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <thread>
#include <type_traits>
#include <unistd.h>
#include <utility>
#include <vector>

#define ENDPASS_CODE 231

#define HOOKLIB_EC_PKT_RD 232
#define HOOKLIB_EC_WTPID 233
#define HOOKLIB_EC_CONN 234
#define HOOKLIB_EC_START 236
#define HOOKLIB_EC_PAIR 237
#define HOOKLIB_EC_RECV_PKT 238
#define HOOKLIB_EC_TX_END 239
#define HOOKLIB_EC_TX_FIN 240
#define HOOKLIB_EC_IMPL 241

static int s_server_socket = -1;

template <typename T, std::size_t S> using Arr = std::array<T, S>;

template <size_t Sz>
static bool do_srv_send(const std::array<char, Sz> &message, const char *desc) {
  if (send(s_server_socket, message.data(), message.size(), 0) == -1) {
    perror(std::format("Failed to send {}\n", desc).c_str());
    close(s_server_socket);
    return false;
  }
  return true;
}

static bool connect_to_server(const char *path) {
  // https://beej.us/guide/bgipc/html/split/unixsock.html#unixsock
  struct sockaddr_un remote{.sun_family = AF_UNIX, .sun_path = ""};

  s_server_socket = socket(AF_UNIX, SOCK_STREAM, 0);
  if (s_server_socket == -1) {
    perror("Failed to create socket\n");
    return false;
  }
  // 108 is the limith of the sun_path field
  constexpr size_t SUN_PATH_MAX_LEN = 108;
  strncpy(static_cast<char *>(remote.sun_path), path, SUN_PATH_MAX_LEN);
  auto len = static_cast<socklen_t>(
      strnlen(static_cast<char *>(remote.sun_path), SUN_PATH_MAX_LEN) +
      sizeof(remote.sun_family));
  // reinterpret_cast should be legal here... (otherwise there is only C-style
  // cast)
  if (connect(s_server_socket, reinterpret_cast<struct sockaddr *>(&remote),
              len) == -1) {
    perror("Failed to connect\n");
    return false;
  }

  // the bitcast checks (at compile time) that pid_t fits the array
  auto pid_bytes = std::bit_cast<std::array<char, 4>>(getpid());

  return do_srv_send(pid_bytes, "Connect - PID");
}

static bool do_srv_recv(void *target, size_t size, const char *desc) {
  ssize_t rcvd = recv(s_server_socket, target, size, 0);
  if (rcvd <= 0) {
    perror(
        std::format("Failed to recv {0} - received {1}\n", desc, rcvd).c_str());
    close(s_server_socket);
    return false;
  } else if (static_cast<size_t>(rcvd) < size) {
    perror(std::format("Failed to recv size at {0} - got {1} - expected {2}\n",
                       desc, rcvd, size)
               .c_str());
    close(s_server_socket);
    return false;
  }
  return true;
}

constexpr std::size_t MSG_SIZE{16};

template <size_t OutSz, size_t InSz>
static void copy_into_impl(Arr<char, OutSz> &target, size_t shift,
                           const std::array<char, InSz> &in) {
  namespace rang = std::ranges;
  rang::copy(in, target.begin() + shift);
}

template <size_t OutSz, size_t InSz, size_t... InSzS>
static void copy_into_impl(std::array<char, OutSz> &target, size_t shift,
                           const std::array<char, InSz> &in,
                           const std::array<char, InSzS> &...others) {
  copy_into_impl(target, shift, in);
  copy_into_impl(target, shift + in.size(), others...);
}

template <size_t OutSz, typename... InS>
static void copy_into(std::array<char, OutSz> &target, InS... vals) {
  static_assert(target.size() >= (sizeof(vals) + ...),
                "values must fit the target");
  copy_into_impl(target, 0ULL, std::bit_cast<Arr<char, sizeof(vals)>>(vals)...);
}

template <size_t OutSz, typename... InS>
static Arr<char, OutSz> make_message(InS... vals) {
  Arr<char, OutSz> result{'\0'};
  ;
  copy_into(result, vals...);
  return result;
}

static bool send_start_msg(uint32_t mod, uint32_t fun, uint32_t call_idx) {
  auto message = make_message<MSG_SIZE>(TAG_START, mod, fun, call_idx);
  return do_srv_send(message, "msg start");
}

template <typename T, size_t InS>
static T take_into(const std::array<char, InS> &in) {
  static_assert(InS >= sizeof(T),
                "input must be at least as large as the desired output");
  std::array<char, sizeof(T)> tArr{'\0'};
  std::ranges::copy(in | std::views::take(sizeof(T)), tArr.begin());
  return std::bit_cast<T>(tArr);
}

// regardless of return type, the target must be freed by caller
static bool request_packet_from_server(uint64_t index, void **target,
                                       uint32_t *packet_size) {
  *target = NULL;
  *packet_size = 0;
  auto message = make_message<MSG_SIZE>(TAG_PKT, index);
  if (!do_srv_send(message, "pktrq")) {
    return false;
  }

  size_t pkt_size = 0;
  auto *pMsg = message.data();
  if (!do_srv_recv(pMsg, sizeof(uint32_t), "pkt sz")) {
    return false;
  }
  pkt_size = static_cast<size_t>(take_into<uint32_t>(message));
  void *buff = malloc(pkt_size);
  *target = buff;
  if (buff == NULL) {
    perror("Failed to alloc pkt");
    close(s_server_socket);
    return false;
  }

  if (!do_srv_recv(buff, pkt_size, "pkt data")) {
    return false;
  }
  *packet_size = static_cast<uint32_t>(pkt_size);
  return true;
}

enum class EMsgEnd : uint8_t {
  MSG_END_TIMEOUT = 0,
  MSG_END_SIGNAL = 1,
  MSG_END_STATUS = 2,
  MSG_END_PASS = 3,
  MSG_END_EXC = 4,
  MSG_END_FATAL = 5,
  // keep this one last!
  MSG_END_COUNT = 6
};

static uint16_t get_tag(EMsgEnd end_type) {
  constexpr uint8_t ENUM_LEN = std::to_underlying(EMsgEnd::MSG_END_COUNT);
  static constexpr std::array<uint16_t, ENUM_LEN> VALUES{
      TAG_TIMEOUT, TAG_SGNL, TAG_EXIT, TAG_PASS, TAG_EXC, TAG_FATAL};
  return VALUES[std::to_underlying(end_type)];
}

static bool send_test_end_message(uint64_t index, EMsgEnd end_type,
                                  int32_t status) {
  uint16_t tag = get_tag(end_type);
  auto message = make_message<MSG_SIZE>(TAG_TEST_END, index, tag, status);
  return do_srv_send(message, "test end msg");
}

static bool send_finish_message() {
  auto message = make_message<MSG_SIZE>(TAG_TEST_FINISH);
  return do_srv_send(message, "test finish msg");
}

static bool try_wait_pid(pid_t pid, int32_t *status, EMsgEnd *result) {
  int w = waitpid(pid, status, WNOHANG | WUNTRACED | WCONTINUED);
  if (w == -1) {
    std::cerr << "Failed waitpid" << std::endl;
    exit(HOOKLIB_EC_WTPID);
  } else if (w != 0) {
    if (w != pid) {
      std::cerr << "PID does not match... " << pid << " " << w << std::endl;
    }
    if (WIFEXITED(*status)) {
      *status = WEXITSTATUS(*status);
      *result = EMsgEnd::MSG_END_STATUS;
    } else if (WIFSIGNALED(*status)) {
      *status = WTERMSIG(*status);
      *result = EMsgEnd::MSG_END_SIGNAL;
    } else if (WIFSTOPPED(*status)) {
      *status = WSTOPSIG(*status);
      *result = EMsgEnd::MSG_END_SIGNAL;
    } else if (WIFCONTINUED(*status)) {
      *status = 0;
      *result = EMsgEnd::MSG_END_SIGNAL;
    } else {
      *result = EMsgEnd::MSG_END_SIGNAL;
    }
    return true;
  }
  return false;
}

enum class PollResult : std::uint8_t {
  ResultFDReady,
  ResultTimeout,
  ResultFail
};

static PollResult do_poll(int fd, short events, int timeout_ms, int *result) {
  events = POLLERR | POLLRDHUP | events;
  pollfd pollfd = {.fd = fd, .events = events, .revents = 0};

  int rv = poll(&pollfd, 1, timeout_ms);
  if (rv == 0) {
    // timeout
    return PollResult::ResultTimeout;
  } else if (rv < 0) {
    perror("Failed to poll test rq sock");
    return PollResult::ResultFail;
  }
  if ((rv & POLLERR) != 0 || (rv & POLLRDHUP) != 0) {
    std::cerr << "FD error " << rv << '\n';
    return PollResult::ResultFail;
  }

  *result = rv;
  return PollResult::ResultFDReady;
}

enum class ERequestResult : uint8_t {
  Error,
  Continue,
  TestPass,
  TestException
};

static ERequestResult handle_requests(int rq_sock) {
  int poll_rv;
  PollResult poll_res = do_poll(rq_sock, POLLIN, 50, &poll_rv);
  if (poll_res == PollResult::ResultFail) {
    return ERequestResult::Error;
  }
  if (poll_res == PollResult::ResultTimeout) {
    return ERequestResult::Continue;
  }
  if (poll_res != PollResult::ResultFDReady) {
    assert(false && "Invalid value");
  }

  if ((poll_rv & POLLIN) == 0) {
    return ERequestResult::Continue;
  }

  uint64_t packet_idx;
  if (read(rq_sock, &packet_idx, sizeof(packet_idx)) != sizeof(packet_idx)) {
    perror("read failed\n");
    return ERequestResult::Error;
  }

  if (packet_idx == HOOKLIB_TESTPASS_VAL) {
    return ERequestResult::TestPass;
  } else if (packet_idx == HOOKLIB_TESTEXC_VAL) {
    return ERequestResult::TestException;
  }

  void *packet_ptr = nullptr;
  uint32_t packet_size = 0;
  if (!request_packet_from_server(packet_idx, &packet_ptr, &packet_size)) {
    std::cerr << "Pktrq failed pkt idx " << packet_idx << std::endl;
    free(packet_ptr);
    return ERequestResult::Error;
  }
  if (write(rq_sock, &packet_size, sizeof(packet_size)) !=
      sizeof(packet_size)) {
    std::cerr << "Pkt sz send failed" << std::endl;
    free(packet_ptr);
    return ERequestResult::Error;
  }
  if (write(rq_sock, packet_ptr, packet_size) != packet_size) {
    std::cerr << "Pkt data send failed" << std::endl;
    free(packet_ptr);
    return ERequestResult::Error;
  }

  free(packet_ptr);
  return ERequestResult::Continue;
}

static EMsgEnd serve_for_other_until_end(int test_requests_socket, pid_t pid,
                                         int timeout_s, int32_t *status) {
  EMsgEnd result = EMsgEnd::MSG_END_FATAL;
  time_t seconds = time(NULL);
  while (true) {
    if (try_wait_pid(pid, status, &result)) {
      if (result == EMsgEnd::MSG_END_STATUS && *status == ENDPASS_CODE) {
        // the test could have passed due to the "special" exit code
        // (we just didnt catch it - yet)
        // we therefore check the requet socket once more in case we missed it
        ERequestResult req_result = handle_requests(test_requests_socket);
        switch (req_result) {
        case ERequestResult::TestPass:
          return EMsgEnd::MSG_END_PASS;
        case ERequestResult::TestException:
          return EMsgEnd::MSG_END_EXC;
        // fallthrough intended, the above can fail, we will just return the
        // result we got (status code)
        case ERequestResult::Error:
        case ERequestResult::Continue:
          break;
        default:
          std::cerr << "serve_for_other_until_end: invalid ERequestResult: "
                    << std::to_underlying(req_result);
          return EMsgEnd::MSG_END_FATAL;
        }
      }
      return result;
    }

    if (time(NULL) - seconds >= timeout_s) {
      std::cerr << "\tLLCAP-TEST Timeout (" << timeout_s << " s)" << std::endl;
      return EMsgEnd::MSG_END_TIMEOUT;
    }

    ERequestResult req_result = handle_requests(test_requests_socket);
    switch (req_result) {
    case ERequestResult::Error:
      std::cerr << "Request handler failed" << std::endl;
      return EMsgEnd::MSG_END_FATAL;
    case ERequestResult::TestPass:
      return EMsgEnd::MSG_END_PASS;
    case ERequestResult::TestException:
      return EMsgEnd::MSG_END_EXC;
    case ERequestResult::Continue:
      break;
    default:
      std::cerr << "serve_for_other_until_end: invalid ending ERequestResult: "
                << std::to_underlying(req_result);
      return EMsgEnd::MSG_END_FATAL;
    }
  }
}

static void perform_testing(uint32_t module_id, uint32_t function_id,
                            uint32_t call_idx) {
  if (!connect_to_server(TEST_SERVER_SOCKET_NAME)) {
    std::cerr << "Failed to connect" << std::endl;
    std::exit(HOOKLIB_EC_CONN);
  }

  if (!send_start_msg(module_id, function_id, call_idx)) {
    std::cerr << "Failed send start message" << std::endl;
    std::exit(HOOKLIB_EC_START);
  }

  set_fork_flag(); // setting the flag in both parent and the fork should not
                   // matter, this function never returns in the fork's parent
                   // (test coordinator)
                   // ! for mt_compat_testing we don't actually fork
                   // but the behavior should remain the same (call counts, ...)

  if (mt_compat_testing()) {
    void *packet_ptr = nullptr;
    uint32_t packet_size = 0;
    // in this mode, the test_count is the actual index of the test to request
    // from the server
    if (!request_packet_from_server(test_count(), &packet_ptr, &packet_size)) {
      std::cerr << std::format(
                       "Packet request failed with idx {0}, received size {1}",
                       test_count(), packet_size)
                << std::endl;
      std::exit(HOOKLIB_EC_RECV_PKT);
    }

    if (packet_ptr == nullptr ||
        !locally_initialize_arg_packet(packet_ptr,
                                       static_cast<int>(packet_size))) {
      std::cerr << std::format("Packet init failed with idx {0}, received size "
                               "{1}, packet ptr {2}",
                               test_count(), packet_size, packet_ptr)
                << std::endl;
      std::exit(HOOKLIB_EC_PAIR);
    }
    // go back and hijack arguments
    return;
  }

  for (uint32_t test_idx = 0; test_idx < test_count(); ++test_idx) {
    std::array<int, 2> sockets{0};

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sockets.data()) == -1) {
      perror("socketpair");
      std::exit(HOOKLIB_EC_PAIR);
    }
    int test_process_socket = sockets[1];
    int coordinator_socket = sockets[0];
    // LLCAP-SERVER <---- UNIX domain socket ----> COORDINATOR
    // <--coor_sock]-------[test_sock--> TEST PROCESS

    pid_t pid = fork();
    if (pid == 0) {
      // TEST PROCESS
      init_packet_socket(test_process_socket, test_idx);
      // populates "argument packet" that will be used by instrumentation
      if (!receive_packet_proto()) {
        perror("Failed to receive argument packet protobuf\n");
        std::quick_exit(HOOKLIB_EC_RECV_PKT);
      }
      // in the test process, return to resume execution (start hijacking)
      return;
    }
    // COORDINATOR
    int status = -1;
    EMsgEnd result = serve_for_other_until_end(
        coordinator_socket, pid, static_cast<int>(get_test_tout_secs()),
        &status);
    if (result == EMsgEnd::MSG_END_FATAL) {
      // attempt to provide all the errors
      std::cerr.flush();
      std::cout.flush();
    }

    if (result != EMsgEnd::MSG_END_STATUS &&
        result != EMsgEnd::MSG_END_SIGNAL && result != EMsgEnd::MSG_END_EXC &&
        result != EMsgEnd::MSG_END_PASS) {
      // kill the test process on non-exiting result (timeout, error, ...)
      // KILL and STOP cannot be ignored
      kill(pid, SIGSTOP);
    }

    if (!send_test_end_message(test_idx, result, status)) {
      std::exit(HOOKLIB_EC_TX_END);
    }
  }

  if (!send_finish_message()) {
    std::exit(HOOKLIB_EC_TX_FIN);
  }

  std::exit(0);
}
::llcaproto::Arguments *s_capptured_args;
thread_local google::protobuf::Arena s_arena;
// # in argument tracing
// the hook_arg_preamble and hook_arg_epilogue
// use this mutex to ensure no other argument instrumentation is taking place
// Note: the current implementation guarantees the thread
// that is permitted to enter hook_arg_preamble is the only one (by keeping a a unique logical ID
// per thread) and thus this mutex is "paranoid" in argument tracing mode

// # in call tracing, the mutex is used to make the pair (Module ID, Function ID) transferred
// atomically without interleavings with other threads' ID transfers
std::mutex s_data_push_mutex;

void hook_arg_preamble(uint32_t module_id, uint32_t fn_id) {
  // CONTEXT TO KEEP IN MIND:
  // we just entered an instrumented function
  if (!in_testing_mode()) {
    s_data_push_mutex.lock();
    // we are capturing function arguments, first we inform of the function
    // itself
    push_data(&module_id, sizeof(module_id));
    push_data(&fn_id, sizeof(fn_id));
    auto tid = std::this_thread::get_id();
    static_assert(sizeof(tid) == 8, "Unexpected Thread ID size");
    push_data(&tid, sizeof(tid));
    s_capptured_args =
        google::protobuf::Arena::Create<::llcaproto::Arguments>(&s_arena);
    // the rest of this function concerns only the testing mode
    return;
  }

  // in testing mode we discriminate based on the function that is under the
  // test if THIS function (the caller of hook_arg_preamble, see context) is the
  // desired one we must furhter determine whether we are in the "right" call
  // (n-th call)
  if (!in_testing_fork() && is_fn_under_test(module_id, fn_id)) {
    // modifies call counter for this thread
    register_call();

    // should_hijack_arg becomes true as soon as the coutner updated above
    // indicates that we "should instrument this call"
    if (should_hijack_arg()) {
      std::cerr << "TESTING" << std::endl;
      perform_testing(module_id, fn_id, get_call_num());
      // PARENT process never returns from the first call to instrumented
      // function CHILD process simply continues execution, should_hijack_arg is
      // used further in the type-hijacking functions
    }
  }
}

void hook_arg_epilogue(uint32_t module_id, uint32_t fn_id) {
  if (in_testing_mode()) {
    if (is_fn_under_test(module_id, fn_id) && should_hijack_arg()) {
      // we're done with hijacking of the function
      disable_hijacking();
    }
    return;
  }
  // FIXME: implement a ZeroCopyOutputStream
  static std::vector<std::byte> buff(4096);
  uint64_t size = static_cast<uint32_t>(s_capptured_args->ByteSizeLong());
  if (size == 0) {
    s_data_push_mutex.unlock();
    return;
  }
  if (size > buff.size()) {
    buff.resize(size);
  }

  push_data(&size, sizeof(size));
  s_capptured_args->SerializeToArray(buff.data(), static_cast<int>(size));
  push_data(buff.data(), static_cast<uint32_t>(size));
  s_arena.Reset();
  s_data_push_mutex.unlock();
}

int32_t hook_test_is_executing(uint32_t module_id, uint32_t fn_id) {
  // The one and zero result as well as its type is crucial here
  // as the result is also used in the LLVM IR plugin (on LLVM IR level)
  int32_t res = (in_testing_mode() && in_testing_fork() &&
                 is_fn_under_test(module_id, fn_id))
                    ? 1
                    : 0;
  return res;
}

static void hook_test_epilogue_impl(uint32_t module_id, uint32_t fn_id,
                                    bool exception) {
  if (0 == hook_test_is_executing(module_id, fn_id)) {
    return;
  }

  if (mt_compat_testing()) {
    // status sent as -1 - the status will not be inspected because if code
    // reaches here, we are finishing via instrumented code (this function) in
    // other words, if the program fails, execution will not reach here and
    // llcap-server will have to deal with our status code on its own
    if (!send_test_end_message(
            test_count(),
            exception ? EMsgEnd::MSG_END_EXC : EMsgEnd::MSG_END_PASS, -1)) {
      perror("signal end to monitor from mt_compat\n");
    }

    // the ENDPASS_CODE is needed only for the forking testing mode (we already
    // sent it via the send_test_end_message)
    std::exit(0);
  }

  // this means we are in a child process
  if (!send_test_pass_to_monitor(exception)) {
    perror("signal end to monitor\n");
  }
  std::quick_exit(ENDPASS_CODE);
}

// here are all the hooks used by the llvm-pass
// calls to these funcitons are inserted by the instrumentation
// and are called during the various phases

void hook_start(uint32_t module_id, uint32_t fn_id) {
  // called during call tracing
  auto guard = std::unique_lock{s_data_push_mutex};
  push_data(&module_id, sizeof(module_id));
  push_data(&fn_id, sizeof(fn_id));
}

void hook_test_epilogue(uint32_t module_id, uint32_t fn_id) {
  // called at the end of the tested function (in testing phase)
  // before every non-execption-related LLVM instruction
  // the _impl does not return (exits) if a test is underway
  hook_test_epilogue_impl(module_id, fn_id, false);
}

void hook_test_epilogue_exc(uint32_t module_id, uint32_t fn_id) {
  // same as above, except this functions just
  // indicates to the llcap-server that we handled an exception
  // the support for exceptions is incomplete, only subset
  // of all potenitally-returning instructions are covered (in the llvm-pass)
  hook_test_epilogue_impl(module_id, fn_id, true);
}

// creates a simple wrapper that properly calls the template fn defined
// below (hook_t)
#define GEN_HOOK_FN(name, argt, storaget)                                      \
  GENFNDECLTEST(name, argt, n) {                                               \
    hook_t<argt, storaget>(n, target, module, fn);                             \
  }

template <class T> constexpr static std::string_view type_name() {
  using std::string_view;
#ifdef __clang__
  string_view p = __PRETTY_FUNCTION__;
  return {p.data() + 34, p.size() - 34 - 1};
#elif defined(__GNUC__)
  string_view p = __PRETTY_FUNCTION__;
#if __cplusplus < 201402
  return {p.data() + 36, p.size() - 36 - 1};
#else
  return {p.data() + 49, p.find(';', 49) - 49};
#endif
#elif defined(_MSC_VER)
  string_view p = __FUNCSIG__;
  return {p.data() + 84, p.size() - 84 - 7};
#endif
}

// NumT - numeric type for which we're creating the hook
// StorageT - the type to be used to store the NumT inside a protobuf
template <class NumT, class StorageT>
  requires ConvertibleIsh<StorageT, NumT>
static void hook_t(NumT n, NumT *target, uint32_t module, uint32_t fn) {
#define COPY_AND_RETURN                                                        \
  assign<NumT>(*(target), (n));                                                \
  return
  if (in_testing_mode()) {
    if (!is_fn_under_test((module), (fn))) {
      COPY_AND_RETURN;
    } else {
      if (!should_hijack_arg()) {
        COPY_AND_RETURN;
      }
      const auto *arg = get_next_arg();
      if (nullptr == arg) {
        perror("hookt terr: size, capacity\n");
        exit(HOOKLIB_EC_PKT_RD);
      }

      // 1. check protobuf type
      // 2. obtain the value from the protobuf
      // 3. rewrite the target (hijack)
      using NestTrait = ProtobufNestTrait<StorageT>;
      if (!NestTrait::check(*arg)) {
        perror("Serious error - unexpected argument type @ hook_t \n");
        std::cerr << type_name<NumT>() << ' ' << type_name<VariantChecker>()
                  << std::endl;
        exit(HOOKLIB_EC_TX_FIN);
      }
      /* is safe assuming the incoming messages are of correct order */
      *target = static_cast<NumT>(NestTrait::extract(*arg));
    }
    return;
  }
  // register value into the static argument packet protobuf
  auto *v = s_capptured_args->add_values();
  capture_into<StorageT>(v, n);
  COPY_AND_RETURN;
#undef COPY_AND_RETURN
}

// as mentioned in llvm-pass, the variations for same-sized primitives
// are redundant at this point, we keep them, however, since they do not add
// that much clutter and may prove useful in the future (to handle specific
// types differently)
GEN_HOOK_FN(hook_float, float, float)
GEN_HOOK_FN(hook_double, double, double)

GEN_HOOK_FN(hook_char, char, int32_t)
GEN_HOOK_FN(hook_uchar, UCHAR, uint32_t)
GEN_HOOK_FN(hook_short, short, int32_t)
GEN_HOOK_FN(hook_ushort, USHORT, uint32_t)
GEN_HOOK_FN(hook_int32, int, int)
GEN_HOOK_FN(hook_uint32, UINT, UINT)
GEN_HOOK_FN(hook_int64, LLONG, int64_t)
GEN_HOOK_FN(hook_uint64, ULLONG, uint64_t)

// implements the above, just for a "custom" type, the std::string
//
// custom type hooks always capture via a pointer to the argument (str)
// otherwise, the logic is the same - we only capture and deserialize
// quite a bit more data (size, capacity, content)
void llcap_hooklib_extra_cxx_string(std::string *str, std::string **target,
                                    uint32_t module, uint32_t function) {
  if (in_testing_mode()) {
    if (!is_fn_under_test(module, function) || !should_hijack_arg()) {
      goto move_string_to_target;
    }
    // up to this point, the logic is the same as with primitive types
    *target = new std::string();
    // we "consume" from the packet in the exact same order as we
    // "push" in the argument capture (below)
    const auto *arg = get_next_arg();
    if (nullptr == arg) {
      perror("strhook terr: size, capacity\n");
      exit(HOOKLIB_EC_PKT_RD);
    }
    if (!arg->has_str()) {
      perror("Serious error - unexpected argument type @ hook str\n");
      exit(HOOKLIB_EC_TX_FIN);
    }
    const auto &str_arg = arg->str();
    assign_stringwrap(**target, str_arg);
    return;
  } else {
    // argument capture
    auto *v = s_capptured_args->add_values();
    if (!capture_stringwrap(v, *str, v->GetArena())) {
      return;
    }
  }
move_string_to_target:
  // implementation detail: it str is an in/out argument, the caller of the
  // instrumented function will not
  *target = str;
}

template <typename T>
static bool make_one_at(T &target, const llcaproto::SingleArgVariant &source) {
  using NestTrait = ProtobufNestTrait<T>;

  if (!NestTrait::check(source)) {
    return false;
  } else {
    auto ex = NestTrait::extract(source);
    return ProtobufNestTrait<T>::construct(target, ex);
  }
}

template <typename T>
  requires(!std::is_same_v<bool, T>)
static void llcap_gen_vec_not_bool(std::vector<T> *vec, std::vector<T> **target,
                                   uint32_t module, uint32_t function) {
  if (in_testing_mode()) {
    if (!is_fn_under_test(module, function) || !should_hijack_arg()) {
      goto move_vec_to_target;
    }
    // up to this point, the logic is the same as with primitive types
    *target = new std::vector<T>();
    // we "consume" from the packet in the exact same order as we
    // "push" in the argument capture (below)
    const auto *arg = get_next_arg();
    if (nullptr == arg) {
      perror("strhook terr: size, capacity\n");
      exit(HOOKLIB_EC_PKT_RD);
    }
    if (!arg->has_vec()) {
      perror("Serious error - unexpected argument type @ hook gvec\n");
      exit(HOOKLIB_EC_TX_FIN);
    }
    const auto &vec_arg = arg->vec();
    (*target)->reserve(vec_arg.capacity());
    (*target)->resize(static_cast<size_t>(vec_arg.values_size()));

    std::vector<T> &target_vec = **target;
    for (int i = 0; i < vec_arg.values_size(); ++i) {
      const auto &source_val = vec_arg.values(i);
      auto &target_val = target_vec[static_cast<size_t>(i)];
      if (!make_one_at<T>(target_val, source_val)) {
        perror("Could not make element at index");
        return;
      }
    }
    return;
  } else {
    // argument capture
    auto *v = s_capptured_args->add_values();
    llcaproto::Vector *protoVec = v->mutable_vec();
    if (nullptr == protoVec) {
      exit(HOOKLIB_EC_IMPL);
    }
    auto it = vec->cbegin();
    protoVec->set_capacity(vec->capacity());
    for (; it != vec->cend(); ++it) {
      auto *vecItemVariant = protoVec->add_values();
      if (!ProtobufNestTrait<T>::variant_capture(vecItemVariant, *it)) {
        perror("Failure capturing item");
      }
    }
  }
move_vec_to_target:
  *target = vec;
}

#define MAKE_VECTOR_HOOK(id, type)                                             \
  GEN_FN_VECDECL(id, type) {                                                   \
    llcap_gen_vec_not_bool<type>(vec, target, module, function);               \
  }

// creates a vector hooking function under the name "llcap_vector_<id>" (here
// <id> == cint) that captures std::vector<T> (here T == int32_t) currently, T
// cannot be bool other custom types have to be registered via ProtobufNestTrait
// for reference, see the std::string specialization:
// ProtobufNestTrait<std::string>
MAKE_VECTOR_HOOK(cint, int32_t)
// MAKE_VECTOR_HOOK(cuint, uint32_t)
// MAKE_VECTOR_HOOK(cfloat, float)
MAKE_VECTOR_HOOK(stdstring, std::string)
