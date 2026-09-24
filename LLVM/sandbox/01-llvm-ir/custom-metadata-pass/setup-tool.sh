#!/bin/sh
set -e

# expects the llvm-project submodule fully built according tho the root README

# a dumb check
LlvmPath=../../llvm-project
TargetToolFolder=$LlvmPath/clang/examples/ast-meta-add
if [ -d "$TargetToolFolder" ]; then
  echo "Directory $TargetToolFolder already exists. This script should be run only once..."
  exit 1
fi

mkdir "$TargetToolFolder"
cp -rL ./ast-meta-add/* "$TargetToolFolder"

# this is why you should not run this more than once - to undo this, simply undo this append
echo "add_subdirectory(ast-meta-add)" >> "$LlvmPath/clang/examples/CMakeLists.txt"

echo "You may run ninja in ../../build now"
echo "If you run into a Permission Denied to a file AstMetaAdd.exports when building the plugin, run ninja with root permissions (sorry, I have no idea why this error happens)"
