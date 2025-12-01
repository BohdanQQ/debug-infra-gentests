#!/bin/bash
set -e

Out=$(echo "$1" | tail -n+2)
# exception is detected in every call that throws it
# we replace 4-th call with index 4 argument (from 5th recorded call)
echo "$Out" | grep "1|4|Pass"
echo "$Out" | grep "2|4|Exception"
echo "$Out" | grep "3|4|Exception"
echo "$Out" | grep "4|4|Exception"
echo "$Out" | grep "5|4|Exception"
