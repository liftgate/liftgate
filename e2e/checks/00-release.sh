#!/usr/bin/env sh
set -eu

kubectl -n liftgate-system port-forward service/liftgate-control-plane 8080:8080 > /dev/null &
trap "kill $!" EXIT
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 http://localhost:8080/readyz
curl --fail --silent --show-error http://localhost:8080/metrics > /dev/null
test "$(curl --silent --output /dev/null --write-out '%{http_code}' http://localhost:8080/api/v1/me)" = 401

kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 < e2e/fixture.sql
kubectl -n env-e2e wait deployment/web --for=create --timeout=5m
kubectl -n env-e2e rollout status deployment/web --timeout=5m
kubectl -n env-e2e get service/web httproute/web
kubectl -n env-e2e get networkpolicy

for attempt in $(seq 30); do
  status="$(kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --tuples-only --no-align --command "select status from deployments where id = '00000000-0000-4000-8000-000000000008'")"
  echo "deployment status: $status"
  test "$status" = running && exit 0
  sleep 5
done
exit 1
