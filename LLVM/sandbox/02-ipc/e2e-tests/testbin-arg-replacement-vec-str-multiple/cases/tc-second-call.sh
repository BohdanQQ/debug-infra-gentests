#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "2|0|Exit(14)"
echo "$Out" | grep "2|1|Exit(21)"
