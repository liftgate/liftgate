#!/usr/bin/env sh
set -eu

ns=env-e2e-b
host=web-hello-e2e-b.liftgate.app
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"

admin() {
  kubectl -n liftgate-system exec deploy/liftgate-control-plane -- /opt/liftgate/bin/liftgate-control-plane admin "$@"
}
page() {
  curl --fail --silent --show-error --insecure --retry 30 --retry-all-errors --retry-delay 2 --resolve "$host:443:$node" "https://$host/"
}
stopped() {
  test "$(kubectl -n $ns get deployment/web -o jsonpath='{.spec.replicas}')" = 0 &&
    test -z "$(kubectl -n $ns get services,httproutes --output name)" &&
    test "$(curl --silent --insecure --output /dev/null --write-out '%{http_code}' --resolve "$host:443:$node" "https://$host/" || true)" != 200
}

test "$(page)" = liftgate-b
admin suspend e2e-b abuse drill
start=$(date +%s)
until stopped; do
  test $(($(date +%s) - start)) -lt 60 || { echo "FAIL: e2e-b is still served 60 s after admin suspend"; exit 1; }
  sleep 2
done
echo "e2e-b stopped $(($(date +%s) - start)) s after admin suspend"
reason="$(kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --tuples-only --no-align \
  --command "select details->>'reason' from audit_log where action = 'org.suspend' and target_id = '00000000-0000-4000-8000-000000000010' order by id desc limit 1")"
test "$reason" = "abuse drill" || { echo "FAIL: audit_log holds '$reason' as the suspension reason"; exit 1; }

admin unsuspend e2e-b
kubectl -n $ns wait httproute/web --for=create --timeout=2m
kubectl -n $ns rollout status deployment/web --timeout=5m
test "$(page)" = liftgate-b
