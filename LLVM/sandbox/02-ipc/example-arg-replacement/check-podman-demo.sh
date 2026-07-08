#!/bin/bash

set -ex
# runs the demo inside the container, used to verify built container

LLCSVR="./bin/llcap-server"

./build-call-trace.sh
mv ./arg-replacement ./arg-replacement-tracecalls

cd ../llcap-server/
echo "N:multiply_i_f" | "$LLCSVR" --modmap ../example-arg-replacement/module-maps/         trace-calls -o ./selected-fns.bin ../example-arg-replacement/arg-replacement-tracecalls

cd ../example-arg-replacement/
./build-arg-trace.sh ../llcap-server/selected-fns.bin

cd ../llcap-server/
"$LLCSVR" --modmap ../example-arg-replacement/module-maps/ capture-args -s ./selected-fns.bin -o ./arg-traces-dir ../example-arg-replacement/arg-replacement

mkdir ./test-outputs

Output=$("$LLCSVR" --modmap ../example-arg-replacement/module-maps/ test -s ./selected-fns.bin -c ./arg-traces-dir -o ./test-outputs/ ../example-arg-replacement/arg-replacement)

set +x
Output=$(echo "$Output" | cut -d']' -f 2- | grep ".*|.*|.*" | tr -d '[:blank:]' | tail -n+2)

echo "$Output"

# some basic output check
echo "$Output" |  grep "|1|0|Exit(63)"
echo "$Output" |  grep "|1|1|Exit(12)"
echo "$Output" |  grep "|1|2|Exit(88)"
echo "$Output" |  grep "|1|3|Signal(11)"
set -x

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
