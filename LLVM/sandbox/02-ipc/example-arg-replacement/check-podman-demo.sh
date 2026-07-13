#!/bin/bash

set -ex
# runs the demo inside the container, used to verify built container

LLCSVR="./bin/llcap-server"

./build-arg-trace.sh

cd ../llcap-server/
rm -rf ./arg-traces-dir
rm -rf ./test-outputs

"$LLCSVR" --modmap ../example-arg-replacement/module-maps/ capture-args -s skip -o ./arg-traces-dir ../example-arg-replacement/arg-replacement

mkdir -p ./test-outputs

Output=$("$LLCSVR" --modmap ../example-arg-replacement/module-maps/ test -s skip -c ./arg-traces-dir -o ./test-outputs/ ../example-arg-replacement/arg-replacement)

set +x
Output=$(echo "$Output" | cut -d']' -f 2- | grep ".*|.*|.*" | tr -d '[:blank:]' | tail -n+2)

echo "$Output"

# some basic output check
echo "$Output" |  grep "|1|0|Exit(63)"
echo "$Output" |  grep "|1|1|Exit(12)"
echo "$Output" |  grep "|1|2|Exit(88)"
echo "$Output" |  grep "|1|3|Signal(11)"
set -x
set +e

# cleanup so that container is as small as possible
# capture outputs in llcap-server directory
rm -r ./test-outputs
rm -r ./arg-traces-dir
rm ./selected-fns.bin
rm ./trace.out


cd ../example-arg-replacement/
# artifacts in the binary dir
rm -r ./module-maps
# binaries, make
make clean
rm ./arg-replacement-tracecalls

# cmake artifacts
rm ./Makefile ./cmake_install.cmake
rm -r ./CMakeFiles
rm -r ./CMakeCache.txt
