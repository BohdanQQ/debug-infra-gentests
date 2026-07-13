#!/bin/sh
set -e

cp ../../01-llvm-ir/llvm-pass/libfn-pass.so ./
cd ../ipc-hooklib
cmake ./ -DCFG_MANUAL=OFF
make
cd ../example-arg-replacement

cmake -D CMAKE_C_COMPILER=clang \
  -D CMAKE_CXX_COMPILER=clang++ \
  ./

# the mllvm args must precede plugin loading

cmake   -D CMAKE_C_COMPILER=clang \
  -D CMAKE_CXX_COMPILER=clang++ \
  -DCMAKE_CXX_FLAGS="-mllvm -llcap-verbose \
  -mllvm -llcap-fn-target-regex=\".*multiply_i_f.*\" \
  -mllvm -llcap-mapdir=./module-maps \
  -mllvm -Arg -Xclang -load -Xclang ./libfn-pass.so -Xclang -fpass-plugin=./libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so"  \
  ./

make clean
make

