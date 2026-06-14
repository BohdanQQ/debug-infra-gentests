#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "1|0|Exit(63)"
echo "$Out" | grep "1|1|Exit(12)"
echo "$Out" | grep "1|2|Exit(88)"
echo "$Out" | grep "1|3|Signal(11)"
