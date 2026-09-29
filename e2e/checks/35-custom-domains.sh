#!/usr/bin/env sh
set -eu

host=shop.example.com
domain=00000000-0000-4000-8000-000000000701
service=00000000-0000-4000-8000-000000000005
certificate="certificates.cert-manager.io/$host"
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"

sql() {
  kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}

listener() {
  kubectl -n liftgate-system get gateway liftgate -o jsonpath="{.spec.listeners[?(@.name==\"domain-$domain\")].hostname}"
}

status() {
  sql --command "select certificate_status from domains where id = '$domain'"
}

reroute() {
  sql --command "insert into outbox (subject, payload) values ('liftgate.domain.verify.requested', '{\"domainId\": \"$domain\", \"serviceId\": \"$service\"}')"
}

within() {
  start=$(date +%s)
  until eval "$2"; do
    test $(($(date +%s) - start)) -lt "$1"
    sleep 2
  done
}

sql --command "insert into domains (id, service_id, hostname, kind, verification_token, verified_at) values ('$domain', '$service', '$host', 'custom', 'e2e', now())"
reroute
kubectl -n liftgate-system wait "$certificate" --for=create --timeout=3m
kubectl -n liftgate-system wait "$certificate" --for=condition=Ready --timeout=3m
within 60 'test "$(status)" = ready'
test "$(listener)" = "$host"
test "$(curl --fail --silent --show-error --insecure --retry 30 --retry-all-errors --retry-delay 2 --resolve "$host:443:$node" "https://$host")" = liftgate

kubectl -n liftgate-system patch gateway liftgate --type merge --patch \
  "$(kubectl -n liftgate-system get gateway liftgate -o json | jq -c '{spec: {listeners: (.spec.listeners | map(select(.name | startswith("domain-") | not)))}}')"
within 60 'test "$(listener)" = "$host"'

sql --command "delete from domains where id = '$domain'"
reroute
kubectl -n liftgate-system wait "$certificate" --for=delete --timeout=2m
within 60 'test -z "$(listener)"'
