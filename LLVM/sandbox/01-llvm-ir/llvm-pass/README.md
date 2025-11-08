# `llvm-pass`

This LLVM pass plugin performs instrumentation of the target program. To build, run `cmake ./ && make`.

## File Structure (`src` directory)

* [`pass.cpp`](./src/pass.cpp) - the registration of the passes
  * `namespace args` declares command line arguments
  * `InstrumentationPass::run` method delegates instrumentation to the correct code
* [`instrumentation`](./src/instrumentation.hpp) - implements both instrumentation passes
  * relevant methods: `FunctionEntryInstrumentation::instrument` (call tracing), `ArgumentInstrumentation::instrument` (argument capture/testing)
  * simple hook insertion: `insertFnEntryHook`
* [`argMapping`](./src/argMapping.hpp) - metadata parsing and interpretation of Clang-LLVM argument index mapping
* [`modMapping`](./src/modMapping.hpp) - implements module mapping generation (both runtime bookkeeping and encoding into a file)
* [`typeids.h`](./src/typeids.h) - declares constants that are encoded into the module maps file, describing sizes of function arguments ([`llcap-server's documentation`](../../02-ipc/llcap-server/README.md#module-mapping))
* [`typeAlias`](./src/typeAlias.hpp), [`constants`](./src/constants.hpp) and [`utility/verbosity`](./src/utility.hpp) are tiny files implementing helpers

## Usage

To use the plugin, you have to modify the build flags of your files. This can be done directly ([example](../../02-ipc/example-arg-replacement/build-call-trace.sh)) or via your build toolchain ([example](../../../report/EVALUATION.md#setup)).

The plugin is programmed defensively. For example, the module maps directory should be empty before recompilation of the call tracing phase, as collisions of module file names are considered errors. Beware that the plugin may cause the compilation to fail.

Please check the `namespace args` in [`pass.cpp`](./src/pass.cpp) for arguments and their usage, or refer to the example provided above.

## Debugging tips

Use `-mllvm -print-before-all` to dump IR before each stage.
