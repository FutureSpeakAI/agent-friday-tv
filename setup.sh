#!/bin/sh
# Agent Friday TV setup for macOS and Linux: ./setup.sh  (arguments are passed on)
set -e
cd "$(dirname "$0")/wizard"
for p in python3 python; do
  if command -v "$p" >/dev/null 2>&1 && "$p" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)'; then
    exec "$p" -m fridaytv_setup "$@"
  fi
done
echo "Python 3.9 or later is needed: https://www.python.org/downloads/"
exit 1
