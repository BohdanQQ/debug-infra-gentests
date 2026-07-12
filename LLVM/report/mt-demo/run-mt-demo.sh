#!/bin/sh

set -eux

rm -rf ./result/modmaps
rm -rf ./result/traces
mkdir -p ./result/modmaps

cp ../../sandbox/01-llvm-ir/llvm-pass/libfn-pass.so ./
cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so ./
cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so ./result/


here=$(pwd)

clang++ -mllvm -llcap-verbose\
  -mllvm -llcap-mapdir=./result/modmaps\
  -mllvm -llcap-fn-target-regex=".*do_work.*"\
  -mllvm -Arg\
  -mllvm -llcap-instrument-fn-exit\
  -Xclang -load -Xclang ./libfn-pass.so\
  -Xclang -fpass-plugin=./libfn-pass.so\
  -fplugin=/usr/local/lib/AstMetaAdd.so\
  -L./ -L./result -lmy-hook -Wl,-rpath,"$here"\
  ./src/main.cpp -o ./mt-demo


modmaps="$here/result/modmaps"
capture="$here/result/traces"
selection="skip"
binary="$here/mt-demo"

llcap="../../sandbox/02-ipc/llcap-server/target/debug/llcap-server"

$llcap -vvvv --modmap "$modmaps" capture-args -s "$selection" -o "$capture" "$binary" 1 2 2

$llcap -vvvv --modmap "$modmaps" test --mode mt-support -s "$selection" -c "$capture" "$binary" 1 2 2
