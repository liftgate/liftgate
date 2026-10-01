#!/usr/bin/env sh
set -eu

ns=env-e2e-teardown
environment=00000000-0000-4000-8000-000000000401
keep=00000000-0000-4000-8000-000000000402
gone=00000000-0000-4000-8000-000000000403

sql() {
  kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}

owned() {
  kubectl -n $ns get deployments,services,secrets,httproutes --selector "liftgate.dev/service-id=$1" --output name
}

sql <<EOF
insert into environments (id, project_id, slug, name, kind, branch, namespace) values
    ('$environment', '00000000-0000-4000-8000-000000000003', 'teardown', 'Teardown', 'preview', 'teardown', '$ns');
insert into services (id, environment_id, slug, name, kind, port, cpu_millis, memory_mb, start_command) values
    ('$keep', '$environment', 'keep', 'Keep', 'worker', null, 100, 64, 'exec sleep 3600'),
    ('$gone', '$environment', 'gone', 'Gone', 'web', 8080, 100, 64, 'echo gone > /tmp/index.html && exec httpd -f -p 8080 -h /tmp');
insert into domains (id, service_id, hostname, kind, verified_at, certificate_status) values
    ('00000000-0000-4000-8000-000000000404', '$gone', 'gone-teardown.liftgate.app', 'platform', now(), 'issued');
insert into builds (id, service_id, commit_sha, branch, status, image_ref, started_at, finished_at) values
    ('00000000-0000-4000-8000-000000000405', '$keep', '0000000000000000000000000000000000000000', 'teardown', 'succeeded', 'busybox:1.36', now(), now()),
    ('00000000-0000-4000-8000-000000000406', '$gone', '0000000000000000000000000000000000000000', 'teardown', 'succeeded', 'busybox:1.36', now(), now());
insert into deployments (id, service_id, build_id, status) values
    ('00000000-0000-4000-8000-000000000407', '$keep', '00000000-0000-4000-8000-000000000405', 'pending'),
    ('00000000-0000-4000-8000-000000000408', '$gone', '00000000-0000-4000-8000-000000000406', 'pending');
insert into outbox (subject, payload) values
    ('liftgate.release.requested', '{"deploymentId": "00000000-0000-4000-8000-000000000407"}'),
    ('liftgate.release.requested', '{"deploymentId": "00000000-0000-4000-8000-000000000408"}');
EOF
kubectl wait namespace/$ns --for=create --timeout=5m
kubectl -n $ns wait deployment/keep --for=create --timeout=5m
kubectl -n $ns wait httproute/gone --for=create --timeout=5m
test "$(owned $gone | wc -l)" = 4

sql --command "delete from services where id = '$gone'; insert into outbox (subject, payload) values ('liftgate.teardown.requested', '{\"namespace\": \"$ns\", \"serviceId\": \"$gone\"}')"
for attempt in $(seq 60); do
  test -z "$(owned $gone)" && break
  sleep 2
done
test -z "$(owned $gone)"
kubectl -n $ns get deployment/keep

sql --command "delete from environments where id = '$environment'; insert into outbox (subject, payload) values ('liftgate.teardown.requested', '{\"namespace\": \"$ns\"}')"
kubectl wait namespace/$ns --for=delete --timeout=5m
