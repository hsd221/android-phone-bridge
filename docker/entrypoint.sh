#!/bin/sh
set -eu
if [ "${1:-}" = "serve" ]; then
  node /app/dist/container-init.js
fi
exec node /app/dist/cli.js "$@"
