#!/usr/bin/env sh
set -eu

ns=env-e2e-b
sql() {
  kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align --command "$1"
}
plan() {
  sql "update organizations set plan = '$1' where slug = 'e2e-b'"
  sql "insert into outbox (subject, payload) values ('liftgate.org.plan.changed', '{\"orgId\": \"00000000-0000-4000-8000-000000000010\"}')"
  for attempt in $(seq 60); do
    test "$(kubectl -n $ns get resourcequota liftgate -o jsonpath='{.spec.hard.pods}')" = "$2" && break
    sleep 2
  done
  expect "pods quota on the $1 plan" "$(kubectl -n $ns get resourcequota liftgate -o jsonpath='{.spec.hard.pods}')" "$2"
  kubectl -n $ns rollout status deployment/web --timeout=5m
}
expect() {
  test "$2" = "$3" || { echo "FAIL: $1 is '$2', expected '$3'"; exit 1; }
  echo "$1: $2"
}
dns() { kubectl -n "$1" exec deploy/web -- sh -c 'timeout 15 nslookup "$0" $1 > /dev/null 2>&1 && echo resolved || echo blocked' "$2" "${3:-}"; }
tcp() { kubectl -n "$1" exec deploy/web -- sh -c 'nc -z -w 5 "$0" "$1" && echo open || echo closed' "$2" "$3"; }

expect "udp dns to 1.1.1.1 on the unlimited plan" "$(dns $ns example.com 1.1.1.1)" resolved

plan free 12
expect "egress bandwidth" "$(kubectl -n $ns get deployment/web -o jsonpath='{.spec.template.metadata.annotations.kubernetes\.io/egress-bandwidth}')" 20M
expect "cpu request" "$(kubectl -n $ns get deployment/web -o jsonpath='{.spec.template.spec.containers[0].resources.requests.cpu}')" 25m
expect "udp dns to 1.1.1.1 on the free plan" "$(dns $ns example.com 1.1.1.1)" blocked
expect "cluster dns on the free plan" "$(dns $ns kubernetes.default.svc.cluster.local)" resolved
expect "tcp 443 to 1.1.1.1 on the free plan" "$(tcp $ns 1.1.1.1 443)" open
expect "udp dns to 1.1.1.1 from another org" "$(dns env-e2e example.com 1.1.1.1)" resolved

plan default ""
expect "udp dns to 1.1.1.1 back on the unlimited plan" "$(dns $ns example.com 1.1.1.1)" resolved
