#!/usr/bin/env sh
set -eu

system=liftgate-system
ns=env-e2e-data
org=00000000-0000-4000-8000-000000000901
project=00000000-0000-4000-8000-000000000902
environment=00000000-0000-4000-8000-000000000903
files=00000000-0000-4000-8000-000000000904
app=00000000-0000-4000-8000-000000000905
token="lg_$(openssl rand -hex 24)"
access="GK$(openssl rand -hex 12)"
secret="$(openssl rand -hex 32)"
work="$(mktemp -d)"
forward=
trap 'kill $forward 2> /dev/null; docker rm -f liftgate-e2e-garage > /dev/null 2>&1; rm -rf "$work"' EXIT

sql() {
  kubectl -n "$system" exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}
api() {
  curl --silent --show-error --output "$work/body" --write-out '%{http_code}' --header "Authorization: Bearer $token" --header "Content-Type: application/json" "$@"
}
expect() {
  status="$(api "$@")"
  test "$status" = "$want" || { echo "FAIL: $* answered $status, expected $want: $(cat "$work/body")"; exit 1; }
}
fail() {
  echo "FAIL: $1"
  kubectl -n "$ns" get pods,pvc,clusters,backups -o wide || true
  kubectl -n "$ns" get events --sort-by=.lastTimestamp | tail -n 30 || true
  kubectl -n "$system" logs deployment/liftgate-control-plane --since=15m | grep -E "Reconciler|WARN|ERROR" | tail -n 30 || true
  exit 1
}
within() {
  limit="$1"
  shift
  start=$(date +%s)
  until "$@"; do
    [ $(($(date +%s) - start)) -lt "$limit" ] || fail "$* did not hold within $limit s"
    sleep 3
  done
  echo "$* after $(($(date +%s) - start)) s"
}
release() {
  id="$(cat /proc/sys/kernel/random/uuid)"
  sql --command "insert into deployments (id, service_id, build_id, status) select '$id', service_id, id, 'pending' from builds where service_id = '$1';
    insert into outbox (subject, payload) values ('liftgate.release.requested', '{\"deploymentId\": \"$id\"}')" > /dev/null
  echo "$id"
}
redeploy() {
  want=201 expect --request POST "http://localhost:8080/api/v1/services/$1/redeploy"
  jq -r .id "$work/body"
}
running() {
  test "$(sql --command "select status from deployments where id = '$1'")" = running
}
ready() {
  want=200 expect "http://localhost:8080/api/v1/environments/$environment/databases"
  test "$(jq -r --arg slug "$1" '.[] | select(.slug == $slug) | .ready' "$work/body")" = true
}
backed_up() {
  want=200 expect "http://localhost:8080/api/v1/databases/$1/backups"
  jq -e 'any(.[]; .phase == "completed")' "$work/body" > /dev/null
}
archived() {
  test "$(kubectl -n "$ns" exec main-1 -c postgres -- psql --username postgres --tuples-only --no-align --command "select last_archived_wal >= '$1' from pg_stat_archiver")" = t
}
gone() {
  ! kubectl get pv -o jsonpath='{range .items[*]}{.spec.claimRef.namespace}{"\n"}{end}' | grep -qx "$ns"
}
in_app() {
  kubectl -n "$ns" exec deploy/app -- sh -c "$1"
}

cat > "$work/garage.toml" <<EOF
metadata_dir = "/var/lib/garage/meta"
data_dir = "/var/lib/garage/data"
db_engine = "sqlite"
replication_factor = 1
rpc_bind_addr = "[::]:3901"
rpc_public_addr = "127.0.0.1:3901"
rpc_secret = "$(openssl rand -hex 32)"

[s3_api]
s3_region = "garage"
api_bind_addr = "[::]:3900"
EOF
chmod 644 "$work/garage.toml"
docker run --detach --name liftgate-e2e-garage --network kind --volume "$work/garage.toml:/etc/garage.toml:ro" \
  --env GARAGE_DEFAULT_ACCESS_KEY="$access" --env GARAGE_DEFAULT_SECRET_KEY="$secret" --env GARAGE_DEFAULT_BUCKET=liftgate-tenants \
  dxflrs/garage:v2.4.1 /garage server --single-node --default-bucket > /dev/null
garage="$(docker inspect --format '{{.NetworkSettings.Networks.kind.IPAddress}}' liftgate-e2e-garage)"
curl --silent --output /dev/null --retry 30 --retry-connrefused --retry-delay 1 "http://$garage:3900"

