#!/usr/bin/env sh
set -eu

service=00000000-0000-4000-8000-000000000501

eventually() {
  for attempt in $(seq 60); do "$@" && return; sleep 5; done
  "$@"
}
gone() {
  test -z "$(kubectl get "$@" --ignore-not-found --output name)"
}

kubectl create namespace env-e2e-orphan
kubectl label namespace env-e2e-orphan liftgate.dev/managed=true liftgate.dev/org-id=00000000-0000-4000-8000-000000000002

kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 <<EOF
insert into services (id, environment_id, slug, name, kind, cpu_millis, memory_mb, start_command) values
    ('$service', '00000000-0000-4000-8000-000000000004', 'orphan', 'Orphan', 'worker', 100, 64, 'exec sleep 3600');

insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
    ('00000000-0000-4000-8000-000000000502', '$service', '0000000000000000000000000000000000000000', 'main', 'succeeded', 'busybox:1.36', now(), now());

insert into deployments (id, service_id, build_id, status) values
    ('00000000-0000-4000-8000-000000000503', '$service', '00000000-0000-4000-8000-000000000502', 'pending');

insert into outbox (subject, payload) values
    ('liftgate.release.requested', '{"deploymentId": "00000000-0000-4000-8000-000000000503"}');
EOF
kubectl -n env-e2e wait deployment/orphan --for=create --timeout=5m
kubectl -n env-e2e get secret/orphan-env

kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 \
  --command "delete from services where id = '$service'"
eventually gone --namespace env-e2e deployment/orphan secret/orphan-env
eventually gone namespace/env-e2e-orphan
kubectl -n env-e2e get deployment/web secret/web-env
