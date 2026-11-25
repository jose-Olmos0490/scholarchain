#!/bin/sh
# Launcher argument contract. Owner: build-engineer. Needs no Docker, JVM, or AWS.
# Part of `sbt check` (task dockerLauncherCheck).
#
# A container command override is appended to the exec-form ENTRYPOINT, so it
# reaches docker/launch.sh as "$@". This runs launch.sh with a stub `java` on PATH
# and compares, byte for byte, the main class and program arguments the JVM would
# receive. It also checks the Dockerfile properties that this relies on.
set -eu

docker_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Stub JVM: record the main class and everything after it, NUL-terminated.
mkdir "$work/bin"
cat >"$work/bin/java" <<'STUB'
#!/bin/sh
while [ "$#" -gt 0 ] && [ "$1" != "$SERVICE_MAIN_CLASS" ]; do shift; done
for a in "$@"; do printf '%s\0' "$a"; done >"$ARGV_FILE"
STUB
chmod +x "$work/bin/java"

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

# expect <case name> <main class> [program args...]
expect() {
  name=$1
  main=$2
  shift 2
  : >"$work/actual"
  for a in "$main" "$@"; do printf '%s\0' "$a"; done >"$work/expected"
  if ! PATH="$work/bin:$PATH" ARGV_FILE="$work/actual" SERVICE_MAIN_CLASS="$main" \
    sh "$docker_dir/launch.sh" "$@"; then
    fail "$name: launch.sh exited non-zero"
  elif cmp -s "$work/expected" "$work/actual"; then
    echo "ok: $name"
  else
    fail "$name"
    echo "  expected: $(tr '\0' '|' <"$work/expected")" >&2
    echo "  actual:   $(tr '\0' '|' <"$work/actual")" >&2
  fi
}

# No override: each service gets its main class and no program arguments.
for svc in QueryApiMain GraphEvidenceApiMain EmbeddingWorkerMain; do
  expect "no arguments, $svc" "scholarchain.services.entrypoint.$svc"
done

loader=scholarchain.storage.loader.LoadMain
expect "loader override" "$loader" --targets neptune --run-id run:abc --dry-run
expect "arguments with spaces" "$loader" \
  --spark-output "/tmp/dir with  two spaces" --snapshot " padded " "--work-dir=/tmp/a b"
expect "single empty argument" "$loader" ""
expect "empty arguments in every position" "$loader" "" --snapshot "" --dry-run ""
# shellcheck disable=SC2016 # the unexpanded text is the point
expect "shell metacharacters stay literal" "$loader" \
  '*' '/app/*' '$HOME' '$(id)' 'a;b' "it's" '"quoted"' 'back\slash' 'line one
line two'

if (unset SERVICE_MAIN_CLASS; PATH="$work/bin:$PATH" ARGV_FILE="$work/actual" \
  sh "$docker_dir/launch.sh" --dry-run) >/dev/null 2>&1; then
  fail "unset SERVICE_MAIN_CLASS must stop the launcher"
else
  echo "ok: unset SERVICE_MAIN_CLASS stops the launcher"
fi

# Only an exec-form ENTRYPOINT receives an appended command, and a CMD would hand
# the services default arguments.
dockerfile="$docker_dir/Dockerfile.service"
if grep -qx 'ENTRYPOINT \["/app/bin/launch.sh"\]' "$dockerfile"; then
  echo "ok: ENTRYPOINT is the launcher in exec form"
else
  fail "ENTRYPOINT must be exec-form [\"/app/bin/launch.sh\"]"
fi
if grep -Eiq '^CMD([[:space:]]|\[)' "$dockerfile"; then
  fail "Dockerfile.service must not set CMD"
else
  echo "ok: no CMD instruction"
fi

if [ "$failures" -ne 0 ]; then
  echo "launch-args-test: $failures failure(s)" >&2
  exit 1
fi
echo "launch-args-test: passed"
