#ifndef LLCAP_HOOKLIB
#define LLCAP_HOOKLIB

#ifdef __cplusplus
#include <cstdint>
#include <string>

// makes the library's linkage simpler in the LLVM IR modification phase (in the
// LLVM plugin) however, I am not sure how exactly the C++ function
// llcap_hooklib_extra_cxx_string compiles agains C code (technically a call to
// this function should be impossible in a correct instrumentation, but I am
// unsure)
extern "C" {

static_assert(sizeof(long long) == 8, "Expecting long long to be 8 bytes");
static_assert(sizeof(int) == 4, "Expecting int to be 4 bytes");
#endif

#define GENFNDECLTEST(name, argt, argvar)                                      \
  void name(argt argvar, argt *target, uint32_t module, uint32_t fn)

/*
Hook function for function tracing.

Sends module id and function id to the llcap-server.
*/
void hook_start(uint32_t module_id, uint32_t id);

/*
An argument tracing hook.

Called first during argument capture or testing mode inside instrumented
function. Ensures correct dispatch according to the test parameters.
*/
void hook_arg_preamble(uint32_t module_id, uint32_t fn_id);

/*
Whether the specified function is being tested **right now**.
*/
bool hook_test_is_executing(uint32_t module_id, uint32_t fn_id);

/*
A testing hook.

Called before every return from a function. In testing mode, inside a testing
fork (child), signals back to the test driver that the test is done (passed).
*/
void hook_test_epilogue(uint32_t module_id, uint32_t fn_id);
// see hook_test_epilogue, except this function is called before resuming
// exception unwind
void hook_test_epilogue_exc(uint32_t module_id, uint32_t fn_id);

GENFNDECLTEST(hook_int32, int, a);

#define LLONG long long // to avoid spaces in a macro
GENFNDECLTEST(hook_int64, LLONG, a);

GENFNDECLTEST(hook_float, float, a);
GENFNDECLTEST(hook_double, double, a);

GENFNDECLTEST(hook_short, short, a);
GENFNDECLTEST(hook_char, char, a);

#define UCHAR unsigned char
#define USHORT unsigned short
#define UINT unsigned int
#define ULLONG unsigned long long

GENFNDECLTEST(hook_uchar, UCHAR, a);
GENFNDECLTEST(hook_ushort, USHORT, a);
GENFNDECLTEST(hook_uint32, UINT, a);
GENFNDECLTEST(hook_uint64, ULLONG, a);

// C++ types
#ifdef __cplusplus
void llcap_hooklib_extra_cxx_string(std::string *str, std::string **target,
                                    uint32_t module, uint32_t function);
}
#endif

#endif // LLCAP_HOOKLIB
