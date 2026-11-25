#!/bin/sh
# Container entrypoint. Replaces itself with the JVM (exec) so the JVM is PID 1 and
# SIGTERM reaches the application's own shutdown handling. Heap is a percentage of
# the container memory limit; there is no fixed -Xmx. GC is left to JVM ergonomics
# (Serial for small containers, G1 otherwise). Extra flags may come in through
# JAVA_TOOL_OPTIONS, set by the deployment.
# Program arguments: a container command override is appended to the ENTRYPOINT and
# is passed on unchanged to the main class. With no override there are none.
set -eu
exec java \
  -XX:+UseContainerSupport \
  -XX:InitialRAMPercentage=25.0 \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.io.tmpdir=/tmp \
  -cp "/app/lib/*" \
  "${SERVICE_MAIN_CLASS:?SERVICE_MAIN_CLASS is not set}" \
  "$@"
