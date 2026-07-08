#!/bin/bash

set -e

Out=$(echo "$1" | tail -n+2)
echo "$Out" | grep "1|0|Pass"
echo "$Out" | grep "1|1|Pass"
echo "$Out" | grep "1|2|Pass"
echo "$Out" | grep "1|3|Signal(11)"
