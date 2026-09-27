#!/usr/bin/env sh
set -eu

sql() {
  kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align --command "$1"
}

sql "insert into outbox (subject, payload, published_at) select 'liftgate.usage.recorded', '{\"records\": 0}', now() - interval '8 days' from generate_series(1, 10000)"
kubectl -n liftgate-system rollout restart deployment/liftgate-control-plane
kubectl -n liftgate-system rollout status deployment/liftgate-control-plane --timeout=5m

for attempt in $(seq 60); do
  expired="$(sql "select count(*) from outbox where published_at < now() - interval '7 days'")"
  echo "outbox rows past retention: $expired"
  test "$expired" = 0 && exit 0
  sleep 2
done
exit 1
