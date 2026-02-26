#!/bin/bash
set -eux

path=$(pwd)
fixpath=$path/$1; shift
keepass=$path/$1; shift

sed -e "s#//FIXPATH#$fixpath#g" ./calltrace.diff.template > "$keepass/calltrace.diff"
sed -e "s#//FIXPATH#$fixpath#g" ./selection-trace.diff.template > "$keepass/selection-trace.diff"
sed -e "s#//FIXPATH#$fixpath#g" ./selection-trace-fnexit.diff.template > "$keepass/selection-trace-fnexit.diff"

cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "$fixpath"
cp ../../sandbox/01-llvm-ir/llvm-pass/libfn-pass.so "$fixpath"
