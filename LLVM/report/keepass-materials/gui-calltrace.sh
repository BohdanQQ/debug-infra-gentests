#!/bin/bash
set -eux

path=$(pwd)
fixpath=$path/$1; shift
keepass=$path/$1; shift

modmaps="$fixpath/modmaps"
mkdir -p "$modmaps"

cd "$keepass"

git stash
git apply ./calltrace.diff

cd build

cmake -DCMAKE_EXPORT_COMPILE_COMMANDS=ON -DCMAKE_CXX_COMPILER=clang++ -DWITH_TESTS=false ../
make -j $(nproc)

cd "$path"

cd ../../sandbox/02-ipc/llcap-server

echo "N:PasswordGenerator::setLength" |  cargo r --release -- -vvvv --modmap "$modmaps" trace-calls -o "$fixpath/selection.bin" "$keepass/build/src/keepassxc"

cd -
