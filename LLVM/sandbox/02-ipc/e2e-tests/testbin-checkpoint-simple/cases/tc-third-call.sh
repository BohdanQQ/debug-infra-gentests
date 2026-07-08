#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "3|0|Exit(21)"
echo "$Out" | grep "3|1|Exit(44)"
echo "$Out" | grep "3|2|Pass"
