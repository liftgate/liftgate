#!/usr/bin/env sh
set -eu

ns=env-e2e-rollout
org=00000000-0000-4000-8000-000000000701
project=00000000-0000-4000-8000-000000000702
environment=00000000-0000-4000-8000-000000000703
web=00000000-0000-4000-8000-000000000704
crash=00000000-0000-4000-8000-000000000705
cron=00000000-0000-4000-8000-000000000706
flaky=00000000-0000-4000-8000-000000000708
host=web-rollout-e2e.liftgate.app
flaky_host=flaky-rollout-e2e.liftgate.app
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

sql() {
  kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}

release() {
  id="$(cat /proc/sys/kernel/random/uuid)"
  sql --command "insert into deployments (id, service_id, build_id, status) select '$id', service_id, id, 'pending' from builds where service_id = '$1';
    insert into outbox (subject, payload) values ('liftgate.release.requested', '{\"deploymentId\": \"$id\"}')" > /dev/null
  echo "$id"
}

column() {
  sql --command "select $2 from deployments where id = '$1'"
}

fail() {
  echo "FAIL: $1"
  kubectl -n $ns get pods,jobs -o wide || true
  kubectl -n liftgate-system logs deployment/liftgate-control-plane --since=10m | grep -E "PodWatcher|DeploymentWatcher|Reconciler|WARN|ERROR" | tail -n 40 || true
  exit 1
}

get() {
  curl --fail --silent --show-error --insecure --retry 30 --retry-all-errors --retry-delay 2 --resolve "$1:443:$node" "https://$1/"
}

await() {
  start=$(date +%s)
  until [ "$(column "$1" status)" = "$2" ]; do
    [ $(($(date +%s) - start)) -lt "$3" ] || fail "deployment $1 is '$(column "$1" status)' after $3 s, expected $2"
    sleep 1
  done
  echo "deployment $1 is $2 after $(($(date +%s) - start)) s"
}

sql <<EOF
insert into organizations (id, slug, name, plan) values ('$org', 'e2e-rollout', 'End to end rollout', 'rollout');
insert into github_installations (id, org_id, account_login) values (7, '$org', 'e2e-rollout');
insert into projects (id, org_id, slug, name, repo_full_name, installation_id) values ('$project', '$org', 'rollout', 'Rollout', 'e2e-rollout/rollout', 7);
insert into environments (id, project_id, slug, name, kind, branch, namespace) values ('$environment', '$project', 'production', 'Production', 'production', 'main', '$ns');
insert into services (id, environment_id, slug, name, kind, port, replicas, cpu_millis, memory_mb, start_command, health_check_path, cron_schedule) values
    ('$web', '$environment', 'web', 'Web', 'web', 8080, 2, 200, 64, 'echo liftgate > /tmp/index.html && exec httpd -f -p 8080 -h /tmp', '/', null),
    ('$crash', '$environment', 'crash', 'Crash', 'worker', null, 1, 100, 64, 'printf "boom\000" > /dev/termination-log; exit 1', null, null),
    ('$cron', '$environment', 'stuck', 'Stuck', 'cron', null, 1, 100, 64, 'exec sleep 86400', null, '* * * * *'),
    ('$flaky', '$environment', 'flaky', 'Flaky', 'web', 8080, 1, 100, 64, 'echo v1 > /tmp/index.html && exec httpd -f -p 8080 -h /tmp', null, null);
insert into domains (id, service_id, hostname, kind, verified_at, certificate_status) values
    ('00000000-0000-4000-8000-000000000707', '$web', '$host', 'platform', now(), 'issued'),
    ('00000000-0000-4000-8000-000000000709', '$flaky', '$flaky_host', 'platform', now(), 'issued');
insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at)
    select gen_random_uuid(), id, '0000000000000000000000000000000000000000', 'main', 'succeeded', 'busybox:1.36', now(), now()
    from services where environment_id = '$environment';
EOF

await "$(release $web)" running 300
release $cron > /dev/null
previous="$(release $flaky)"
kubectl -n $ns wait httproute/web --for=create --timeout=2m
get "$host" > /dev/null

go="$(command -v go || ls "${RUNNER_TOOL_CACHE:-/opt/hostedtoolcache}"/go/*/x64/bin/go | sort -V | tail -n 1)"
GOBIN="$work" "$go" install github.com/rakyll/hey@v0.1.5
"$work/hey" -z 150s -c 5 -q 10 -host "$host" "https://$node/" > "$work/load" &
load=$!
for round in 1 2 3; do
  await "$(release $web)" running 60
  kubectl -n $ns rollout status deployment/web --timeout=2m
done
kill -0 $load 2> /dev/null || fail "the three redeploys outlasted the 150 s of load"
wait $load
cat "$work/load"
grep -Eq '^ +\[200\]' "$work/load" || fail "no request succeeded"
test -z "$(sed -n '/^Status code distribution:/,/^$/p' "$work/load" | grep -E '^ +\[' | grep -Ev '^ +\[2[0-9][0-9]\]')" || fail "the load saw non-2xx responses during the redeploys"
if grep -q '^Error distribution:' "$work/load"; then fail "the load saw failed requests during the redeploys"; fi
echo "three redeploys under load returned only 2xx responses"

deployment="$(release $crash)"
await "$deployment" failed 60
error="$(column "$deployment" error)"
case "$error" in *"exit code 1: boom") echo "the crashing release failed with: $error" ;; *) fail "the error '$error' does not end with the exit code and the termination message" ;; esac

await "$previous" running 60
get "$flaky_host" | grep -qx v1 || fail "the flaky service does not answer before its crashing release"
sql --command "update services set start_command = 'httpd -f -p 8080 -h /tmp & sleep 6; exit 1' where id = '$flaky'" > /dev/null
await "$(release $flaky)" failed 60
test "$(column "$previous" status)" = running || fail "the previous release of the flaky service is '$(column "$previous" status)', expected running"
get "$flaky_host" | grep -qx v1 || fail "the previous release of the flaky service stopped answering"
echo "a release that crashed after becoming ready failed while the previous release kept serving"

start=$(date +%s)
until kubectl -n $ns get jobs -o jsonpath='{range .items[*]}{.status.conditions[*].reason}{"\n"}{end}' | grep -q DeadlineExceeded; do
  [ $(($(date +%s) - start)) -lt 180 ] || fail "no run of the cron job was stopped at its deadline within 180 s"
  sleep 2
done
echo "a cron run was stopped at its deadline"
start=$(date +%s)
until [ "$(kubectl -n $ns get jobs -o name | wc -l)" -ge 2 ]; do
  [ $(($(date +%s) - start)) -lt 120 ] || fail "the cron job did not run again within 120 s of the stopped run"
  sleep 2
done
kubectl -n $ns get jobs
echo "the cron job ran again after the stopped run"
