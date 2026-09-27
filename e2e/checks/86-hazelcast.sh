#!/usr/bin/env sh
set -eu

ns=liftgate-system

profile() {
  helm upgrade liftgate charts/liftgate --namespace $ns --reuse-values --set profile="$1"
  kubectl -n $ns rollout status deployment/"$2" --timeout=10m
}

expect() {
  for attempt in $(seq 90); do
    members="$(kubectl get --raw "/api/v1/namespaces/$ns/pods/$1:8080/proxy/metrics" | awk '$1 == "liftgate_hazelcast_members" { print $2 + 0 }')" || true
    test "$members" = "$2" && { echo "$1: $members Hazelcast members"; return; }
    sleep 5
  done
  kubectl -n $ns logs "$1" --tail=100
  echo "FAIL: $1 reports '$members' Hazelcast members, expected $2"
  exit 1
}

profile ha liftgate-api
replicas="$(kubectl -n $ns get deployment liftgate-api -o jsonpath='{.spec.replicas}')"
for pod in $(kubectl -n $ns get pods -l liftgate.dev/role=api -o jsonpath='{.items[*].metadata.name}'); do
  expect "$pod" "$replicas"
done
profile single liftgate-control-plane
