#!/bin/sh
set -e

mkdir build
cd build
cmake \
    -G Ninja ../llvm-project/llvm \
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
    -DLLVM_ENABLE_PROJECTS="clang;llvm" \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLVM_BUILD_TESTS=ON \
    -DLLVM_TARGETS_TO_BUILD=host \
    -DLLVM_INSTALL_UTILS=ON \
    -DLLVM_ENABLE_DUMP=ON \
    -DLLVM_ENABLE_ASSERTIONS=ON \
    -DLLVM_BUILD_TOOLS=OFF \
    -DLLVM_BUILD_EXAMPLES=OFF \
    -DCLANG_PLUGIN_SUPPORT=ON \
    -DCLANG_INCLUDE_TESTS=ON \
    -DCLANG_BUILD_EXAMPLES=ON \
    -DLLVM_USE_LINKER=lld

# slow incremental build might also need
# DBUILD_SHARED_LIBS=ON
cd ../
