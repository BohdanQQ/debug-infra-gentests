#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "4|0|Exit(0)"
echo "$Out" | grep "4|1|Exit(0)"
echo "$Out" | grep "4|2|Exit(0)"
echo "$Out" | grep "4|3|Pass"
