# `hooklib`

This library allows the instrumented program to communicate with the [`llcap-server`](../llcap-server/). We inject calls to this library into the target program via the [`llvm-pass`](../../01-llvm-ir/llvm-pass/). The library is primarily written in C++ but should be linkable to a C binary.

## Prerequisites

Protocol Buffers - `protoc`, `protobuf-devel` (tested with `libprotoc 3.19.6`), `criu`

## Build

Building should be as simple as running 
    
    protoc --cpp_out=./ ./protobuf/proto/main.proto
    cmake ./ -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && make

## Terminology

* see the [project's concepts](../../../README.md#concepts)
* a *channel* refers to a pair of semaphores that guard and synchronize access to a shared memory buffer

## Structure

* [`shm_commons.h`](./shm_commons.h) - header used by both this library and the [`llcap-server`](../llcap-server/README.md#comms-parameters-shared-memory-region) to share constants related to their communication protocols
* [`shm_oneshot_rx.c/h`](./shm_oneshot_rx.h) implements a one-shot reader of a shared-memory "channel"
* [`shm_write_channel.c/h](./shm_write_channel.h) implements a writable "channel" (used to [capture](../llcap-server/README.md#capturing-data-from-the-target) data from the target program)
*  [`llcap_state.c/h`](../sandbox/02-ipc/ipc-hooklib//llcap_state.h) implements the "backend" side of the library, managing and querying of capture/testing parameters
* [`hook.cpp`](./hook.cpp), [`hook.h`](./hook.h)
  * main logic of the instrumentation - defines argument hooks as well as core functions driving the testing process
  * For exploration of the project, you can search for the functions declared in the header file in the [`llvm-pass`](../../01-llvm-ir/llvm-pass/) to get a rough idea of the insertion of the call to these functions

Notable code structures:

* `GENFN_TEST_PRIMITIVE` macro ([`hook.cpp`](./hook.cpp)) defining how instrumentation behaves for a fixed-size primitive types
  * pure declarations of hooks are in [`hook.h`](./hook.h)
* `perform_testing` ([`hook.cpp`](./hook.cpp)) which implements the `fork`ing approach for testing
* `oneshot_shm_read`, `send_start_msg`, `request_packet_from_server`, `send_test_end_message`, `send_finish_message` for the core communcation

## Note on an alternative build mode

For the `CFG_MANUAL` CMake parameter, the build process defines the `MANUAL_INIT_DEINIT` macro separate the functionality required by the [`ipc-finalizer`](../ipc-finalizer/). You can safely ignore the `MANUAL_INIT_DEINIT` sections of code.
