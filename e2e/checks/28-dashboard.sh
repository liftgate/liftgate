#!/usr/bin/env sh
set -eu

node="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
token="$(openssl rand -hex 24)"
hash="$(printf %s "$token" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=')"

kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 --quiet \
  --command "insert into sessions (id, user_id, expires_at) values ('$hash', '00000000-0000-4000-8000-000000000001', now() + interval '1 hour')"

get() {
  url="https://liftgate.test$1" session="$2"
  shift 2
  curl --silent --insecure --resolve "liftgate.test:443:$node" ${session:+--cookie "__Host-liftgate_session=$session"} "$@" "$url"
}

expect() {
  for attempt in $(seq 30); do
    code="$(get "$1" "$3" --output /dev/null --write-out '%{http_code}')" || true
    test "$code" = "$2" && { echo "https://liftgate.test$1 ${3:+signed in }$code"; return; }
    sleep 2
  done
  echo "FAIL: https://liftgate.test$1 ${3:+signed in }answered $code, expected $2"
  exit 1
}

signin() {
  expect "$1" 307 ""
  login="$(get "$1" "" --output /dev/null --write-out '%{redirect_url}')"
  test "$login" = "https://liftgate.test/login?next=$2" || { echo "FAIL: https://liftgate.test$1 signed out redirects to $login"; exit 1; }
  echo "https://liftgate.test$1 signed out redirects to $login"
}

signin /e2e %2Fe2e
signin /no-such-page-zz9 %2Fno-such-page-zz9
signin "/new?org=e2e" %2Fnew%3Forg%3De2e
expect /e2e 200 "$token"
expect /e2e/hello 200 "$token"
expect /e2e-b 200 "$token"
expect /no-such-page-zz9 404 "$token"
expect /legal/terms 404 "$token"

for path in /login /api/v1/me; do
  headers="$(get $path "" --output /dev/null --dump-header - | tr -d '\r')"
  for header in "content-security-policy: .*frame-ancestors 'none'" "x-content-type-options: nosniff" "referrer-policy: strict-origin-when-cross-origin" "strict-transport-security: max-age=63072000"; do
    printf '%s\n' "$headers" | grep -qi "^$header" || { echo "FAIL: https://liftgate.test$path lacks $header"; printf '%s\n' "$headers"; exit 1; }
  done
  echo "https://liftgate.test$path carries the security headers"
done
