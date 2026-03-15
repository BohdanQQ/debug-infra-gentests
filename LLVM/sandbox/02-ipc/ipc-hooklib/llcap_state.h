
#ifndef HOOKLIB_LLCAP_STATE
#define HOOKLIB_LLCAP_STATE
#include "protobuf/proto/main.pb.h"
#ifdef __cplusplus
extern "C" {
#endif
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// implements the "backend" side of our capture/testing stages
// we keep some static data here to provide control-flow guiding
// flags for the functions we insert during instrumentation

// these are sent as an "index" value
// they are "large enough" to never be an index and are used in
// a hacky way to inform the test coordinator that the test has reached
// the epilogue function
constexpr uint64_t HOOKLIB_TESTPASS_VAL =
    0xFFFFFFFFFAAFF00F; // no exception, before ret
constexpr uint64_t HOOKLIB_TESTEXC_VAL =
    0xFFFFFFFFFAAEE00E; // indicates exception handling happening

#ifdef MANUAL_INIT_DEINIT
#define HOOKLIB_CTOR_ATTR
#define HOOKLIB_DTOR_ATTR
int init_finalize_after_crash(const char *full_semaphore, uint32_t buff_count);
#else
// for normal compilation of hooklib, these attributes are set, guaranteeing our
// library gets initialized alternative approach would be lazy initalization (on
// demand as the first instrumented call is performed)
#define HOOKLIB_CTOR_ATTR __attribute__((constructor))
#define HOOKLIB_DTOR_ATTR __attribute__((destructor))
#endif

// initializes hooklib for fully functioning later
int init(void) HOOKLIB_CTOR_ATTR;
// frees hooklib resources acquired
void deinit(void) HOOKLIB_DTOR_ATTR;

// for data capture during call tracing and argument capture, pushes data to the
// buffer towards llcap-server
int push_data(const void *source, uint32_t len);

// if true, we are in a testing mode (i.e. not in call tracing or arg capture
// mode)
bool in_testing_mode(void);
// if true, we are inside a test fork, whether or not argument should be
// replaced, use `should_hijack_arg`
bool in_testing_fork(void);
// set inside the child
void set_fork_flag(void);

// get test timeout in seconds, the forked child should run no longer than this
// amount (with some reasonable poll leeway)
uint16_t get_test_tout_secs(void);
// if true, we are performing the specific call which is requested to have their
// arguments replaced
bool should_hijack_arg(void);
// number of tests to perform (i.e. number of argument packets available)
uint32_t test_count(void);
// the argument packet index that is to be fetched from the llcap-server
// available only in the MT support mode (mt_compat_testing() is true)
uint32_t arg_pkt_index_to_fetch(void);
// if true, the module id and function id of this funciton corresponds to the
// llcap-server's target function
bool is_fn_under_test(uint32_t mod, uint32_t fn);

// returns 1-based index of the current call (being/ that was) executed
uint32_t get_call_num(void);
// registers calls of targeted functions, it is crucial to only register the
// tested function
// (`is_fn_under_test`)
void register_call(void);
// disables hijacking for the rest of the runtime
void disable_hijacking(void);

// indicate test passed to the test monitor (parent)
// `exception` argument indicates whether or not exception handling was taking
// place
bool send_test_pass_to_monitor(bool exception);
// copy specified nr of bytes of the argument packet to the target address
bool consume_bytes_from_packet(size_t bytes, void *target);
const llcaproto::SingleArgVariant *get_next_arg();

// intializes the argument packet, use `consume_bytes_from_packet` to consume
// data from it
bool receive_packet(void);
bool receive_packet_proto(void);
// initialize the socket to the test coordinator (parent)
// stores the descriptor and the packet index that will be requested
void init_packet_socket(int fd, uint64_t request_idx);

// whether we're performing only a single testcase in this process 
// (and thus not performing a fork)
bool mt_compat_testing(void);

// whether a CRIU checkpoint will be performed
bool shall_perform_checkpoint(void);

// performs a transparent CRIU checkpoint and restore
// if not successful, program terminates
bool perform_checkpoint(void);

// initializes argument packet
bool locally_initialize_arg_packet(void* owning_packet, int packet_size);

// obtain the logical ID of this thread
uint64_t get_thread_lid();

#ifdef __cplusplus
}
#endif
#endif // HOOKLIB_LLCAP_STATE
