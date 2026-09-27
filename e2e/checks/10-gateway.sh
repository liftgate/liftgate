#!/usr/bin/env sh
set -eu

node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
test "$(curl --fail --silent --show-error --insecure --retry 30 --retry-all-errors --retry-delay 2 \
  --resolve "web-hello-e2e.liftgate.app:443:$node" https://web-hello-e2e.liftgate.app)" = liftgate
