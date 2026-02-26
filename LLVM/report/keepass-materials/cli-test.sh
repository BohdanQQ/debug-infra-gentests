#!/bin/bash
set -eux

path=$(pwd)
fixpath=$path/$1; shift
keepass=$path/$1; shift

cd "$keepass"

git stash
git apply ./selection-trace.diff

cd build

cmake -DCMAKE_EXPORT_COMPILE_COMMANDS=ON -DCMAKE_CXX_COMPILER=clang++ -DWITH_TESTS=false ../
make -j $(nproc)

cd "$path"

cd ../../sandbox/02-ipc/llcap-server

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
cp ./capture-example.bin "$captureFile"

cd ../../sandbox/02-ipc/llcap-server
cargo r --release -- --modmap "$modmaps" test -s "$selection" -c "$capture" "$clibin" generate --length 13

