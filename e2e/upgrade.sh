#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

NAMESPACE=liftgate-system
for tag in $(git -c versionsort.suffix=- tag --merged HEAD^ --sort=-v:refname --list 'v*'); do
  version="${tag#v}"
  images="ghcr.io/liftgate/control-plane:$version ghcr.io/liftgate/dashboard:$version"
  for image in $images; do docker pull --quiet "$image" || continue 2; done
  break
done
docker image inspect $images > /dev/null
previous="$(mktemp -d)"
git archive "$tag" charts e2e | tar -x -C "$previous"

sql() {
  kubectl -n "$NAMESPACE" exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --tuples-only --no-align "$@"
}
running() {
  test "$(sql --command "select status from deployments where id = '${1:-00000000-0000-4000-8000-000000000008}'")" = running
}

echo "==> install $tag"
EXTRA_IMAGES="$images" CHART="$previous/charts/liftgate" VALUES="$previous/e2e/values.yaml" sh e2e/cluster.sh \
  --set controlPlane.image=ghcr.io/liftgate/control-plane \
  --set-string controlPlane.tag="$version" \
  --set dashboard.image=ghcr.io/liftgate/dashboard \
  --set-string dashboard.tag="$version" \
  --set gateway.issuer=selfsigned
sql < "$previous/e2e/fixture.sql"
sql --command "insert into api_tokens (id, org_id, name, token_hash, created_by) values (gen_random_uuid(),
  '00000000-0000-4000-8000-000000000002', 'upgrade', translate(rtrim(encode(sha256('lg_upgrade'), 'base64'), '='), '+/', '-_'),
  '00000000-0000-4000-8000-000000000001')"
for attempt in $(seq 60); do running && break; sleep 5; done
running

node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
probe() {
  url="https://liftgate.test$1"
  shift
  curl --silent --insecure --max-time 5 --output /dev/null --write-out '%{http_code}\n' \
    --resolve "liftgate.test:443:$node" --header 'Authorization: Bearer lg_upgrade' "$url" "$@"
}
probe /api/v1/orgs/e2e/projects --fail --retry 30 --retry-all-errors --retry-delay 2
codes="$(mktemp)"
while :; do echo "$(date +%T.%3N) $(probe /api/v1/orgs/e2e/projects) $(probe /login)"; sleep 0.2; done > "$codes" &
trap 'kill $!' EXIT
platform='app.kubernetes.io/component in (control-plane, dashboard)'
roll() {
  kubectl -n "$NAMESPACE" rollout status deployment/liftgate-control-plane --timeout=15m
  kubectl -n "$NAMESPACE" rollout status deployment/liftgate-dashboard --timeout=5m
  kubectl -n "$NAMESPACE" wait --for=delete $old --timeout=5m
}
old="$(kubectl -n "$NAMESPACE" get pods -l "$platform" -o name)"
drains="$(kubectl -n "$NAMESPACE" get pods -l "$platform" -o jsonpath='{.items[*].spec.containers[0].lifecycle.preStop}')"

echo "==> upgrade to $(git rev-parse --short HEAD)"
helm dependency update charts/liftgate
upgrading="$(date +%T.%3N)"
helm upgrade liftgate charts/liftgate --namespace "$NAMESPACE" --reset-then-reuse-values --values e2e/values.yaml
roll
drained="$(date +%T.%3N)"
for attempt in $(seq 20); do probe /api/v1/orgs/e2e/projects --fail > /dev/null; done
kubectl get --raw "/api/v1/namespaces/$NAMESPACE/services/liftgate-control-plane:http/proxy/readyz" > /dev/null
running
test "$(sql --command 'select version from flyway_schema_history where success order by installed_rank desc limit 1')" = \
  "$(ls control-plane/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)"
sh e2e/checks/10-gateway.sh

for round in 1 2 3; do
  echo "==> restart the control plane and the dashboard, round $round"
  old="$(kubectl -n "$NAMESPACE" get pods -l "$platform" -o name)"
  kubectl -n "$NAMESPACE" rollout restart deployment/liftgate-control-plane deployment/liftgate-dashboard
  roll
done

echo "==> NATS down for 30 s"
kubectl -n "$NAMESPACE" scale statefulset/liftgate-nats --replicas=0
kubectl -n "$NAMESPACE" wait --for=delete pod/liftgate-nats-0 --timeout=2m
sleep 30
kubectl -n "$NAMESPACE" scale statefulset/liftgate-nats --replicas=1
kubectl -n "$NAMESPACE" rollout status statefulset/liftgate-nats --timeout=5m
release="$(cat /proc/sys/kernel/random/uuid)"
sql --command "insert into deployments (id, service_id, build_id, status) values ('$release', '00000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-000000000007', 'pending');
  insert into outbox (subject, payload) values ('liftgate.release.requested', '{\"deploymentId\": \"$release\"}')"
for attempt in $(seq 60); do running "$release" && break; sleep 5; done
running "$release"

awk '{ print $2, $3 }' "$codes" | sort | uniq -c
test -s "$codes"
[ -n "$drains" ] || echo "the pods of $tag have no preStop hook, so their replacement between $upgrading and $drained may fail requests"
echo "non-2xx responses from the api or the dashboard:"
awk -v from="$upgrading" -v to="$drained" -v strict="${drains:+1}" '$2 !~ /^2/ || $3 !~ /^2/ { print; if (strict || $1 < from || $1 > to) failed = 1 } END { exit failed }' "$codes"
