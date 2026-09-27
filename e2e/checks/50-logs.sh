#!/usr/bin/env sh
set -eu

ns=env-e2e-logs
environment=00000000-0000-4000-8000-000000000511
service=00000000-0000-4000-8000-000000000512
rival=00000000-0000-4000-8000-000000000513
token="lg_$(openssl rand -hex 24)"
rival_token="lg_$(openssl rand -hex 24)"
work="$(mktemp -d)"

sql() {
  kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}

digest() {
  printf %s "$1" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '='
}

socket() {
  curl --silent --no-buffer --http1.1 --max-time "$1" --output "$work/$2" \
    --header "Authorization: Bearer $3" --header "Connection: Upgrade" --header "Upgrade: websocket" \
    --header "Sec-WebSocket-Version: 13" --header "Sec-WebSocket-Key: $(openssl rand -base64 16)" \
    "http://localhost:8080/api/v1/logs/services/$service${4:-}" || true
}

fail() {
  echo "FAIL: $1"
  cat -v "$work/$2" | tail -c 2000
  kubectl -n $ns get pods -o wide || true
  kubectl -n liftgate-system logs deployment/liftgate-control-plane --tail=80 || true
  exit 1
}

pods() {
  kubectl -n $ns get pods -l "liftgate.dev/service-id=$service" -o jsonpath='{.items[*].metadata.name}'
}

kubectl -n liftgate-system port-forward service/liftgate-control-plane 8080:8080 > /dev/null &
forward=$!
trap 'kill $forward; rm -rf "$work"' EXIT
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 --output /dev/null http://localhost:8080/readyz

sql <<EOF
insert into users (id, login) values ('$rival', 'e2e-rival');
insert into memberships (org_id, user_id, role) values ('00000000-0000-4000-8000-000000000010', '$rival', 'owner');
insert into api_tokens (id, org_id, name, token_hash, created_by) values
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000002', 'logs', '$(digest "$token")', '00000000-0000-4000-8000-000000000001'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000010', 'rival', '$(digest "$rival_token")', '$rival');
insert into environments (id, project_id, slug, name, kind, branch, namespace) values
    ('$environment', '00000000-0000-4000-8000-000000000003', 'logs', 'Logs', 'preview', 'logs', '$ns');
insert into services (id, environment_id, slug, name, kind, cpu_millis, memory_mb, start_command) values
    ('$service', '$environment', 'printer', 'Printer', 'worker', 100, 64,
     'echo hello-liftgate from \$HOSTNAME; until [ -e /tmp/crash ]; do sleep 1; done; echo crashed \$HOSTNAME; exit 1');
insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
    ('00000000-0000-4000-8000-000000000514', '$service', '0000000000000000000000000000000000000000', 'logs', 'succeeded', 'busybox:1.36', now(), now());
insert into deployments (id, service_id, build_id, status) values
    ('00000000-0000-4000-8000-000000000515', '$service', '00000000-0000-4000-8000-000000000514', 'pending');
insert into outbox (subject, payload) values
    ('liftgate.release.requested', '{"deploymentId": "00000000-0000-4000-8000-000000000515"}');
EOF
kubectl wait namespace/$ns --for=create --timeout=5m
kubectl -n $ns wait deployment/printer --for=create --timeout=5m
kubectl -n $ns rollout status deployment/printer --timeout=5m

binding="$(kubectl -n $ns get rolebinding liftgate-log-reader -o jsonpath='{.roleRef.kind}/{.roleRef.name} {.subjects[0].kind}/{.subjects[0].namespace}/{.subjects[0].name}')"
test "$binding" = "ClusterRole/liftgate-log-reader ServiceAccount/liftgate-system/liftgate" || { echo "FAIL: the log reader binding is '$binding'"; exit 1; }
echo "log reader binding: $binding"

pod="$(pods)"
for attempt in $(seq 60); do
  kubectl -n $ns logs "$pod" 2>/dev/null | grep -q "hello-liftgate from $pod" && break
  sleep 1
done
socket 10 live "$token"
grep -aq "hello-liftgate from $pod" "$work/live" || fail "the service socket did not show the boot line of $pod within 10 s" live
echo "boot line printed before the socket connected arrived within 10 s"

socket 150 follow "$token" &
sleep 5
kubectl -n $ns delete pod "$pod" --wait=false
replacement=""
for attempt in $(seq 60); do
  replacement="$(pods | tr ' ' '\n' | grep -vx "$pod" | head -n 1)" || true
  test -n "$replacement" && grep -aq "hello-liftgate from $replacement" "$work/follow" && break
  sleep 2
done
grep -aq "hello-liftgate from $pod" "$work/follow" || fail "the following socket missed $pod" follow
test -n "$replacement" && grep -aq "hello-liftgate from $replacement" "$work/follow" || fail "the socket did not continue with the replacement of $pod" follow
echo "the stream continued with $replacement"

kubectl -n $ns exec "$replacement" -- touch /tmp/crash
for attempt in $(seq 60); do
  test "$(kubectl -n $ns get pod "$replacement" -o jsonpath='{.status.containerStatuses[0].restartCount}')" -ge 1 2>/dev/null && break
  sleep 2
done
socket 30 previous "$token" "?previous=true"
grep -aq "crashed $replacement" "$work/previous" || fail "?previous=true did not return the crashed container's output" previous
echo "?previous=true returned the crashed container's output"

socket 10 rival "$rival_token"
od -An -v -tx1 "$work/rival" | tr -d ' \n' | grep -q '^88[0-7][0-9a-f]03f0' || fail "a user of another org was not closed with VIOLATED_POLICY" rival
echo "a user of another org was closed with VIOLATED_POLICY"
