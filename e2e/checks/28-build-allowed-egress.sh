#!/usr/bin/env sh
set -eu

ns=liftgate-build
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"

allow() {
  helm upgrade liftgate charts/liftgate --namespace liftgate-system --reuse-values --set-json "build.allowedEgressCidrs=$1"
}
cleanup() {
  allow '[]'
  kubectl -n $ns delete pod allowed-egress-probe --ignore-not-found --wait=false
  kubectl -n default delete pod allowed-egress-target --ignore-not-found --wait=false
  docker rm -f allowed-egress-registry > /dev/null
}
trap cleanup EXIT

docker run --detach --name allowed-egress-registry --network kind \
  registry:2.8.3@sha256:a3d8aaa63ed8681a604f1dea0aa03f100d5895b6a58ace528858a7b332415373 > /dev/null
registry="$(docker inspect --format '{{.NetworkSettings.Networks.kind.IPAddress}}' allowed-egress-registry)"
kubectl -n $ns run allowed-egress-probe --image busybox:1.36 --restart Never --command -- sleep 900
kubectl -n default run allowed-egress-target --image busybox:1.36 --restart Never --command -- httpd -f -p 8080
kubectl -n $ns wait pod/allowed-egress-probe --for=condition=Ready --timeout=2m
kubectl -n default wait pod/allowed-egress-target --for=condition=Ready --timeout=2m
pod="$(kubectl -n default get pod allowed-egress-target -o jsonpath='{.status.podIP}')"

expect() {
  test "$2" = "$3" || { echo "FAIL: $1 is '$2', expected '$3'"; exit 1; }
  echo "$1: $2"
}
tcp() { kubectl -n "$1" exec "$2" -- sh -c 'nc -z -w 5 "$0" "$1" && echo open || echo closed' "$3" "$4"; }
build() { tcp $ns allowed-egress-probe "$@"; }
unrestricted() { tcp default allowed-egress-target "$@"; }

expect "unrestricted tcp to the node's api server" "$(unrestricted "$node" 6443)" open
expect "unrestricted tcp to a pod" "$(unrestricted "$pod" 8080)" open
expect "unrestricted tcp to a registry outside the cluster" "$(unrestricted "$registry" 5000)" open
expect "build tcp to the unlisted registry" "$(build "$registry" 5000)" closed

allow "[{\"cidr\":\"$registry/32\",\"ports\":[5000]},{\"cidr\":\"$node/32\",\"ports\":[6443]},{\"cidr\":\"$pod/32\",\"ports\":[8080]}]"
for attempt in $(seq 30); do test "$(build "$registry" 5000)" = open && break; sleep 2; done
expect "build tcp to the listed registry" "$(build "$registry" 5000)" open
expect "build tcp to the listed node" "$(build "$node" 6443)" closed
expect "build tcp to the listed pod" "$(build "$pod" 8080)" closed
