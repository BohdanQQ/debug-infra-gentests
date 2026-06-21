#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "3|0|Pass"
echo "$Out" | grep "3|1|Pass"
echo "$Out" | grep "3|2|Pass"
echo "$Out" | grep "3|3|Pass"
