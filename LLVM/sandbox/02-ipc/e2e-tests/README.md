# End-to-end testing environment

End-to-end testing is implemented here. The `test.sh` script builds specified binary and traces it completely. The testing part is not yet implemented (planned: basic pattern matching on testing stage output).

It requires the following arguments: `<tested binary directory> <substring of a function name> <timeout in seconds> "" <additional clang arguments>`. (notice the `""`).

The test script performs tracing of all functions but tests only one of them, the very first one in the selection list `llcap-server` provides. Thus, for now, the tested binary shall ensure that it calls the desired function the most times out of the entire binary.

If any part of the test fails or is terminated unexpectedly and `llcap-server` is terminated forcefully, the `llcap-cleanup.sh` performs the necessary cleanup.

For each test, artifacts are stored in the `out` and `build` directories.

## Running "all" tests


then:

```bash
cd ./test 
./run-all-tests.sh
# or for CRIU-based tests:
sudo ./run-all-tests.sh criu
```

For CRIU support, run everyting as root and initialize the CRIU socket:

```bash
sudo criu service -vvvv --address /tmp/llcap-criu-socket.service
```


## How it works

`run-all-tests.sh` refers to test directories that provide small C/C++ applications that are going to be tested. The line

    run-test "testbin-arg-replacement-simple" "timeout-all.sh" "test_target" 0 "$Size" "$Count"

runs the `testbin-arg-replacement-simple` test, using the `timeout-all.sh` script to check the outputs of the run with timeout `0`s. The `$Size` and `$Count` parameters guide the `llcap-server`'s buffer parameters for IPC.

The line

    run-test-in-directory-custom-buffers "testbin-arg-replacement-simple" "test_target" 5 "$Size" "$Count"

follows the same pattern as the previous one, with one exception: All the output checking scripts are now looked up inside the `testbin-arg-replacement-simple` directory.

In each `testbin-*` directory, the scripts look for the `cases` folder, which contains check scripts (named `tc-*.sh`). These receive "clean" test output and check it.

The "clean" test output is created by reducing the `llcap-server` output - stripping white spaces and unnecessary lines, ... See the (bottom of the) [`test.sh` file for implementation](./test.sh) for details. For a reference check script, head [here](./testbin-arg-replacement-structret-simple/cases/tc-first-call.sh) and compare it with [`llcap-server` outputs](../example-arg-replacement/README.md#primitive-type-instrumentation). It is expected that the check script will fail to indicate a test failure. 

Optionally, the (call-tracing) instrumentation can be checked by inspecting the LLVM IR (folder `cases/ir`, scripts `ir-*.sh`). Again, depending on the script's exit code, the entire test fails or testing continues.
