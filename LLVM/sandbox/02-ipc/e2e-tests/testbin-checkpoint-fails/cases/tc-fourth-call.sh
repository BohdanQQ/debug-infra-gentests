#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "4|0|Exit(21)"
echo "$Out" | grep "4|1|Exit(3)"
echo "$Out" | grep "4|2|Exit(44)"
echo "$Out" | grep "4|3|Pass"
