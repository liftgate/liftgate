#!/usr/bin/env sh
set -eu

ns=liftgate-build
trap "kubectl -n $ns delete pod egress-probe --ignore-not-found --wait=false" EXIT
kubectl -n $ns run egress-probe --image busybox:1.36 --restart Never --command -- sleep 900
kubectl -n $ns wait pod/egress-probe --for=condition=Ready --timeout=2m

expect() {
  test "$2" = "$3" || { echo "FAIL: $1 is '$2', expected '$3'"; exit 1; }
  echo "$1: $2"
}
tcp() { kubectl -n "$1" exec "$2" -- sh -c 'nc -z -w 5 "$0" "$1" && echo open || echo closed' "$3" "$4"; }
dns() { kubectl -n $ns exec egress-probe -- sh -c 'timeout 15 nslookup example.com $0 > /dev/null 2>&1 && echo resolved || echo blocked' "${1:-}"; }
from_node() { docker exec liftgate-control-plane timeout 5 bash -c "< /dev/tcp/$1/$2" 2> /dev/null && echo open || echo closed; }

expect "build tcp 443 to 1.1.1.1" "$(tcp $ns egress-probe 1.1.1.1 443)" open
expect "build cluster dns" "$(dns)" resolved
expect "build udp dns to 1.1.1.1" "$(dns 1.1.1.1)" blocked
expect "node tcp 443 to the denied 1.0.0.1" "$(from_node 1.0.0.1 443)" open
expect "build tcp 443 to the denied 1.0.0.1" "$(tcp $ns egress-probe 1.0.0.1 443)" closed
expect "tenant tcp 443 to the denied 1.0.0.1" "$(tcp env-e2e deploy/web 1.0.0.1 443)" closed
for target in "smtp.gmail.com 465" "smtp.gmail.com 587" "smtp.sendgrid.net 2525"; do
  if test "$(from_node $target)" = open; then
    expect "build tcp to $target" "$(tcp $ns egress-probe $target)" closed
  else
    echo "skipped $target: the runner itself cannot reach it"
  fi
done
