#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

for check in e2e/checks/*.sh; do
  echo "==> $check"
  sh "$check"
done
