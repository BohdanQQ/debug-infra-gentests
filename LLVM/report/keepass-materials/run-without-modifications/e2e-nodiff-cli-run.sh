#!/bin/bash
set -eux

path=$(pwd)
fixpath=$path/$1; shift
keepass=$path/$1; shift
pattern=$1; shift

modmaps="$fixpath/modmaps"
rm -rf "$modmaps"

wrap="$keepass/calltrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./calltrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

wrap="$keepass/argtrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./argtrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

wrap="$keepass/fnexit-argtrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./fnexit-argtrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

cp ../../../sandbox/01-llvm-ir/llvm-pass/libfn-pass.so "$fixpath"

cd ../../../sandbox/02-ipc/ipc-hooklib
cmake ./
make -j6
cd -

cp ../../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "$fixpath"
cp ../../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "../../../sandbox/02-ipc/llcap-server"
cp ../../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "$keepass/build/src/cli/"

ls "$fixpath/libmy-hook.so"

mkdir -p "$modmaps"

set +x
echo "cd $keepass/build"
echo "and use the cmake command"
echo "cmake -DWITH_TESTS=OFF -DCMAKE_CXX_COMPILER="$keepass/calltrace-wrapper.sh" ../"
echo "make -j12"
echo "and press ENTER"

read -r TRASH

set -x

cd ../../../sandbox/02-ipc/llcap-server

export LD_LIBRARY_PATH="$fixpath"

echo "N:PasswordGenerator::setLength" |  cargo r --release -- --modmap "$modmaps" trace-calls -o "$fixpath/selection.bin" "$keepass/build/src/cli/keepassxc-cli" generate --length 13

cd -

set +x
echo "call tracing done"
echo "now do (in $keepass/build)"
echo "cmake -DWITH_TESTS=OFF -DCMAKE_CXX_COMPILER=$keepass/argtrace-wrapper.sh ../"
echo "make -j12"
echo "and press ENTER"

read -r TRASH
set -x


cd ../../../sandbox/02-ipc/llcap-server

modmaps="$fixpath/modmaps"
selection="$fixpath/selection.bin"
capture="$fixpath/kpass-trcs-dir"
clibin="$keepass/build/src/cli/keepassxc-cli"

rm -rf "$capture"

# blanket run to create the file structure
cargo r --release -- -vvvv --modmap "$modmaps" capture-args -s "$selection" -o "$capture" "$clibin" generate --length 13

# replace the captured values with the example capture
cd "$path"
captureFile=$(find "$capture" -maxdepth 2 -mindepth 2)
cp ../capture-example.bin "$captureFile"

cd ../../../sandbox/02-ipc/llcap-server
cargo r --release -- -vvvv --modmap "$modmaps" test -s "$selection" -c "$capture" "$clibin" generate --length 13
