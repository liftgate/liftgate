#!/usr/bin/env sh
set -eu

ns=liftgate-system
node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
session=__Host-liftgate_session=e2e-stale

landing() {
  helm upgrade liftgate charts/liftgate --namespace $ns --reuse-values --set dashboard.landing="$1"
  kubectl -n $ns rollout status deployment/liftgate-dashboard --timeout=5m
}

expect() {
  for attempt in $(seq 60); do
    body="$(curl --silent --insecure --resolve "liftgate.test:443:$node" --cookie "$2" --write-out '%{http_code} %{redirect_url}' "https://liftgate.test$1")" || true
    case "$body" in *"$3"*) break ;; esac
    sleep 2
  done
  case "$body" in *"$3"*) ;; *) echo "FAIL: https://liftgate.test$1 ${2:-signed out} never showed $3"; exit 1 ;; esac
  if [ -n "${4:-}" ]; then case "$body" in *"$4"*) echo "FAIL: https://liftgate.test$1 ${2:-signed out} showed $4"; exit 1 ;; esac; fi
  echo "https://liftgate.test$1 ${2:-signed out}: $3"
}

expect / "" "307 https://liftgate.test/dashboard"

landing true
expect / "" "Request access" 'href="/dashboard"'
expect / "$session" 'href="/dashboard"' "Request access"
expect /dashboard "$session" "Sign out"
expect /api/v1/me "$session" '"error":"unauthorized"'
landing false
