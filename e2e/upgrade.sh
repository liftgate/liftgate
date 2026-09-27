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
  test "$(sql --command "select status from deployments where id = '00000000-0000-4000-8000-000000000008'")" = running
}

echo "==> install $tag"
EXTRA_IMAGES="$images" CHART="$previous/charts/liftgate" VALUES="$previous/e2e/values.yaml" sh e2e/cluster.sh \
  --set controlPlane.image=ghcr.io/liftgate/control-plane \
  --set-string controlPlane.tag="$version" \
  --set dashboard.image=ghcr.io/liftgate/dashboard \
  --set-string dashboard.tag="$version" \
  --set gateway.issuer=selfsigned
sql < "$previous/e2e/fixture.sql"
for attempt in $(seq 60); do running && break; sleep 5; done
running

node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
probe() {
  curl --silent --insecure --max-time 5 --output /dev/null --write-out '%{http_code}\n' \
    --resolve "liftgate.test:443:$node" https://liftgate.test/api/v1/auth/providers "$@"
}
probe --fail --retry 30 --retry-all-errors --retry-delay 2
codes="$(mktemp)"
while :; do echo "$(date +%T.%3N) $(probe)"; sleep 0.2; done > "$codes" &
trap 'kill $!' EXIT
old="$(kubectl -n "$NAMESPACE" get pods -l app.kubernetes.io/component=control-plane -o name)"

echo "==> upgrade to $(git rev-parse --short HEAD)"
helm dependency update charts/liftgate
helm upgrade liftgate charts/liftgate --namespace "$NAMESPACE" --reset-then-reuse-values --values e2e/values.yaml
kubectl -n "$NAMESPACE" rollout status deployment/liftgate-control-plane --timeout=15m
handover="$(date -d '1 second ago' +%T.%3N)"
kubectl -n "$NAMESPACE" wait --for=delete $old --timeout=5m
drained="$(date +%T.%3N)"
for attempt in $(seq 20); do probe --fail > /dev/null; done
kubectl -n "$NAMESPACE" rollout status deployment/liftgate-dashboard --timeout=5m
kubectl get --raw "/api/v1/namespaces/$NAMESPACE/services/liftgate-control-plane:http/proxy/readyz" > /dev/null
running
test "$(sql --command 'select version from flyway_schema_history where success order by installed_rank desc limit 1')" = \
  "$(ls control-plane/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)"
sh e2e/checks/10-gateway.sh

awk '{ print $2 }' "$codes" | sort | uniq -c
test -s "$codes"
echo "non-2xx responses, tolerated only while $tag shuts down between $handover and $drained:"
awk -v from="$handover" -v to="$drained" '$2 !~ /^2/ { print; if ($1 < from || $1 > to) failed = 1 } END { exit failed }' "$codes"
