#!/bin/bash

cd ../../sandbox/02-ipc/ipc-finalizer
./build.sh

./ipc-fin /llcap-capture-base-semfull 10

cd ../llcap-server

cargo r -- --modmap /home/root --cleanup trace-calls
