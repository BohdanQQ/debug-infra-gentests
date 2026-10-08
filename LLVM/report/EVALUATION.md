# Evaluation

We choose to evaluate the tool set on the [`keepassxc`](https://github.com/keepassxreboot/keepassxc) project. Keepassxc is a password manager application written in C++. It is a rather large project built with `cmake` that can be built with `clang`.

## Setup

We cloned the project, commit: `b342be457153aa098c8bbce0f8b0fd3baa8c158f`, and performed the following:

1. fixed paths of the clang plugins and the `hooklib`, we denote this by using a `//FIXPATH` placeholder
2. applied the following changes to the project (we use `//FIXPATH` 5 times here):

```diff
diff --git a/src/CMakeLists.txt b/src/CMakeLists.txt
index 06b3fee2..bb27ec5c 100644
--- a/src/CMakeLists.txt
+++ b/src/CMakeLists.txt
@@ -188,6 +188,9 @@ set(keepassx_SOURCES
 streams/qtiocompressor.cpp
 streams/StoreDataStream.cpp
 streams/SymmetricCipherStream.cpp)
+
+set_source_files_properties(core/PasswordGenerator.cpp COMPILE_FLAGS "${COMPILE_FLAGS} -mllvm -llcap-mapdir=//FIXPATH/modmaps -mllvm -Call -Xclang -load -Xclang //FIXPATH/libfn-pass.so -Xclang -fpass-plugin=//FIXPATH/libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so")
+
 if(APPLE)
 set(keepassx_SOURCES
 ${keepassx_SOURCES}
@@ -343,10 +346,17 @@ configure_file(git-info.h.cmake ${CMAKE_CURRENT_BINARY_DIR}/git-info.h)
 add_library(autotype STATIC ${autotype_SOURCES})
 target_link_libraries(autotype Qt5::Core Qt5::Widgets)
 
+add_library(hooklib SHARED IMPORTED)
+set_target_properties(hooklib PROPERTIES
+IMPORTED_LOCATION //FIXPATH/libmy-hook.so
+)
+
+
 add_library(keepassx_core STATIC ${keepassx_SOURCES})
 
 set_target_properties(keepassx_core PROPERTIES COMPILE_DEFINITIONS KEEPASSX_BUILDING_CORE)
 target_link_libraries(keepassx_core
+        hooklib
 autotype
 ${keepassxcbrowser_LIB}
 ${qrcode_LIB}
@@ -407,7 +417,8 @@ if(WIN32)
 endif()
 
 add_executable(${PROGNAME} WIN32 ${keepassx_SOURCES_MAINEXE} ${WIN32_ProductVersionFiles})
-target_link_libraries(${PROGNAME} keepassx_core)
+
+target_link_libraries(${PROGNAME} keepassx_core hooklib)
 
 set_target_properties(${PROGNAME} PROPERTIES ENABLE_EXPORTS ON)
 
diff --git a/src/autotype/xcb/CMakeLists.txt b/src/autotype/xcb/CMakeLists.txt
index 0704de63..a1bf0d6d 100644
--- a/src/autotype/xcb/CMakeLists.txt
+++ b/src/autotype/xcb/CMakeLists.txt
@@ -3,7 +3,13 @@ include_directories(SYSTEM ${X11_X11_INCLUDE_PATH})
 set(autotype_XCB_SOURCES AutoTypeXCB.cpp)
 
 add_library(keepassxc-autotype-xcb MODULE ${autotype_XCB_SOURCES})
-target_link_libraries(keepassxc-autotype-xcb keepassx_core Qt5::Core Qt5::Widgets Qt5::X11Extras ${X11_X11_LIB} ${X11_Xi_LIB} ${X11_XTest_LIB})
+
+add_library(hooklib SHARED IMPORTED)
+set_target_properties(hooklib PROPERTIES
+IMPORTED_LOCATION //FIXPATH/libmy-hook.so
+)
+
+target_link_libraries(keepassxc-autotype-xcb hooklib keepassx_core Qt5::Core Qt5::Widgets Qt5::X11Extras ${X11_X11_LIB} ${X11_Xi_LIB} ${X11_XTest_LIB})
 install(TARGETS keepassxc-autotype-xcb
 BUNDLE DESTINATION . COMPONENT Runtime
 LIBRARY DESTINATION ${PLUGIN_INSTALL_DIR} COMPONENT Runtime)

```

3. followed the project's [development environment setup](https://github.com/keepassxreboot/keepassxc/wiki/Set-up-Build-Environment-on-Linux)
4. ensured that our patched `clang++` is "installed" (this is only in the case of a clean environment that was set up according to the `keepassxc`'s setup)
5. followed the project's [build instructions](https://github.com/keepassxreboot/keepassxc/blob/develop/INSTALL.md#build-steps) up to (and including) the `cmake` usage
6. run `cmake -DCMAKE_CXX_COMPILER=clang++ -DWITH_TESTS=false ../`
7. run `make`

For the argument tracing instrumentation, we instead use the following `set_source_files_properties`:

```diff
+
+set_source_files_properties(core/PasswordGenerator.cpp COMPILE_FLAGS "${COMPILE_FLAGS} -mllvm -llcap-mapdir=//FIXPATH/modmaps -mllvm -Arg -mllvm -llcap-fn-targets-file=//FIXPATH/selection.bin -Xclang -load -Xclang //FIXPATH/libfn-pass.so -Xclang -fpass-plugin=//FIXPATH/libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so")
+
```

## Running the tool - call tracing + capture

We then executed the `llcap-server` for call tracing and argument capture. Both times, we opened the password generator and interacted with the password length UI element:

```shell
# in sandbox/02-ipc/llcap-server
llcap-server -vvvv --modmap //FIXPATH/modmaps trace-calls -o ./selected-fnis-kpass.bin /path/to/keepassxc/build/src/keepassxc

cp ./selected-fnis-kpass.bin //FIXPATH/selection.bin
```

The tool reported the following:

```
0 - 33152 - randomGen() (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
1 - 1030 - PasswordGenerator::passwordGroups() const (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
2 - 431 - PasswordGenerator::isValid() const (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
3 - 397 - PasswordGenerator::numCharClasses() const (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
4 - 214 - PasswordGenerator::generatePassword() const (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
5 - 211 - PasswordGenerator::setLength(int) (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
6 - 211 - PasswordGenerator::setCharClasses(QFlags<PasswordGenerator::CharClass> const&) (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
7 - 211 - PasswordGenerator::setFlags(QFlags<PasswordGenerator::GeneratorFlag> const&) (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
8 - 194 - PasswordGenerator::setCustomCharacterSet(QString const&) (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
9 - 194 - PasswordGenerator::setExcludedCharacterSet(QString const&) (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
10 - 1 - PasswordGenerator::PasswordGenerator() (module /path/to/keepassxc/src/core/PasswordGenerator.cpp)
Module map summary:
Total Modules loaded: 33
Total Functions loaded: 179
Total traced calls: 36246
Traces originated from 1 modules
```

We selected the `PasswordGenerator::setLength(int)` function (as it is the only valid candidate for the instrumentation) and performed argument capture (again, interacting with the UI element):

```shell
llcap-server  --modmap //FIXPATH/modmaps trace-calls capture-args -s ./selected-fnis-kpass.bin -o ./kpass-trcs-dir /path/to/keepassxc/build/src/keepassxc
```

Then, we inspect the packets:

```shell
llcap-server --  --modmap //FIXPATH/modmaps test -s ./selected-fnis-kpass.bin -c ./kpass-trcs-dir/ --inspect-packets F3199851-01000000
```

With the following result:

```log
P | [inspect_packet] Module: /path/to/keepassxc/src/core/PasswordGenerator.cpp
P | [inspect_packet] Function: PasswordGenerator::setLength(int)
P | [inspect_packet] Packet Description: [Fixed(0), Fixed(4)]
P | [inspect_packet] Packet index: 0
P | [inspect_packet] Raw packet: [20, 0, 0, 0]
P | [inspect_packet] Packet index: 1
P | [inspect_packet] Raw packet: [20, 0, 0, 0]

----------- ...omitted for brevity... -----------

P | [inspect_packet] Raw packet: [32, 0, 0, 0]
P | [inspect_packet] Packet index: 53
P | [inspect_packet] Raw packet: [31, 0, 0, 0]
P | [inspect_packet] Packet index: 54
P | [inspect_packet] Raw packet: [30, 0, 0, 0]
P | [inspect_packet] Packet index: 55
P | [inspect_packet] Raw packet: [40, 0, 0, 0]
P | [inspect_packet] Packet index: 56
P | [inspect_packet] Raw packet: [50, 0, 0, 0]
P | [inspect_packet] Packet index: 57
P | [inspect_packet] Raw packet: [60, 0, 0, 0]
P | [inspect_packet] Packet index: 58
P | [inspect_packet] Raw packet: [70, 0, 0, 0]
P | [inspect_packet] Packet index: 59
P | [inspect_packet] Raw packet: [80, 0, 0, 0]
P | [inspect_packet] Packet index: 60
P | [inspect_packet] Raw packet: [90, 0, 0, 0]
```

Reader shall notice the last few entries - these were created by "clicking" on the right side of the UI slider, incrementing by `10`. The values do correspond to the values set while performing argument capture. The initial recorded values may have been influenced by previous runs of the program.

## Running the tool - testing

Testing a GUI application in the way set up as above can be vague/tricky. Essentially, the tool will be relying on these major properties:

1. the instrumented function gets called at all
2. the fork is successful - forking everything (the GUI library can present a major hurdle here)

We believe, however, that according to the goals of the project (i.e. to create an initial set of tools that allow this kind of testing) the main evaluation criterion here is demonstrating both instrumentation passes can be performed and function.

We shortened the capture trace to the last seven argument packets and ran testing:

```shell
llcap-server -- -vvvv  --modmap //FIXPATH/modmaps test -s ./selected-fnis-kpass.bin -c ./kpass-trcs-dir/ /path/to/keepassxc/build/src/keepassxc
```
 
While the concerns above have not manifested when testing, we encountered a keepass-specific issue
that prevented the execution of more tests. When our tool terminated the tested application, it kept
running some process in the background. This, when attempting to run a test of the second call, prevented the launch of the application (keepass expects only one instance of the program to be running).

Nonetheless, the first call has been instrumented. The application initializes its internal data, calling the function even before we can interact with it. This means we cannot effectively close the application as the GUI is not loaded yet. The results then report test case timeouts as we would
expect.

We attempted to use the `-llcap-instrument-fn-exit` option of the LLVM pass, which terminates the test case automatically when the instrumented function is left. This results in the "pass" test status, indicating that the end of the function has been reached in a short time:

```log
P | [main] ---------------------------------------------------------------
P | [main] Test results (7): 
P | [main] Module ID | Function ID |  Call  | Packet | Result
P | [main]  F3199851 |  01000000 |   1 |   0 | Pass
P | [main]  F3199851 |  01000000 |   1 |   1 | Pass
P | [main]  F3199851 |  01000000 |   1 |   2 | Pass
P | [main]  F3199851 |  01000000 |   1 |   3 | Pass
P | [main]  F3199851 |  01000000 |   1 |   4 | Pass
P | [main]  F3199851 |  01000000 |   1 |   5 | Pass
P | [main]  F3199851 |  01000000 |   1 |   6 | Pass
P | [main] ---------------------------------------------------------------
P | [main] Exiting...
```

## Using `keepassxc-cli`

To avoid the GUI-related hurdles, we can run the `keepassxc-cli` binary compiled along with the `keepassxc` binary. We can supply `generate --length 13` to generate a single 13-character password of the specified length on the standard output. 

Instrumentation of this program should provide observable outcomes **if we use the argument traces recorded from the GUI stage**. This is to have *more than one* argument packet because the CLI version calls `setLength` only once, as expected. 

```shell
# clean up module maps generated by previous instrumentation
rm -r /path/to/keepassxc/build/modmaps/*

# build with -Call

# perform call tracing
cargo r -- --modmap /path/to/keepassxc/build/modmaps/ trace-calls -o ./selected-fnis-kpass.bin /path/to/keepassxc/build/src/cli/keepassxc-cli generate --length 13

# copy recorded traces from the GUI phase to some //TEMP

# replace -Call to -Arg to perform second instrumentation, build keepassxc
# ensure function exit instrumentation is NOT performed

# perform blank argument capture (just to generate proper filesystem structure for the argument traces)
cargo r -- --modmap /path/to/keepassxc/build/modmaps/ capture-args -s ./selected-fnis-kpass.bin -o ./kpass-trcs-dir /path/to/keepassxc/build/src/cli/keepassxc-cli generate --length 13

# replace the trace in the kpass-trcs-dir with the //TEMP trace

# output of the tested program will get redirected to the stdout of llcap-server
cargo r -- --modmap /path/to/keepassxc/build/modmaps test -s ./selected-fnis-kpass.bin -c ./kpass-trcs-dir/ /path/to/keepassxc/build/src/cli/keepassxc-cli generate --length 30
```

With result:

```
P | [main] Verbosity: 0
P | [main] Reading function selection
P | [main] Masking
P | [main] Setting up function packet reader
P | [main] Setting up function packet server
P | [main] Run program for fn m: F3199851 f: 01000000
P | [main] Waiting for jobs to finish...
qvXJdWw2akDAe2Ztist4tfg44nTEUt
nripg5s3n5nYMNneYYtmCrrCbVKoc9ZTRJDLjhwd
QDT7YQoPMbY4j3KNY3vaEXjrtJPLtm3Hxsq45J2KCm3oStD3g4
dTaZrcT9merNvsARc373Nr7QtQCSZcPXnRFAKbiy4NXFnZqVuSihcagdjfdA
9j3JsmxEzq3RnfxT2dMEQwvjTNHWqncdyNu5zgH32ReabDyp4iRqr5aqH7MUnRynufjHmY
K4tLUEDKrsSgzXViemamRwi5AhCM7Wt2YskbPNNhQv4tUUJeRwXJLFj9FQvjYc5ykedtHTHVcnj4bhua
NkUsdqsJDkgpoC734RvCScyHbFDWjvArfUvajf7vNJeCDrE4KwHX4QNUXsTA5tcU9YRwbEXfUPgxqoE2M2j9SLh9mZ
3yMJYtUfz3i5vVhWTrvVp2iC2ia5uE
ygtDWm5J3chowMgJcPFPM5Wr99hwxZ
bnFLagzRS9os5YzHCp5ieZz793CCaY
7bJedxACFFJVHxCtqWDaX2HhMemcxC
FU9kyxrxug22LSEreJXsKPzEf5EMqo
ovVsce3tTtVjbztq7mXaHFfH7QbmC7
P | [main] Waiting for server to exit...
P | [main] ---------------------------------------------------------------
P | [main] Test results (7): 
P | [main] Module ID | Function ID |  Call  | Packet | Result
P | [main]  F3199851 |  01000000   |   1    |   0    | Exit(0)
P | [main]  F3199851 |  01000000   |   1    |   1    | Exit(0)
P | [main]  F3199851 |  01000000   |   1    |   2    | Exit(0)
P | [main]  F3199851 |  01000000   |   1    |   3    | Exit(0)
P | [main]  F3199851 |  01000000   |   1    |   4    | Exit(0)
P | [main]  F3199851 |  01000000   |   1    |   5    | Exit(0)
P | [main]  F3199851 |  01000000   |   1    |   6    | Exit(0)
P | [main] ---------------------------------------------------------------
P | [main] Exiting...
```

We can see that the program output changes correctly. We provided 7 recorded packets; thus, `llcap-server` expects to instrument all 7 of them and launches the program 7 times. Yet, only one call to `setLength` will be performed, and it will fork and replace the call's arguments 7 times. The other executions result in unchanged behavior and no tests will be registered as the 2nd and later call to the target function is never performed.

## Conclusion

### Functionality

The plugin systems seem to function well even in large and complex codebases. Functional issues are reduced to application-specific termination procedures. We verified the functionality on a simple function, but also demonstrated the usefulness of the function exit instrumentation despite providing limited support.

### Usability

While the changes required to make the system functional are small, they require modifying build files for every iteration and instrumentation change. We would ideally only need to "touch" the build files once and perhaps configure the behavior elsewhere. The issue has been appended to the [Future work section](../notes/000-TODOs.md#configurability-and-consistency).

We theorize that adding support for a custom type (e.g., `QString`) would probably require additions to the `hooklib` build process. This class of issues limits the scope of [the proposed future work on making extensions easier](../notes/000-TODOs.md#more-comfortable-extension-of-the-ast-and-ir-plugins) and the theoretical addition of `QString` serves as a concrete example.

### Performance

Due to instrumentation of "every" function, but thanks to IR-level modifications and utilization of a relatively lightweight shared-memory approach, we can reasonably assume a fair but not large performance impact of the `call-tracing` stage. The impact of other stages is much less relevant - the instrumentation targets only the user-selected functions.

#### Build times

All times presented are approximate due to small sample sizes and relatively small differences.

##### Patched LLVM, plugin impact

We built the original program in `53.1s` without instrumentation, while the call tracing instrumented build took `53.3s` for the single `.cpp` file. 

For all `keepassx_SOURCES` without the `core/Tools.cpp` file, it took `55.4s` (creating 170 module-mapping files). The excluded file caused problems due to it being compiled more than once by `cmake`.

##### Unpatched LLVM

Unpatched `clang version 19.1.7` distributed with the author's installation of Fedora compiled the project in `54s`. We deem this comparison unfair because our patched LLVM is most likely compiled with target-specific optimizations. Unpatched recompilation of LLVM compiles the project in `52.3s`.

#### Memory

To be precise, the only issue the current implementation presents is the leakage of memory in the
custom type replacement, as we need to allocate an entirely new object, whose pointer will replace the argument (so far, we only support replacement of values of up to 16 bytes; other arguments must be of the pointer type). At the IR level, we have no lifetime information (the argument can be `in + out`, `const`ness could have been assumed), and thus the replaced value will live forever.
