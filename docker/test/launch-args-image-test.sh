#!/bin/sh
# Real-image check that a command override reaches LoadMain. Owner: build-engineer.
# Not part of `sbt check`: it needs Docker and a built service image.
# Usage: sh docker/test/launch-args-image-test.sh <image>
# Containers run with --network none and no credentials, so no AWS call can happen.
# LoadMain echoes the arguments it rejects or accepts, which is what is asserted.
set -eu

image=${1:?usage: launch-args-image-test.sh <image>}
loader=scholarchain.storage.loader.LoadMain
run_id=run:0000000000000000000000000000000000000000000000000000000000000000
failures=0

# expect <case name> <exit status> <literal output text> [program args...]
expect() {
  name=$1
  status=$2
  text=$3
  shift 3
  actual=0
  output=$(docker run --rm --network none --read-only --tmpfs /tmp \
    -e SERVICE_MAIN_CLASS="$loader" "$image" "$@" 2>&1) || actual=$?
  if [ "$actual" -eq "$status" ] && printf '%s\n' "$output" | grep -Fq -- "$text"; then
    echo "ok: $name"
  else
    echo "FAIL: $name (exit $actual, wanted $status and: $text)" >&2
    printf '%s\n' "$output" | head -n 5 >&2
    failures=$((failures + 1))
  fi
}

expect "no arguments" 2 "error: --run-id is required"
expect "argument with spaces stays one argument" 2 \
  "error: unknown argument 'two  spaced words'" "two  spaced words"
expect "empty argument is delivered" 2 "error: unknown argument ''" ""
expect "empty value is delivered in place" 2 "error: --snapshot is required" \
  --run-id "$run_id" --spark-output /tmp --snapshot ""
expect "value with spaces is delivered whole" 2 \
  "error: --spark-output /tmp/no such  dir is not a directory" \
  --run-id "$run_id" --snapshot label --dry-run --spark-output "/tmp/no such  dir"

if [ "$failures" -ne 0 ]; then
  echo "launch-args-image-test: $failures failure(s)" >&2
  exit 1
fi
echo "launch-args-image-test: passed"
