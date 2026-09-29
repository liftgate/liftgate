#!/usr/bin/env sh
set -eu

ns=env-e2e-metrics
environment=00000000-0000-4000-8000-000000000651
service=00000000-0000-4000-8000-000000000652
rival=00000000-0000-4000-8000-000000000653
token="lg_$(openssl rand -hex 24)"
rival_token="lg_$(openssl rand -hex 24)"
work="$(mktemp -d)"

sql() {
  kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}

digest() {
  printf %s "$1" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '='
}

metrics() {
  curl --silent --show-error --output "$work/body" --write-out '%{http_code}' --header "Authorization: Bearer $1" \
    "http://localhost:8080/api/v1/services/$service/metrics$2"
}

kubectl -n liftgate-system port-forward service/liftgate-control-plane 8080:8080 > /dev/null &
forward=$!
trap 'kill $forward; rm -rf "$work"' EXIT
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 --output /dev/null http://localhost:8080/readyz

sql <<EOF
insert into users (id, login) values ('$rival', 'e2e-metrics-rival');
insert into memberships (org_id, user_id, role) values ('00000000-0000-4000-8000-000000000010', '$rival', 'owner');
insert into api_tokens (id, org_id, name, token_hash, created_by) values
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000002', 'metrics', '$(digest "$token")', '00000000-0000-4000-8000-000000000001'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000010', 'metrics-rival', '$(digest "$rival_token")', '$rival');
insert into environments (id, project_id, slug, name, kind, branch, namespace) values
    ('$environment', '00000000-0000-4000-8000-000000000003', 'metrics', 'Metrics', 'preview', 'metrics', '$ns');
insert into services (id, environment_id, slug, name, kind, cpu_millis, memory_mb, start_command) values
    ('$service', '$environment', 'burner', 'Burner', 'worker', 100, 96, 'while :; do :; done');
insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
    ('00000000-0000-4000-8000-000000000654', '$service', '0000000000000000000000000000000000000000', 'metrics', 'succeeded', 'busybox:1.36', now(), now());
insert into deployments (id, service_id, build_id, status) values
    ('00000000-0000-4000-8000-000000000655', '$service', '00000000-0000-4000-8000-000000000654', 'pending');
insert into outbox (subject, payload) values
    ('liftgate.release.requested', '{"deploymentId": "00000000-0000-4000-8000-000000000655"}');
EOF
kubectl wait namespace/$ns --for=create --timeout=5m
kubectl -n $ns wait deployment/burner --for=create --timeout=5m
kubectl -n $ns rollout status deployment/burner --timeout=5m

start=$(date +%s)
until [ "$(metrics "$token" "")" = 200 ] && jq -e '
    (.cpu | map(.value) | max // 0) > 0
    and (.memory | length) > 0 and (.networkRx | length) > 0 and (.networkTx | length) > 0
    and ([.cpu, .memory, .networkRx, .networkTx] | all(length <= 300))
    and .memoryLimitBytes == 96 * 1048576 and .step == 12' "$work/body" > /dev/null; do
  if [ $(($(date +%s) - start)) -ge 300 ]; then
    echo "FAIL: the burner's metrics did not show CPU above 0 with memory and network series within 5 minutes"
    head -c 2000 "$work/body" || true; echo
    kubectl -n $ns get pods -o wide || true
    kubectl -n liftgate-system logs deployment/liftgate-control-plane --since=10m | grep -E "MetricsRoutes|metrics|WARN|ERROR" | tail -n 40 || true
    exit 1
  fi
  sleep 5
done
echo "CPU above 0 after $(($(date +%s) - start)) s: $(jq -c '{cpu: .cpu[-1], memory: .memory[-1], rx: .networkRx[-1], tx: .networkTx[-1], limit: .memoryLimitBytes, restarts: .restarts}' "$work/body")"

test "$(metrics "$token" "?range=7d")" = 200 && jq -e '.step == 2016' "$work/body" > /dev/null || { echo "FAIL: range=7d did not answer with a 2016 s step"; exit 1; }
test "$(metrics "$token" "?range=30d")" = 422 || { echo "FAIL: range=30d was not rejected with 422"; exit 1; }
test "$(metrics "$rival_token" "")" = 403 || { echo "FAIL: another org's member was not refused with 403"; exit 1; }
echo "range=7d answers, range=30d is 422 and another org's member gets 403"
