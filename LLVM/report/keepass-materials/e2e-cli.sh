#!/bin/bash
set -eux

fixpath=$1; shift
keepass=$1; shift

mkdir -p "$fixpath" && ./make-diffs.sh "$fixpath" "$keepass"

rm -rf "$fixpath/modmaps"

./cli-calltrace.sh "$fixpath" "$keepass"

./cli-test.sh "$fixpath" "$keepass"
