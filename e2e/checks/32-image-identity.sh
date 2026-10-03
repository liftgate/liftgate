#!/usr/bin/env sh
set -eu

ns=liftgate-build
system=liftgate-system
tenant=env-e2e
node=liftgate-control-plane
address=$(kubectl get service kubernetes -o jsonpath='{.spec.clusterIP}' | sed 's/[0-9]*$/50/')
registry=$address:5000
service=00000000-0000-4000-8000-000000000331
pusher=00000000-0000-4000-8000-000000000332
first=00000000-0000-4000-8000-000000000333
second=00000000-0000-4000-8000-000000000334
legacy=00000000-0000-4000-8000-000000000335
repository=e2e/hello/production/identity
commit=$(openssl rand -hex 20)
image=$registry/$repository:$commit
password=$(openssl rand -hex 16)
token="lg_$(openssl rand -hex 24)"
forward=
work=$(mktemp -d)

. e2e/registry.sh

report() {
  code=$?
  if [ "$code" -ne 0 ]; then
    kubectl -n $tenant get pods -l liftgate.dev/service=identity -o wide || true
    kubectl -n $tenant describe pods -l liftgate.dev/service=identity || true
    for pod in identity-first identity-second; do kubectl -n $ns logs "$pod" --all-containers --prefix --tail=40 || true; done
    sql --command "select id, build_id, status, error from deployments where service_id = '$service' order by created_at" || true
    kubectl -n $system logs deployment/liftgate-control-plane -c control-plane --tail=80 || true
  fi
  [ -z "$forward" ] || kill "$forward" 2> /dev/null || true
  rm -rf "$work"
  exit "$code"
}
trap report EXIT

expect() {
  [ "$2" = "$3" ] || { echo "FAIL: $1 is '$2', expected '$3'" >&2; exit 1; }
  echo "$1: $2"
}

sql() {
  kubectl -n $system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}

hashed() {
  printf %s "$1" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '='
}

built() {
  printf 'FROM busybox:1.36\nRUN mkdir /www && echo %s > /www/index.html\nCMD ["httpd", "-f", "-p", "8080", "-h", "/www"]\n' "$1" > "$work/$1"
  kubectl -n $ns create configmap "identity-$1" --from-file=Dockerfile="$work/$1" > /dev/null
  build "identity-$1" "$pusher" "$password" $repository "$commit" > /dev/null
  expect "build of the $1 image" "$(finished "identity-$1")" Succeeded > /dev/null
  digest=$(kubectl -n $ns get pod "identity-$1" -o jsonpath='{.status.containerStatuses[0].state.terminated.message}')
  echo "$digest" | grep -Eqx 'sha256:[0-9a-f]{64}' || { echo "FAIL: the $1 build reported '$digest' instead of a digest" >&2; exit 1; }
  echo "$digest"
}

release() {
  id=$(cat /proc/sys/kernel/random/uuid)
  sql --command "insert into deployments (id, service_id, build_id, status) values ('$id', '$service', '$1', 'pending');
    insert into outbox (subject, payload) values ('liftgate.release.requested', '{\"deploymentId\": \"$id\"}')" > /dev/null
  echo "$id"
}

runs() {
  for attempt in $(seq 90); do
    [ "$(sql --command "select status from deployments where id = '$1'")" = running ] && break
    sleep 2
  done
  expect "status of deployment $1" "$(sql --command "select status from deployments where id = '$1'")" running
  pod=$(kubectl -n $tenant get pods -l "liftgate.dev/deployment=$1" --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
  expect "image and pull policy of $pod" "$(kubectl -n $tenant get pod "$pod" -o jsonpath='{.spec.containers[0].image} {.spec.containers[0].imagePullPolicy}')" "$2"
  pulled=$(kubectl -n $tenant get pod "$pod" -o jsonpath='{.status.containerStatuses[0].imageID}')
  expect "digest $pod runs" "${pulled##*@}" "$3"
  expect "page $pod serves" "$(kubectl -n $tenant exec "$pod" -- cat /www/index.html)" "$4"
}

cached() {
  docker exec $node crictl inspecti -o json "$image" | jq -r '[.status.repoDigests[] | sub(".*@"; "")] | join(" ")'
}

sql <<EOF > /dev/null
insert into services (id, environment_id, slug, name, kind, port, cpu_millis, memory_mb) values
    ('$service', '00000000-0000-4000-8000-000000000004', 'identity', 'Identity', 'web', 8080, 100, 64);
insert into builds (id, service_id, commit_sha, branch, status, started_at, registry_secret_hash) values
    ('$pusher', '$service', '$commit', 'main', 'running', now(), '$(hashed "$password")');
insert into api_tokens (id, org_id, name, token_hash, created_by) values
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000002', 'identity', '$(hashed "$token")', '00000000-0000-4000-8000-000000000001');
EOF
job $pusher > /dev/null

one=$(built first)
sql --command "insert into builds (id, service_id, commit_sha, branch, status, image_ref, image_digest, started_at, finished_at) values
  ('$first', '$service', '$commit', 'main', 'succeeded', '$image', '$one', now(), now())" > /dev/null
before=$(release $first)
runs "$before" "$registry/$repository@$one IfNotPresent" "$one" first
docker exec $node crictl pull "$image" > /dev/null
expect "digest the node holds for the tag $commit after the first build" "$(cached)" "$one"

two=$(built second)
[ "$one" != "$two" ] || { echo "FAIL: both builds of $commit pushed $one" >&2; exit 1; }
sql --command "insert into builds (id, service_id, commit_sha, branch, status, image_ref, image_digest, started_at, finished_at) values
  ('$second', '$service', '$commit', 'main', 'succeeded', '$image', '$two', now(), now())" > /dev/null
after=$(release $second)
runs "$after" "$registry/$repository@$two IfNotPresent" "$two" second
expect "digest the node still holds for the tag $commit" "$(cached)" "$one"

sql --command "insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
  ('$legacy', '$service', '$commit', 'main', 'succeeded', '$image', now(), now())" > /dev/null
tagged=$(release $legacy)
runs "$tagged" "$image Always" "$two" second
expect "digest the node holds for the tag $commit after a build without a digest ran" "$(cached)" "$two"

kubectl -n $system port-forward service/liftgate-control-plane 8080:8080 > /dev/null &
forward=$!
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 --output /dev/null http://localhost:8080/readyz
rollback=$(curl --fail --silent --show-error --request POST --header "Authorization: Bearer $token" "http://localhost:8080/api/v1/deployments/$before/rollback" | jq -er .id)
runs "$rollback" "$registry/$repository@$one IfNotPresent" "$one" first
