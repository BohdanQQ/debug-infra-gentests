#!/bin/bash
set -e

Out=$(echo "$1" | tail -n+2)
# we expect the other test case to succeed for this one
PASS_LINES=$(echo "$Out" | grep -v "|4|Exception" | grep "|Pass" | wc -l)
OUT_LINES=$(echo "$Out" | wc -l)
if [ $PASS_LINES -eq $OUT_LINES ]; then
  exit 1
fi
exit 0