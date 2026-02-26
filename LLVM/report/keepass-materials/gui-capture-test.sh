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
kpassbin="$keepass/build/src/keepassxc"

rm -rf "$capture"

cargo r --release -- -vvvv --modmap "$modmaps" capture-args -s "$selection" -o "$capture" "$kpassbin"

cargo r --release -- -vvvv --modmap "$modmaps" test -s "$selection" -c "$capture" "$kpassbin"

