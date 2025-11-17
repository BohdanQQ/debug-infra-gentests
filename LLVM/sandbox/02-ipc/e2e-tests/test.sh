#!/bin/sh
set -e

# Args: <tested binary directory> <function name> <timeout in seconds> <output-testing script/directory> <clang args>

# debugging: set to -v -vv -vvv or ... to adjust llcap-server verbosity
LlcapVerbosity=""

# give names to arguments, navigate to the correct working directory (of the binary we will be instrumenting) 
WorkingDir=$1; shift
TestedFnName=$1; shift
TimeoutSec=$1; shift
OutputTestScriptDir=$1; shift
IRTestScriptDir=$1; shift
LlcapBufSz=$1; shift
LlcapBufCnt=$1; shift
CppArgs=$*;

cd "$WorkingDir"
WorkingDir=$(pwd)
BuildDir="$WorkingDir"/build
OutputsDir="$WorkingDir"/out

# "testbin" name should be hardcoded into the cmakelists.txt file
LlcapSvrBin="$WorkingDir/../../llcap-server/target/debug/llcap-server"
InstrumentedBin="$BuildDir"/testbin
ModMapsPath="$BuildDir"/module-maps/

# build hooklib
cd ../../ipc-hooklib

cmake ./ -DCFG_MANUAL=OFF
make

# build the first instrumentation stage
rm -rf "$BuildDir"
mkdir "$BuildDir"
cd "$BuildDir"

# ! assume llvm pass to be built 
# (avoiding rebuild, because this is quite a long compilation)
cp ../../../../01-llvm-ir/llvm-pass/libfn-pass.so "$BuildDir"

if [ -d "$IRTestScriptDir" ];
then
  # TODO: make this a local function (build dir + cmake args?)
  # testing IR - create a twin build directory
  # where we only generate IR and inspect it

  TmpBuildDir="$BuildDir"/../build-ir-test
  rm -rf "$TmpBuildDir"
  mkdir "$TmpBuildDir"

  cp ../../../../01-llvm-ir/llvm-pass/libfn-pass.so "$TmpBuildDir"
  cd "$TmpBuildDir"

  
  cmake -D CMAKE_C_COMPILER=clang \
  -D CMAKE_CXX_COMPILER=clang++ \
  ../

  cmake   -D CMAKE_C_COMPILER=clang \
  -D CMAKE_C_FLAGS="-mllvm -Call -mllvm -llcap-mapdir=./mmaps -Xclang -load -Xclang ./libfn-pass.so -Xclang -fpass-plugin=../libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so" \
  -D CMAKE_CXX_COMPILER=clang++ \
  -D CMAKE_CXX_FLAGS="-mllvm -Call -mllvm -llcap-mapdir=./mmaps -Xclang -load -Xclang ./libfn-pass.so -Xclang -fpass-plugin=./libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so -S -emit-llvm" \
  ../
 
  # initialize directory for llvm pass artifacts
  mkdir "./mmaps"
  make clean
  make

  echo "pre-testing LLVM IR $IRTestScriptDir"
  for File in "$IRTestScriptDir"/ir-*.sh; do
    echo "LLVM IR Test $File"
    set -e
    "$File" "$TmpBuildDir"
    set +e
  done
  set -e
  cd "$BuildDir"
fi

cmake -D CMAKE_C_COMPILER=clang \
  -D CMAKE_CXX_COMPILER=clang++ \
  ../

cmake -D CMAKE_C_COMPILER=clang \
  -D CMAKE_C_FLAGS="-mllvm -Call -Xclang -load -Xclang ./libfn-pass.so -Xclang -fpass-plugin=./libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so" \
  -D CMAKE_CXX_COMPILER=clang++ \
  -D CMAKE_CXX_FLAGS="-mllvm -Call -mllvm -llcap-verbose -Xclang -load -Xclang ./libfn-pass.so -Xclang -fpass-plugin=./libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so" \
  ../

# re-initialize artifact directories
mkdir -p "$OutputsDir"
# initialize directory for llvm pass artifacts
mkdir "$ModMapsPath"

make clean
make

SelectionPath="$OutputsDir"/selected-fns.bin

echo "!!! Tracing"
echo "N:$TestedFnName" | "$LlcapSvrBin" -s "$LlcapBufSz" -c "$LlcapBufCnt" --modmap "$ModMapsPath" trace-calls -o "$SelectionPath" "$InstrumentedBin"

# rebuild for the second instrumentation stage
echo "!!! Rebuilding"

cmake -D CMAKE_C_COMPILER=clang \
  -D CMAKE_CXX_COMPILER=clang++ \
  ../

cmake   -D CMAKE_C_COMPILER=clang \
  -D CMAKE_C_FLAGS="$CppArgs -mllvm -llcap-verbose -mllvm -Arg -mllvm -llcap-fn-targets-file=$SelectionPath -Xclang -load -Xclang ./libfn-pass.so -Xclang -fpass-plugin=./libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so"  \
  -D CMAKE_CXX_COMPILER=clang++ \
  -D CMAKE_CXX_FLAGS="$CppArgs -mllvm -llcap-verbose -mllvm -Arg -mllvm -llcap-fn-targets-file=$SelectionPath -Xclang -load -Xclang ./libfn-pass.so -Xclang -fpass-plugin=./libfn-pass.so -fplugin=/usr/local/lib/AstMetaAdd.so"  \
  ../

make clean
make

ArgTraceDir="$OutputsDir"/arg-traces-dir
TestOutputsDir="$OutputsDir"/test-outputs

echo "!!! Capturing"

rm -rf "$ArgTraceDir" && "$LlcapSvrBin" $LlcapVerbosity -s "$LlcapBufSz" -c "$LlcapBufCnt" --modmap "$ModMapsPath"\
 capture-args -s "$SelectionPath" -o "$ArgTraceDir" "$InstrumentedBin"

echo "!!! Testing"

mkdir -p "$TestOutputsDir"

Output=$(rm -rf "${TestOutputsDir:?}"/* && "$LlcapSvrBin" $LlcapVerbosity -s "$LlcapBufSz" -c "$LlcapBufCnt" --modmap "$ModMapsPath"\
 test -s "$SelectionPath" -t "$TimeoutSec" -c "$ArgTraceDir"\
 -o "$TestOutputsDir" "$InstrumentedBin")

if [[ $LLCAP_ECHO_OUT ]]; then
  echo "$Output"
fi

# transform output to a |-separated table with a single-line header
# ModuleID|FunctionID|Call|Packet|Result
Output=$(echo "$Output" | cut -d']' -f 2- | grep ".*|.*|.*" | tr -d '[:blank:]')

if [[ "$OutputTestScriptDir" != "" ]]
then
  echo "!!! Testing Outputs"
  
  # testing for a fatal erorr first, allow the lookup to fail
  set +e
  echo "$Output" | tail -n+2 | grep "Fatal"
  if [[ "$?" == 0 ]]
  then
    echo "$Output"
    exit 1;
  fi
  set -e

  # go through all files and execute them
  if [ -d "$OutputTestScriptDir" ];
  then
    for File in "$OutputTestScriptDir"/tc-*.sh; do
      echo "Test $File"
      # never redirect this, do not touch this line
      "$File" "$Output"
      echo "OK"
    done
  else
    "$OutputTestScriptDir" "$Output"
  fi
else
  echo "!!! Done"
  echo "$Output"
fi