helm upgrade liftgate charts/liftgate --namespace "$system" --reuse-values \
  --set databases.backup.enabled=true \
  --set databases.backup.endpointUrl="http://$garage:3900" \
  --set databases.backup.destinationPath=s3://liftgate-tenants/ \
  --set databases.backup.accessKeyId="$access" \
  --set databases.backup.secretAccessKey="$secret" \
  --set databases.backup.region=garage \
  --set "databases.backup.allowedEgress[0].cidr=$garage/32" \
  --set "databases.backup.allowedEgress[0].port=3900"
kubectl -n "$system" rollout status deployment/liftgate-control-plane --timeout=10m

sql <<EOF
insert into organizations (id, slug, name, plan) values ('$org', 'e2e-data', 'End to end data', 'data');
insert into memberships (org_id, user_id, role) values ('$org', '00000000-0000-4000-8000-000000000001', 'owner');
insert into github_installations (id, org_id, account_login) values (9, '$org', 'e2e-data');
insert into projects (id, org_id, slug, name, repo_full_name, installation_id) values ('$project', '$org', 'data', 'Data', 'e2e-data/data', 9);
insert into environments (id, project_id, slug, name, kind, branch, namespace) values ('$environment', '$project', 'production', 'Production', 'production', 'main', '$ns');
insert into services (id, environment_id, slug, name, kind, port, cpu_millis, memory_mb, start_command) values
    ('$files', '$environment', 'files', 'Files', 'worker', null, 100, 64, 'trap "exit 0" TERM; sleep 3600 & wait'),
    ('$app', '$environment', 'app', 'App', 'worker', null, 100, 128, 'exec sleep 3600');
insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
    (gen_random_uuid(), '$files', '0000000000000000000000000000000000000000', 'main', 'succeeded', 'busybox:1.36', now(), now()),
    (gen_random_uuid(), '$app', '0000000000000000000000000000000000000000', 'main', 'succeeded', 'postgres:18-alpine', now(), now());
insert into api_tokens (id, org_id, name, token_hash, created_by) values
    (gen_random_uuid(), '$org', 'data', translate(rtrim(encode(sha256('$token'), 'base64'), '='), '+/', '-_'), '00000000-0000-4000-8000-000000000001');
EOF
files_release="$(release "$files")"
app_release="$(release "$app")"

kubectl -n "$system" port-forward service/liftgate-control-plane 8080:8080 > /dev/null &
forward=$!
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 http://localhost:8080/readyz > /dev/null

want=201 expect --request POST --data '{"slug":"main","storageGb":1,"cpuMillis":100,"memoryMb":512}' "http://localhost:8080/api/v1/environments/$environment/databases"
database="$(jq -r .id "$work/body")"
created=$(date +%s)

within 180 running "$files_release"
claim='{"apiVersion":"v1","kind":"PersistentVolumeClaim","metadata":{"name":"e2e"},"spec":{"accessModes":["ReadWriteOnce"],"resources":{"requests":{"storage":"1Gi"}}}}'
if echo "$claim" | kubectl --as="system:serviceaccount:$system:liftgate" --namespace default create --dry-run=server --filename - > "$work/out" 2>&1; then
  fail "the control plane may create a volume claim outside managed namespaces"
fi
grep -qF "ValidatingAdmissionPolicy 'liftgate'" "$work/out" || fail "a volume claim outside managed namespaces was refused for another reason: $(cat "$work/out")"
echo "$claim" | kubectl --as="system:serviceaccount:$system:liftgate" --namespace "$ns" create --dry-run=server --filename - > /dev/null
want=200 expect --request PATCH --data '{"volume":{"mountPath":"/data","sizeGb":1}}' "http://localhost:8080/api/v1/services/$files"
want=422 expect --request PATCH --data '{"replicas":2}' "http://localhost:8080/api/v1/services/$files"
test "$(jq -r .field "$work/body")" = replicas
first="$(redeploy "$files")"
within 180 running "$first"
test "$(kubectl -n "$ns" get deployment files --output jsonpath='{.spec.strategy.type}')" = Recreate
test "$(kubectl -n "$ns" get pvc files-data --output jsonpath='{.status.phase}')" = Bound
kubectl -n "$ns" exec deploy/files -- sh -c 'echo kept > /data/proof'
before="$(kubectl -n "$ns" get pods --selector liftgate.dev/service=files --output name)"
second="$(redeploy "$files")"
within 180 running "$second"
test "$(kubectl -n "$ns" get pods --selector liftgate.dev/service=files --field-selector status.phase=Running --output name)" != "$before"
test "$(kubectl -n "$ns" exec deploy/files -- cat /data/proof)" = kept
echo "a file written before a redeploy is readable after it"
want=200 expect --request PATCH --data '{"volume":{"mountPath":"/data","sizeGb":2}}' "http://localhost:8080/api/v1/services/$files"
grown="$(redeploy "$files")"
within 180 running "$grown"
test "$(kubectl -n "$ns" exec deploy/files -- cat /data/proof)" = kept
class="$(kubectl -n "$ns" get pvc files-data --output jsonpath='{.spec.storageClassName}')"
expands="$(kubectl get storageclass "$class" --output jsonpath='{.allowVolumeExpansion}')"
echo "a volume grown in storage class $class (allowVolumeExpansion ${expands:-unset}) still deploys, with a claim of $(kubectl -n "$ns" get pvc files-data --output jsonpath='{.spec.resources.requests.storage}')"

