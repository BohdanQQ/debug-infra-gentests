#include "hook.h"
#include "llcap_state.h"
#include "shm_commons.h"
#include <array>
#include <cassert>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <ostream>
#include <stdbool.h>
#include <string>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <utility>
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

static bool connect_to_server(const char *path) {
  // https://beej.us/guide/bgipc/html/split/unixsock.html#unixsock
  socklen_t len;
  struct sockaddr_un remote;
  remote.sun_family = AF_UNIX;

  s_server_socket = socket(AF_UNIX, SOCK_STREAM, 0);
  if (s_server_socket == -1) {
    perror("Failed to create socket\n");
    return false;
  }
  // 108 is the limith of the sun_path field
  constexpr size_t SUN_PATH_MAX_LEN = 108;
  strncpy(remote.sun_path, path, SUN_PATH_MAX_LEN);
  len = static_cast<socklen_t>(strnlen(remote.sun_path, SUN_PATH_MAX_LEN) +
                               sizeof(remote.sun_family));
  // reinterpret_cast should be legal here... (otherwise there is only C-style
  // cast)
  if (connect(s_server_socket, reinterpret_cast<struct sockaddr *>(&remote),
              len) == -1) {
    perror("Failed to connect\n");
    return false;
  }

  return true;
}

static bool do_srv_send(void *data, size_t size, const char *desc) {
  if (send(s_server_socket, data, size, 0) == -1) {
    std::cerr << "Failed to send " << desc << std::endl;
    perror("");
    close(s_server_socket);
    return false;
  }
  return true;
}

static bool do_srv_recv(void *target, size_t size, const char *desc) {
  ssize_t rcvd = recv(s_server_socket, target, size, 0);
  if (rcvd <= 0) {
    std::cerr << "Failed to recv " << desc << ", rcvd: " << rcvd << std::endl;
    perror("");
    close(s_server_socket);
    return false;
  } else if (static_cast<size_t>(rcvd) < size) {
    std::cerr << "Failed to recv size at " << desc << ", got: " << rcvd
              << " expected " << size << '\n';
    perror("");
    close(s_server_socket);
    return false;
  }
  return true;
}

#define MSG_SIZE 16

static bool send_start_msg(uint32_t mod, uint32_t fun, uint32_t call_idx) {
  char message[MSG_SIZE];
  static_assert(sizeof(TAG_START) + sizeof(mod) + sizeof(fun) +
                        sizeof(call_idx) <=
                    MSG_SIZE &&
                sizeof(TAG_START) == 2 && sizeof(mod) == 4 && sizeof(fun) == 4);
  memcpy(message, &TAG_START, sizeof(TAG_START));
  memcpy(message + 2, &mod, sizeof(mod));
  memcpy(message + 6, &fun, sizeof(fun));
  memcpy(message + 10, &call_idx, sizeof(call_idx));
  return do_srv_send(message, sizeof(message), "msg start");
}

// regardless of return type, the target must be freed by caller
static bool request_packet_from_server(uint64_t index, void **target,
                                       uint32_t *packet_size) {
  *target = NULL;
  *packet_size = 0;
  char message[MSG_SIZE];
  static_assert(sizeof(TAG_PKT) == 2 &&
                sizeof(TAG_PKT) + sizeof(index) <= MSG_SIZE);

  memcpy(message, &TAG_PKT, sizeof(TAG_PKT));
  memcpy(message + 2, &index, sizeof(index));
  if (!do_srv_send(message, sizeof(message), "pktrq")) {
    return false;
  }
  size_t pkt_size = 0;
  if (!do_srv_recv(message, sizeof(uint32_t), "pkt sz")) {
    return false;
  }
  pkt_size = static_cast<size_t>(*reinterpret_cast<uint32_t *>(message));
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
  return std::array<uint16_t, ENUM_LEN>{TAG_TIMEOUT, TAG_SGNL, TAG_EXIT,
                                        TAG_PASS,    TAG_EXC,  TAG_FATAL}
      [std::to_underlying(end_type)];
}

static bool send_test_end_message(uint64_t index, EMsgEnd end_type,
                                  int32_t status) {
  uint16_t tag = get_tag(end_type);

  char message[MSG_SIZE];
  static_assert(
      sizeof(TAG_TEST_END) + sizeof(index) + sizeof(tag) + sizeof(status) <=
          MSG_SIZE &&
      sizeof(TAG_TEST_END) == 2 && sizeof(index) == 8 && sizeof(tag) == 2);
  memcpy(message, &TAG_TEST_END, 2);
  memcpy(message + 2, &index, sizeof(index));
  memcpy(message + 10, &tag, sizeof(tag));
  memcpy(message + 12, &status, sizeof(status));
  return do_srv_send(message, sizeof(message), "test end msg");
}

