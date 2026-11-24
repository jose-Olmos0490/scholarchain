#!/bin/sh
# Liveness probe: GET /healthz on the service's own port (curl ships in the base image).
exec curl --fail --silent --show-error --max-time 3 -o /dev/null \
  "http://127.0.0.1:${HEALTH_PORT:?}/healthz"
