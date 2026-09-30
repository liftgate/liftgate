#!/usr/bin/env sh
set -eu

target="deployment/liftgate-control-plane"

gc="$(kubectl -n liftgate-system exec "$target" -c control-plane -- java -Xlog:gc -version 2>&1)"
printf '%s\n' "$gc" | grep -q 'Using G1' || { printf '%s\n' "$gc"; echo "FAIL: the control-plane JVM options do not select G1"; exit 1; }
echo "the control-plane JVM options select G1"

kubectl -n liftgate-system exec "$target" -c control-plane -- grep -q netty_transport_native_epoll /proc/1/maps || { echo "FAIL: the control plane serves HTTP without Netty's native epoll transport"; exit 1; }
echo "the control plane loaded Netty's native epoll transport"
