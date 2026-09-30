#!/usr/bin/env sh
set -eu

kubectl -n env-e2e-b wait deployment/web --for=create --timeout=5m
kubectl -n env-e2e-b rollout status deployment/web --timeout=5m
kubectl -n liftgate-system wait clusters.postgresql.cnpg.io/liftgate-postgres --for=condition=Ready --timeout=5m

python3 -m http.server 5000 --bind 0.0.0.0 > /dev/null 2>&1 &
trap "kill $!; kubectl -n default delete pod isolation-probe --ignore-not-found --wait=false" EXIT
kubectl -n default run isolation-probe --image busybox:1.36 --restart Never --command -- sleep 900
kubectl -n default wait pod/isolation-probe --for=condition=Ready --timeout=2m

podIp() { kubectl -n "$1" get pods -l "$2" -o jsonpath='{.items[0].status.podIP}'; }
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
host="$(docker exec liftgate-control-plane ip -4 route show default | awk '{print $3}')"
tenant_a_service="$(kubectl -n env-e2e get service/web -o jsonpath='{.spec.clusterIP}')"
tenant_b_service="$(kubectl -n env-e2e-b get service/web -o jsonpath='{.spec.clusterIP}')"
tenant_b="$(podIp env-e2e-b liftgate.dev/service=web)"
control_plane="$(podIp liftgate-system app.kubernetes.io/component=control-plane)"
dashboard="$(podIp liftgate-system app.kubernetes.io/component=dashboard)"
postgres="$(podIp liftgate-system cnpg.io/cluster=liftgate-postgres,cnpg.io/podRole=instance)"
nats="$(podIp liftgate-system app.kubernetes.io/component=nats)"

expect() {
  result="$(kubectl -n "$2" exec "$3" -- sh -c 'nc -z -w 3 "$0" "$1" && echo open || echo closed' "$4" "$5")"
  test "$result" = "$1" || { echo "FAIL: $2/$3 -> $4:$5 is ${result:-unknown}, expected $1"; exit 1; }
  echo "$2/$3 -> $4:$5 $1"
}

expect open env-e2e deploy/web "$tenant_a_service" 80
for target in "$tenant_b 8080" "$tenant_b_service 80" "$control_plane 8080" "$control_plane 5701" "$postgres 5432" \
  "$nats 4222" "$nats 8222" "169.254.169.254 80" "$host 5000" "$node 6443" "$node 10250"; do
  expect closed env-e2e deploy/web $target
done

for target in "$host 5000" "$node 10250" "$control_plane 8080" "$dashboard 3000"; do
  expect open default isolation-probe $target
done
for target in "$tenant_b 8080" "$control_plane 5701" "$postgres 5432" "$nats 4222" "$nats 8222"; do
  expect closed default isolation-probe $target
done

gateway() {
  code="$(curl --silent --insecure --output /dev/null --write-out '%{http_code}' --retry 30 --retry-all-errors --retry-delay 2 \
    --resolve "$1:443:$node" "https://$1$2")"
  test "$code" = "$3" || { echo "FAIL: gateway -> https://$1$2 answered $code, expected $3"; exit 1; }
  echo "gateway -> https://$1$2 $code"
}

gateway web-hello-e2e.liftgate.app / 200
gateway web-hello-e2e-b.liftgate.app / 200
gateway liftgate.test /login 200
gateway liftgate.test /api/v1/me 401
