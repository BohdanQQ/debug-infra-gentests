#!/bin/bash
set -eux

path=$(pwd)
fixpath=$path/$1; shift
findutils=$path/$1; shift
pattern=$1; shift

modmaps="$fixpath/modmaps"
rm -rf "$modmaps"

wrap="$findutils/calltrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./calltrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

wrap="$findutils/argtrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./argtrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

wrap="$findutils/fnexit-argtrace-wrapper.sh"
sed -e "s#//FIXPATH#$fixpath#g" -e "s#//PATTERN#$pattern#g" ./fnexit-argtrace-wrapper.sh.template > "$wrap"
chmod +x "$wrap"

cp ../../sandbox/01-llvm-ir/llvm-pass/libfn-pass.so "$fixpath"

cd ../../sandbox/02-ipc/ipc-hooklib
cmake ./
make -j6
cd -

cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "$fixpath"
cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "../../sandbox/02-ipc/llcap-server"
cp ../../sandbox/02-ipc/ipc-hooklib/libmy-hook.so "$findutils/find/"

ls "$fixpath/libmy-hook.so"

mkdir -p "$modmaps"

set +x
echo "now do the following manually:"
echo "  cd $findutils"
echo "  and use the cmake command"
echo "  ./configure CC=gcc"
echo "  make CC="$findutils/calltrace-wrapper.sh
echo "  and press ENTER"

read -r TRASH

set -x

cd ../../sandbox/02-ipc/llcap-server

export LD_LIBRARY_PATH="$fixpath"

echo "N:inside_dir" |  cargo r --release -- --modmap "$modmaps" trace-calls -o "$fixpath/selection.bin" "$findutils/find/find" /etc/systemd -name '*.conf' -print

cd -

set +x
echo "call tracing done"
echo "now do (in $findutils)"
echo "  ./configure CC=gcc"
echo "  make CC=$findutils/argtrace-wrapper.sh"
echo "  and press ENTER"

read -r TRASH
set -x


cd ../../sandbox/02-ipc/llcap-server

modmaps="$fixpath/modmaps"
selection="$fixpath/selection.bin"
capture="$fixpath/findutils-trcs-dir"
findbin="$findutils/find/find"

rm -rf "$capture"

# blanket run to create the file structure
cargo r --release -- -vvvv --modmap "$modmaps" capture-args -s "$selection" -o "$capture" "$findbin" /etc/systemd -name '*.conf' -print

cargo r --release -- -vvvv --modmap "$modmaps" test -s "$selection" -c "$capture" "$findbin" /etc/systemd -name '*.conf' -print

