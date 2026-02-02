#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2 | cut -d "|" -f 1,4,5,6)
echo "$Out" | grep "0|1|0|Signal(6)"
echo "$Out" | grep "1|1|0|Exit(5)"
echo "$Out" | grep "0|1|1|Signal(6)"
echo "$Out" | grep "1|1|1|Signal(6)"