static bool send_finish_message() {
  char message[MSG_SIZE];
  static_assert(MSG_SIZE >= sizeof(TAG_TEST_FINISH));

  memcpy(message, &TAG_TEST_FINISH, sizeof(TAG_TEST_FINISH));
  return do_srv_send(message, sizeof(message), "test finish msg");
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

  void *packet_ptr;
  uint32_t packet_size;
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

static EMsgEnd serve_for_child_until_end(int test_requests_socket, pid_t pid,
                                         int timeout_s, int32_t *status) {
  time_t seconds;
  EMsgEnd result = EMsgEnd::MSG_END_FATAL;
  seconds = time(NULL);
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
          std::cerr << "serve_for_child_until_end: invalid ERequestResult: "
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
      std::cerr << "serve_for_child_until_end: invalid ending ERequestResult: "
                << std::to_underlying(req_result);
      return EMsgEnd::MSG_END_FATAL;
    }
  }
}

static void perform_testing(uint32_t module_id, uint32_t function_id,
                            uint32_t call_idx) {
  if (!connect_to_server(TEST_SERVER_SOCKET_NAME)) {
    std::cerr << "Failed to connect" << std::endl;
    exit(HOOKLIB_EC_CONN);
  }

  if (!send_start_msg(module_id, function_id, call_idx)) {
    std::cerr << "Failed send start message" << std::endl;
    exit(HOOKLIB_EC_START);
  }
  set_fork_flag(); // setting the flag in both parent and the fork should not
                   // matter, this function never returns in the fork's parent
                   // (test coordinator)

  for (uint32_t test_idx = 0; test_idx < test_count(); ++test_idx) {
    int sockets[2];

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == -1) {
      perror("socketpair");
      exit(HOOKLIB_EC_PAIR);
    }
    int child_socket = sockets[1];
    int parent_socket = sockets[0];
    // LLCAP-SERVER <---- UNIX domain socket ----> PARENT
    // <--par_sock]-------[child_sock--> CHILD

    pid_t pid = fork();
    if (pid == 0) {
      // CHILD
      init_packet_socket(child_socket, test_idx);
      // populates "argument packet" that will be used by instrumentation
      if (!receive_packet()) {
        perror("Failed to receive argument packet\n");
        exit(HOOKLIB_EC_RECV_PKT);
      }
      // in child process, return to resume execution (start hijacking)
      return;
    }
    // PARENT
    int status = -1;
    EMsgEnd result = serve_for_child_until_end(
        parent_socket, pid, static_cast<int>(get_test_tout_secs()), &status);
    if (result == EMsgEnd::MSG_END_FATAL) {
      // attempt to provide all the errors
      std::cerr.flush();
      std::cout.flush();
    }
    if (result != EMsgEnd::MSG_END_STATUS &&
        result != EMsgEnd::MSG_END_SIGNAL && result != EMsgEnd::MSG_END_EXC &&
        result != EMsgEnd::MSG_END_PASS) {
      // kill the child on non-exiting result (timeout, error, ...)
      // KILL and STOP cannot be ignored
      kill(pid, SIGSTOP);
    }

    if (!send_test_end_message(test_idx, result, status)) {
      exit(HOOKLIB_EC_TX_END);
    }
  }

  if (!send_finish_message()) {
    exit(HOOKLIB_EC_TX_FIN);
  }

  exit(0);
}

void hook_arg_preamble(uint32_t module_id, uint32_t fn_id) {
  // CONTEXT TO KEEP IN MIND:
  // we just entered an instrumented function
  if (!in_testing_mode()) {
    // we are capturing function arguments, first we inform of the function
    // itself
    push_data(&module_id, sizeof(module_id));
    push_data(&fn_id, sizeof(fn_id));
    // the rest of this function concerns only the testing mode
    return;
  }

  // in testing mode we discriminate based on the function that is under the
  // test if THIS function (the caller of hook_arg_preamble, see context) is the
  // desired one we must furhter determine whether we are in the "right" call
  // (n-th call)
  if (!in_testing_fork() && is_fn_under_test(module_id, fn_id)) {
    // modifies call counter
    register_call();

    // should_hijack_arg becomes true as soon as the coutner updated above
    // indicates that we "should instrument this call"
    if (should_hijack_arg()) {
      perform_testing(module_id, fn_id, get_call_num());
      // PARENT process never returns from the first call to instrumented
      // function CHILD process simply continues execution, should_hijack_arg is
      // used further in the type-hijacking functions
    }
  }
}

int32_t hook_test_is_executing(uint32_t module_id, uint32_t fn_id) {
  // The one and zero result as well as its type is crucial here 
  // as the result is also used in the LLVM IR plugin (on LLVM IR level)
  int32_t res = (in_testing_mode() && in_testing_fork() &&
  is_fn_under_test(module_id, fn_id) ) ? 1 : 0;
  return res;
}

static void hook_test_epilogue_impl(uint32_t module_id, uint32_t fn_id,
                                    bool exception) {
  if (0 == hook_test_is_executing(module_id, fn_id)) {
    return;
  }

  if (!send_test_pass_to_monitor(exception)) {
    perror("signal end to monitor");
  }

  exit(ENDPASS_CODE);
}