within 180 ready main
elapsed=$(($(date +%s) - created))
test "$elapsed" -le 180 || fail "database main took $elapsed s to become ready"
echo "database main was ready $elapsed s after it was requested"
test "$(kubectl get namespace "$ns" --output jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}')" = restricted
if kubectl -n "$ns" get events --output jsonpath='{range .items[*]}{.message}{"\n"}{end}' | grep -i "violates PodSecurity"; then fail "a database pod violated the restricted profile"; fi
runtime="$(kubectl -n "$ns" get pod main-1 --output jsonpath='{.spec.runtimeClassName}')"
echo "database pods run under the restricted profile with runtime class ${runtime:-none, so the node's default runtime}"
test "$(kubectl auth can-i get secrets --namespace "$ns" --as="system:serviceaccount:$system:liftgate")" = yes
test "$(kubectl auth can-i get secrets --namespace env-e2e --as="system:serviceaccount:$system:liftgate")" = no || fail "the api reads secrets in an environment without a database"
echo "the api reads secrets only in environments with a database"

within 180 running "$app_release"
want=204 expect --request POST --data "{\"serviceId\":\"$app\"}" "http://localhost:8080/api/v1/databases/$database/links"
linked="$(redeploy "$app")"
within 180 running "$linked"
test "$(kubectl -n "$ns" get deployment app --output jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DATABASE_URL")].valueFrom.secretKeyRef.name}')" = main-app
test "$(in_app 'psql "$DATABASE_URL" --tuples-only --no-align --command "select 1"')" = 1
want=200 expect "http://localhost:8080/api/v1/databases/$database/connection"
test "$(jq -r .uri "$work/body")" = "$(in_app 'printenv DATABASE_URL')"
echo "a linked service runs select 1"

primary="$(kubectl -n "$ns" get service main-rw --output jsonpath='{.spec.clusterIP}')"
pod="$(kubectl -n "$ns" get pod main-1 --output jsonpath='{.status.podIP}')"
for target in "$primary" "$pod"; do
  result="$(kubectl -n env-e2e exec deploy/web -- sh -c 'nc -z -w 3 "$0" 5432 && echo open || echo closed' "$target")"
  test "$result" = closed || fail "a pod in another environment reached $target:5432"
done
echo "a pod in another environment cannot connect"

within 300 backed_up "$database"
echo "the scheduled backup completed"

in_app 'psql "$DATABASE_URL" --quiet --command "create table marks (label text); insert into marks values ('"'"'before'"'"')"'
sleep 2
target="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sleep 2
in_app 'psql "$DATABASE_URL" --quiet --command "insert into marks values ('"'"'after'"'"')"'
wal="$(kubectl -n "$ns" exec main-1 -c postgres -- psql --username postgres --tuples-only --no-align --command 'select pg_walfile_name(pg_switch_wal())')"
within 180 archived "$wal"
want=201 expect --request POST --data "{\"slug\":\"restored\",\"pointInTime\":\"$target\"}" "http://localhost:8080/api/v1/databases/$database/restore"
within 300 ready restored
test "$(kubectl -n "$ns" exec restored-1 -c postgres -- psql --username postgres --dbname app --tuples-only --no-align --command 'select string_agg(label, $$,$$) from marks')" = before
echo "restoring to $target gives the rows written before it and none after"

want=409 expect --request DELETE "http://localhost:8080/api/v1/databases/$database"
want=204 expect --request DELETE "http://localhost:8080/api/v1/services/$files"
within 120 sh -c "! kubectl -n $ns get pvc files-data > /dev/null 2>&1"
echo "deleting a service removes its volume"

test -n "$(kubectl -n "$ns" get pvc --selector cnpg.io/cluster --output name)"
sql --command "delete from environments where id = '$environment'; insert into outbox (subject, payload) values ('liftgate.teardown.requested', '{\"namespace\": \"$ns\"}')" > /dev/null
kubectl wait namespace "$ns" --for=delete --timeout=5m
within 180 gone
echo "deleting the environment removes its clusters and their volumes"
