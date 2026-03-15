
#ifndef LLCAP_SHM_COMMONS
#define LLCAP_SHM_COMMONS

#ifdef __cplusplus
static_assert(sizeof(unsigned int) == 4, "expected size of u32");
static_assert(sizeof(unsigned short) == 2, "expected size of u16");
#endif

// NOLINTBEGIN(modernize-use-using)
typedef struct {
  unsigned int buff_count;
  unsigned int buff_len;
  unsigned int total_len;
  // the above required for call tracing and argument capture
  
  // false if zero, indicates whether we are in capture mode or not
  unsigned int mode; // required for argument capture and testing, 0 for call
                     // tracing, 1 for capture, 2 for testing, 3 for nofork testing (multithreading support) and test_count is used as the packet index to be requested
  // the below is required for only the testing phase
  // identifier of function under test
  unsigned int target_fnid;
  unsigned int target_modid;
  // false if zero, indicates whether we are inside a forked process - and
  // should prevent further forking when instrumented code (preamble) is reached
  // multiple times
  unsigned int forked;
  // number of tests to be performed (number of forks to perform)
  // or (if mode == 3 - MT support), the index of the packet to be requested from the llcap-server
  unsigned int test_count;
  // the number of the call of the target function to instrument
  // utitlized by decrementing this value on each call -> equality to 1
  // means the current call shall be instrumented
  // the number passed here should be "intended call 0-based index" + 2!
  unsigned int target_call_number;
  unsigned short test_timeout_seconds;
  // number of items in the thread-counting list
  unsigned int thread_count;
  // logical ID of the thread to be tested
  unsigned int target_thread_lid;
  const char* checkpoint_dump_dir;
  const char* checkpoint_id;
} ShmMeta;
// NOLINTEND(modernize-use-using)

static const unsigned long CLI_MSG_SIZE = 24;

// message types the test coordinator sends to the llcap-server
static const unsigned short TAG_START = 0;
static const unsigned short TAG_PKT = 1;
static const unsigned short TAG_TEST_END = 2;
static const unsigned short TAG_TEST_FINISH = 3;

// test end results
static const unsigned short TAG_EXC     = 13; // exception
static const unsigned short TAG_PASS    = 14; // pass (if function end is instrumented)
static const unsigned short TAG_TIMEOUT = 15; // test case timeout (not the global timeout)
static const unsigned short TAG_EXIT    = 16; // test exited (function end not instrumented)
static const unsigned short TAG_SGNL    = 17; // test terminated with a signal
static const unsigned short TAG_FATAL   = 18; // fatal test failure, indicates an error
// the TAG_EXIT, TAG_TIMEOUT, TAG_SGNL can also appear as results when function end is instrumented
// this happens when an unsupported exception flow has been reached
// for support of exception flow, refer to the llvm-pass

// names of the shared resources - semaphores, shared memory, sockets (used by hooklib and llcap-server)
static const char *const META_SEM_DATA = "/llcap-meta-sem-data";
static const char *const META_SEM_ACK = "/llcap-meta-sem-ack";
static const char *const META_MEM_NAME = "/llcap-meta-shmem";
static const char *const META_MEM_SIZE_NAME = "/llcap-meta-shmem-size";
static const char *const TEST_SERVER_SOCKET_NAME = "/tmp/llcap-test-server";
static const char* const CRIU_SOCKET_PATH = "/tmp/llcap-criu-socket.service";

#endif // LLCAP_SHM_COMMONS
