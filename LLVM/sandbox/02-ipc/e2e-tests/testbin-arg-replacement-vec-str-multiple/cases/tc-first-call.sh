#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "1|0|Exit(14)"
echo "$Out" | grep "1|1|Exit(14)"