// here are all the hooks used by the llvm-pass
// calls to these funcitons are inserted by the instrumentation
// and are called during the various phases

void hook_start(uint32_t module_id, uint32_t fn_id) {
  // called during call tracing
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

// The following function template is the (de)serializing code for "primitive"
// types name - fn name argt - type of the captured argument (by value) argvar -
// a unique name for the scope of the function GENFNDECLTEST merely creates the
// declaration this creates the function name(argt argvar, argt& target, uint32t
// module, uint32t fn)
#define GENFN_TEST_PRIMITIVE(name, argt, argvar)                               \
  GENFNDECLTEST(name, argt, argvar) {                                          \
    if (in_testing_mode()) {                                                   \
      if (!is_fn_under_test((module), (fn))) {                                 \
        goto just_copy_arg;                                                    \
      } else {                                                                 \
        /* Makes sure this is the right call (call order-wise) */              \
        if (!should_hijack_arg()) {                                            \
          goto just_copy_arg;                                                  \
        }                                                                      \
        register_argument();                                                   \
        /* This is where argument replacement happens, we copy data from the   \
        argument packet to the target ptr */                                   \
        if (!consume_bytes_from_packet(sizeof((argvar)), (target))) {          \
          std::cerr << "Failed to get " << sizeof((argvar)) << " bytes"        \
                    << std::endl;                                              \
          perror("");                                                          \
          exit(HOOKLIB_EC_PKT_RD);                                             \
        }                                                                      \
      }                                                                        \
      /* in the testing phase, we do not push_data, we either jumped to        \
      just_copy_arg or we performed argument replacement */                    \
      return;                                                                  \
    }                                                                          \
    /* this part performs argument capture and                                 \
    copies the argument */                                                     \
    push_data(&(argvar), sizeof((argvar)));                                    \
                                                                               \
  just_copy_arg:                                                               \
    *(target) = (argvar);                                                      \
    return;                                                                    \
  }
// as mentioned in llvm-pass, the variations for same-sized primitives
// are redundant at this point, we keep them, however, since they do not add
// that much clutter and may prove useful in the future (to handle specific
// types differently)
GENFN_TEST_PRIMITIVE(hook_float, float, n)
GENFN_TEST_PRIMITIVE(hook_double, double, n)

GENFN_TEST_PRIMITIVE(hook_char, char, c)
GENFN_TEST_PRIMITIVE(hook_uchar, UCHAR, c)
GENFN_TEST_PRIMITIVE(hook_short, short, s)
GENFN_TEST_PRIMITIVE(hook_ushort, USHORT, s)
GENFN_TEST_PRIMITIVE(hook_int32, int, i)
GENFN_TEST_PRIMITIVE(hook_uint32, UINT, i)
GENFN_TEST_PRIMITIVE(hook_int64, LLONG, d)
GENFN_TEST_PRIMITIVE(hook_uint64, ULLONG, d)

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
    register_argument();
    // up tho this point, the logic is the same as with primitive types
    *target = new std::string();
    uint32_t cstr_size = 0;
    uint32_t capacity = 0;

    // this is "our" capture format
    // we "consume" from the packet in the exact same order as we
    // "push" in the argument capture (below) without the TOTAL SIZE of the
    // argument packet
    if (!consume_bytes_from_packet(4, &cstr_size) ||
        !consume_bytes_from_packet(4, &capacity)) {
      perror("strhook terr: size, capacity\n");
      exit(HOOKLIB_EC_PKT_RD);
    }
    if (cstr_size > capacity) {
      perror("strhook terr: invalid size\n");
      exit(HOOKLIB_EC_IMPL);
    }
    (*target)->reserve(capacity);
    (*target)->resize(cstr_size);
    if (!consume_bytes_from_packet(cstr_size, (*target)->data())) {
      perror("strhook terr: string content");
      exit(HOOKLIB_EC_PKT_RD);
    }
    return;
  } else {
    // argument capture
    uint32_t cstring_size = static_cast<uint32_t>(str->size());
    uint32_t capacity = static_cast<uint32_t>(str->capacity());
    uint64_t size = cstring_size + sizeof(capacity) + sizeof(cstring_size);
    if (str->size() > UINT32_MAX) {
      perror("strhook cerr: size error");
      return;
    }

    // we are required to push the TOTAL SIZE of the argument payload first
    // (this corresponds to the semantics of the LLSZ_CUSTOM marker)
    push_data(&size, sizeof(size));
    // the rest of the pushes are up to us
    push_data(&cstring_size, sizeof(cstring_size));
    push_data(&capacity, sizeof(capacity));
    push_data(str->c_str(), cstring_size);
  }
move_string_to_target:
  // implementation detail: it str is an in/out argument, the caller of the
  // instrumented function will not
  *target = str;
}
