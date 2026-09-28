#!/usr/bin/env sh
set -eu

ns=env-e2e-revert
environment=00000000-0000-4000-8000-000000000601
service=00000000-0000-4000-8000-000000000602
v1=00000000-0000-4000-8000-000000000603
v2=00000000-0000-4000-8000-000000000604
legacy=00000000-0000-4000-8000-000000000605
crashing=00000000-0000-4000-8000-000000000606
host=app-revert-e2e.liftgate.app
token="lg_$(openssl rand -hex 24)"
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
codes="$(mktemp)"

sql() {
  kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}

digest() {
  printf %s "$1" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '='
}

api() {
  curl --fail --silent --show-error --header "Authorization: Bearer $token" --header "Content-Type: application/json" "$@"
}

variables() {
  api --request PUT --data "$1" "http://localhost:8080/api/v1/services/$service/env" > /dev/null
}

redeploy() {
  api --request POST "http://localhost:8080/api/v1/services/$service/redeploy" | jq -r .id
}

is() {
  test "$(sql --command "select status from deployments where id = '$1'")" = "$2"
}

serves() {
  test "$(curl --silent --insecure --max-time 5 --resolve "$host:443:$node" "https://$host/")" = "$1"
}

released() {
  test "$(kubectl -n $ns get deployment/app -o jsonpath='{.metadata.labels.liftgate\.dev/deployment} {.spec.template.spec.containers[0].image}')" = "$1 $2"
}

stall() {
  kubectl -n $ns patch deployment/app --type merge --patch '{"spec":{"progressDeadlineSeconds":15}}'
}

settled() {
  test "$(kubectl -n $ns get deployment/app -o jsonpath='{.status.replicas}/{.status.updatedReplicas}/{.status.availableReplicas}')" = 1/1/1
}

replaced() {
  pod="$(kubectl -n $ns get pods -l "liftgate.dev/deployment=$1" -o jsonpath='{.items[*].metadata.name}' | tr ' ' '\n' | grep -vx "$2" | head -n 1)" || true
  test -n "$pod" && kubectl -n $ns wait "pod/$pod" --for=condition=Ready --timeout=5s > /dev/null 2>&1
}

eventually() {
  deadline=$(($(date +%s) + $1))
  shift
  until "$@"; do
    test "$(date +%s)" -lt $deadline || return 1
    sleep 2
  done
}

fail() {
  echo "FAIL: $1"
  kubectl -n $ns get deployments,replicasets,pods -o wide || true
  sql --command "select id, status, error from deployments where service_id = '$service' order by created_at" || true
  kubectl -n liftgate-system logs deployment/liftgate-control-plane --since=10m | grep -E "Reconciler|DeploymentWatcher|WARN|ERROR" | tail -n 40 || true
  exit 1
}

kubectl -n liftgate-system port-forward service/liftgate-control-plane 8080:8080 > /dev/null &
forward=$!
probe=$forward
trap 'kill $forward $probe 2> /dev/null || true; rm -f "$codes"' EXIT
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 --output /dev/null http://localhost:8080/readyz

sql <<EOF
insert into api_tokens (id, org_id, name, token_hash, created_by) values
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000002', 'revert', '$(digest "$token")', '00000000-0000-4000-8000-000000000001');
insert into environments (id, project_id, slug, name, kind, branch, namespace) values
    ('$environment', '00000000-0000-4000-8000-000000000003', 'revert', 'Revert', 'preview', 'revert', '$ns');
insert into services (id, environment_id, slug, name, kind, port, cpu_millis, memory_mb, start_command) values
    ('$service', '$environment', 'app', 'App', 'web', 8080, 100, 64,
     'test -z "\$CRASH" || exit 1; echo "\$GREETING" > /tmp/index.html && exec httpd -f -p 8080 -h /tmp');
insert into domains (id, service_id, hostname, kind, verified_at, certificate_status) values
    ('00000000-0000-4000-8000-000000000607', '$service', '$host', 'platform', now(), 'issued');
insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
    ('$v1', '$service', '1111111111111111111111111111111111111111', 'revert', 'succeeded', 'busybox:1.36', now(), now()),
    ('$v2', '$service', '2222222222222222222222222222222222222222', 'revert', 'succeeded', 'busybox:1.37', now(), now());
insert into deployments (id, service_id, build_id, status) values
    ('$legacy', '$service', '$v1', 'pending');
insert into outbox (subject, payload) values
    ('liftgate.release.requested', '{"deploymentId": "$legacy"}');
EOF
kubectl wait namespace/$ns --for=create --timeout=5m
kubectl -n $ns wait deployment/app --for=create --timeout=5m
kubectl -n $ns rollout status deployment/app --timeout=5m
eventually 120 is $legacy running || fail "the legacy deployment never ran"

variables '[{"name": "GREETING", "value": "v1"}]'
first="$(redeploy)"
eventually 180 is "$first" running || fail "the redeploy $first never ran"
test "$(kubectl -n $ns get deployment/app -o jsonpath='{.spec.template.spec.containers[0].envFrom[0].secretRef.name}')" = "app-env-$(echo "$first" | cut -c1-8)" ||
  fail "the redeploy does not reference its own env secret"
eventually 60 serves v1 || fail "the redeploy did not apply GREETING without a build"
echo "redeploy $first applied the new env from build $v1"

while :; do
  curl --silent --insecure --max-time 5 --output /dev/null --write-out '%{http_code}\n' --resolve "$host:443:$node" "https://$host/" || true
  sleep 0.5
done > "$codes" &
probe=$!
sql <<EOF
insert into deployments (id, service_id, build_id, status, config, env)
    select '$crashing', service_id, '$v2', 'pending', jsonb_set(config, '{startCommand}', '"exit 1"'), env from deployments where id = '$first';
insert into outbox (subject, payload) values
    ('liftgate.release.requested', '{"deploymentId": "$crashing"}');
EOF
eventually 120 released $crashing busybox:1.37 || fail "v2 was never applied"
stall
eventually 180 is $crashing failed || fail "v2 never failed"
failed=$(date +%s)
eventually 60 released "$first" busybox:1.36 || fail "the Deployment is not back on v1 within 60 s of v2 failing"
echo "back on v1 $(($(date +%s) - failed)) s after v2 failed"
error="$(sql --command "select error from deployments where id = '$crashing'")"
case "$error" in *"reverted to $first"*) echo "v2 failed with: $error" ;; *) fail "v2 failed with '$error'" ;; esac
eventually 120 settled || fail "the revert to v1 never finished rolling out"
is "$first" running || fail "v1 is not running after the revert"
kill $probe
sort "$codes" | uniq -c
test -s "$codes" && ! grep -qvx 200 "$codes" || fail "the app did not answer 200 throughout the failed release"

variables '[{"name": "GREETING", "value": "v1"}, {"name": "CRASH", "value": "1"}]'
crash="$(redeploy)"
eventually 120 released "$crash" busybox:1.36 || fail "the crashing env change was never applied"
old="$(kubectl -n $ns get pods -l "liftgate.dev/deployment=$first" -o jsonpath='{.items[0].metadata.name}')"
kubectl -n $ns delete pod "$old"
eventually 120 replaced "$first" "$old" || fail "the pod of v1 that replaced $old never became ready"
test "$(kubectl -n $ns exec "$pod" -- printenv GREETING)" = v1 || fail "$pod lost GREETING"
! kubectl -n $ns exec "$pod" -- printenv CRASH || fail "$pod picked up the crashing env change"
eventually 60 serves v1 || fail "v1 does not serve after its pod was replaced"
echo "$pod replaced $old with the env of $first"

stall
eventually 180 is "$crash" failed || fail "the crashing env change never failed"
eventually 60 released "$first" busybox:1.36 || fail "the crashing env change was not reverted"
eventually 120 settled || fail "the revert of the crashing env change never finished rolling out"
