#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "2|0|Pass"
echo "$Out" | grep "2|1|Pass"
echo "$Out" | grep "2|2|Pass"
