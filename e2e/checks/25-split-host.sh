#!/usr/bin/env sh
set -eu

ns=liftgate-system
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"

expect() {
  for attempt in $(seq 60); do
    code="$(curl --silent --insecure --output /dev/null --write-out '%{http_code}' --resolve "$1:443:$node" "https://$1$2")" || true
    test "$code" = "$3" && { echo "https://$1$2 $code"; return; }
    sleep 2
  done
  echo "FAIL: https://$1$2 answered $code, expected $3"
  exit 1
}

hosts() {
  helm upgrade liftgate charts/liftgate --namespace $ns --reuse-values --set dashboardUrl="$1"
  kubectl -n $ns rollout status deployment/liftgate-control-plane --timeout=5m
}

hosts https://dashboard.liftgate.test
expect dashboard.liftgate.test /login 200
expect liftgate.test /api/v1/me 401
expect liftgate.test /metrics 404
expect liftgate.test /readyz 404

hosts ""
expect liftgate.test /login 200
expect liftgate.test /api/v1/me 401
