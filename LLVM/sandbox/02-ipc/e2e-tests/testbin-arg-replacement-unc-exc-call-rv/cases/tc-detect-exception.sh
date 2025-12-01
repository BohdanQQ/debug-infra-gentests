#!/bin/bash
set -e

Out=$(echo "$1" | tail -n+2)

# exception is detected in every call that throws it
# (1st doesnt, the rest only when 5th argument packet is used)
echo "$Out" | grep "1|4|Pass"
echo "$Out" | grep "2|4|Exception"
echo "$Out" | grep "3|4|Exception"
echo "$Out" | grep "4|4|Exception"
echo "$Out" | grep "5|4|Exception"
