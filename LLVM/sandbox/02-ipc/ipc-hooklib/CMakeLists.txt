set(CMAKE_C_STANDARD 23)
set(CMAKE_CXX_STANDARD 23) # can be lowered to 20, but since this project requires the patched LLVM which is relatively new, we use the latest standard

set(CMAKE_C_COMPILER "clang")
set(CMAKE_CXX_COMPILER "clang++")
option(CFG_MANUAL "Library compiled without auto initialization and deinitialization")


cmake_minimum_required(VERSION 3.30)
project(my-hook)


add_compile_options(-Wall -Wextra -Wconversion -Wpedantic -Wmost)

find_package(Protobuf 3 REQUIRED)
include_directories(${PROTOBUF_INCLUDE_DIRS} ./)

# for a more thorough audit, you can use

# add_compile_options(-Weverything 
  # with some selection of ignored warnings
#-Wno-declaration-after-statement -Wno-pre-c23-compat -Wno-c++98-compat -Wno-pre-c11-compat -Wno-global-constructors -Wno-c++98-compat-pedantic -Wno-covered-switch-default -Wno-padded)

add_library(my-hook SHARED ./hook.cpp ./llcap_state.c ./shm_write_channel.c ./shm_util.c ./shm_oneshot_rx.c ./protobuf/proto/main.pb.cc)

set_target_properties(my-hook PROPERTIES C_STANDARD 23)
set_target_properties(my-hook PROPERTIES CXX_STANDARD 23)

if(CFG_MANUAL)
add_library(my-hook-manual SHARED ./hook.cpp ./llcap_state.c ./shm_write_channel.c ./shm_util.c ./shm_oneshot_rx.c ./protobuf/proto/main.pb.cc)
set_target_properties(my-hook-manual PROPERTIES COMPILE_DEFINITIONS "MANUAL_INIT_DEINIT=1")
endif()
