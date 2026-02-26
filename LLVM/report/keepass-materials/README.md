# End-to-end demo

Assumes
* built `keepassxc` at least once
* built the repo (`llvm-project`, `llvm-pass`, `ipc-hooklib`)

**assuming pwd is this file's parent**
**assuming keepassxc is at the relative path ../../../../keepassxc**


## How to run ("no modifications" to the build system)

```shell
cd ./run-without-modifications
mkdir -p tmp-tracing
./e2e-nodiff-cli-run.sh ./tmp-tracing ../../../../../../keepassxc "src/core/Pass"
```

Note that the discriminating pattern `"src/core/Pass"` is required (otherwise you will only encounter a limitation - file path hash collisions). This pattern matches against all the arguments passed to the build system. Thus, if you're tinkering, ensure it is concrete enough.

This was added as a workaround since proper hash collision checking runs into issues due to compile process parallelization.

The process creates 2 scripts that instrument files (selected by the pattern above) for call tracing and argument capture/testing by adding compile flags and executing `clang++` with the extended arguments. The scripts are then used (via cmake) in the place of the "C++ compiler".

## How to run (cli version with example captured data)

```shell
./e2e-cli.sh ./tmp-tracing ../../../../keepassxc
```

## How to run (GUI available)

In this directory:

```shell
mkdir -p ./tmp-tracing && ./make-diffs.sh ./tmp-tracing ../../../../keepassxc

rm -rf ./tmp-tracing/modmaps

./cli-calltrace.sh ./tmp-tracing ../../../../keepassxc

./cli-test.sh ./tmp-tracing ../../../../keepassxc
```


make sure to click the password generator and play around with it
* note: too much input data will cause the testing phase to take too long

```shell
mkdir ./tmp-tracing && ./make-diffs.sh ./tmp-tracing ../../../../keepassxc

./gui-calltrace.sh ./tmp-tracing ../../../../keepassxc

./gui-capture-test.sh ./tmp-tracing ../../../../keepassxc
```

### Other useful commands

* cleanup: `./manual-flush.sh`

* when keepass complains that only one instance can be running:
  
  killall -r ".\*keepassxc.\*" -s KILL

* removing `keepassxc/build` and rebuilding

