#!/bin/bash
set -eux

path=$(pwd)
fixpath=$path/$1; shift
yaga=$path/$1; shift
pattern=$1; shift

modmaps="$fixpath/modmaps"
rm -rf "$modmaps"

wrap="$yaga/calltrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./calltrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

wrap="$yaga/argtrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./argtrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

wrap="$yaga/fnexit-argtrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./fnexit-argtrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

cp ../../sandbox/01-llvm-ir/llvm-pass/libfn-pass.so "$fixpath"

cd ../../sandbox/02-ipc/ipc-hooklib
cmake ./
make -j6
cd -

cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "$fixpath"
cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "../../sandbox/02-ipc/llcap-server"
cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "$yaga/build-release"

ls "$fixpath/libmy-hook.so"

mkdir -p "$modmaps"

set +x
echo "now do the following manually:"
echo "  cd $yaga"
echo "  rm -rf build-release (if needed)"
echo "  mkdir build-release"
echo "  cd build-release"
echo "  and use the cmake command"
echo "  cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER="$yaga/calltrace-wrapper.sh" .."
echo "  make -j3"
echo "  and press ENTER"

read -r TRASH

set -x

cd ../../sandbox/02-ipc/llcap-server

export LD_LIBRARY_PATH="$fixpath"

echo "N:yaga::Model_base::is_defined" |  cargo r --release -- --modmap "$modmaps" trace-calls -o "$fixpath/selection.bin" "$yaga/build-release/smt" $path/input.smt

cd -

set +x
echo "call tracing done"
echo "now do (in $yaga)"
echo "  rm -rf build-release (if needed)"
echo "  mkdir build-release"
echo "  cd build-release"
echo "  cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=$yaga/argtrace-wrapper.sh .."
echo "  make -j3"
echo "  and press ENTER"

read -r TRASH
set -x


cd ../../sandbox/02-ipc/llcap-server

modmaps="$fixpath/modmaps"
selection="$fixpath/selection.bin"
capture="$fixpath/kpass-trcs-dir"
yagabin="$yaga/build-release/smt"

rm -rf "$capture"

# blanket run to create the file structure
cargo r --release -- -vvvv --modmap "$modmaps" capture-args -s "$selection" -o "$capture" "$yagabin" $path/input.smt

cargo r --release -- -vvvv --modmap "$modmaps" test -s "$selection" -c "$capture" "$yagabin" $path/input.smt

