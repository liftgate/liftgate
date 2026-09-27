#!/usr/bin/env sh
set -eu

ns=liftgate-system
values="$(mktemp)"
trap 'rm -f "$values"' EXIT

helm get values liftgate --namespace "$ns" --output yaml > "$values"
helm uninstall liftgate --namespace "$ns" --wait --timeout 10m
kubectl -n "$ns" get cluster/liftgate-postgres objectstore/liftgate-postgres pvc/liftgate-postgres-1 secret/liftgate-postgres-app

helm install liftgate charts/liftgate --namespace "$ns" --values "$values"
kubectl -n "$ns" rollout status deployment/liftgate-control-plane --timeout=10m
test "$(kubectl -n "$ns" exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --tuples-only --no-align --command "select count(*) from projects where slug = 'hello'")" = 1
