#!/bin/sh
set -eu

image=$1
fixtures=$(cd "$(dirname "$0")" && pwd)
run=liftgate-build-test-$$
work=$(mktemp -d)
foo="plain value $$
"
token="secret-$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
greeting="Hello from build time $$"

cleanup() {
  docker rm -f "$run-registry" "$run-next" "$run-hello" "$run-fastapi" "$run-sveltekit-node" "$run-pnpm-workspace" "$run-vite" "$run-php" > /dev/null 2>&1 || true
  docker network rm "$run" > /dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

expect() {
  [ "$2" = "$3" ] || { echo "$1: expected '$3', got '$2'" >&2; exit 1; }
}

build() {
  fixture=$1
  name=$2
  shift 2
  case $fixture in
    php*) ;;
    *) set -- --add-host registry-1.docker.io:127.0.0.1 "$@" ;;
  esac
  if ! docker run --rm --network "$run" --security-opt seccomp=unconfined --security-opt apparmor=unconfined \
    --volume "$fixtures/$fixture:/workspace/src:ro" \
    --env LIFTGATE_ROOT_DIR=/ --env LIFTGATE_DOCKERFILE_PATH=Dockerfile --env LIFTGATE_REGISTRY_INSECURE=true \
    --env "IMAGE=registry:5000/test/$name:latest" --env "CACHE=registry:5000/test/$name:cache" \
    "$@" "$image" > "$work/$name.log" 2>&1; then
    cat "$work/$name.log"
    exit 1
  fi
  docker pull --quiet "$registry/test/$name:latest" > /dev/null
  docker history --no-trunc "$registry/test/$name:latest" > "$work/$name.history"
  docker image inspect "$registry/test/$name:latest" >> "$work/$name.history"
  if grep -F "$token" "$work/$name.history" "$work/$name.log"; then
    echo "the secret variable leaked into the $name image history or build log" >&2
    exit 1
  fi
}

sha() {
  printf %s "$1" | sha256sum | cut -d ' ' -f 1
}

restricted() {
  docker run --user 1000:1000 --cap-drop ALL --security-opt no-new-privileges "$@"
}

serve() {
  name=$1
  path=$2
  shift 2
  restricted --detach --name "$run-$name" --env PORT=8080 --publish 127.0.0.1::8080 "$@" > /dev/null
  curl --silent --show-error --fail --retry 60 --retry-all-errors --retry-delay 1 "http://$(docker port "$run-$name" 8080/tcp | head -n 1)$path"
}

contract() {
  for fixture in "$fixtures"/*/; do
    docker run --rm --entrypoint railpack --volume "$fixture:/src:ro" "$image" info --format json /src 2> /dev/null |
      jq --arg name "$(basename "$fixture")" '{($name): {provider: .metadata.providers, framework: [.metadata // {} | to_entries[] | select(.key | endswith("Runtime")) | .value][0]}}'
  done | jq --slurp --sort-keys add
}

writable() {
  expect "the name of uid 1000 in $1" "$(docker exec "$run-$1" id -un)" liftgate
  expect "a writable HOME, /app and /app/$2 in $1" "$(docker exec "$run-$1" sh -c "touch \"\$HOME/probe\" ./probe $2/probe && echo \"\$HOME\"")" /home/liftgate
  expect "unwritable paths under /app in $1" "$(docker exec "$run-$1" find . \( -type d -o ! -path '*/node_modules/*' \) ! -writable -print -quit)" ""
  added=$(docker history --no-trunc --human=false --format '{{.Size}} {{.CreatedBy}}' "$registry/test/$1:latest" | awk 'NR == 1 || /home\/liftgate/ { size += $1 } END { print size }')
  [ "$added" -lt 1000000 ] || { echo "the layers Liftgate adds to the $1 image hold $added bytes" >&2; exit 1; }
}

environment() {
  docker image inspect -f '{{join .Config.Env "\n"}}' "$registry/test/$1:latest" | grep -v '^RAILPACK_BUILT_AT=' | sort
}

docker network create "$run" > /dev/null
docker run --detach --name "$run-registry" --network "$run" --network-alias registry --publish 127.0.0.1::5000 \
  registry:2.8.3@sha256:a3d8aaa63ed8681a604f1dea0aa03f100d5895b6a58ace528858a7b332415373 > /dev/null
registry=$(docker port "$run-registry" 5000/tcp | head -n 1)

build dockerfile dockerfile --env LIFTGATE_BUILD_STRATEGY=dockerfile \
  --env "LIFTGATE_BUILD_ENV_NAMES=FOO API_TOKEN" --env LIFTGATE_BUILD_ARG_NAMES=FOO \
  --env "LIFTGATE_ENV_FOO=$foo" --env "LIFTGATE_ENV_API_TOKEN=$token"
built=$registry/test/dockerfile:latest
expect "ARG FOO" "$(docker run --rm "$built" sha256sum /foo | cut -d ' ' -f 1)" "$(sha "$foo")"
expect "ARG of a secret variable" "$(docker run --rm "$built" cat /api-token-arg)" ""
expect "secret mount" "$(docker run --rm "$built" cat /api-token.sha256)" "$(sha "$token")"

build dockerfile preview --env LIFTGATE_BUILD_STRATEGY=dockerfile --env PRODUCTION_CACHE=registry:5000/test/dockerfile:cache \
  --env "LIFTGATE_BUILD_ENV_NAMES=FOO API_TOKEN" --env LIFTGATE_BUILD_ARG_NAMES=FOO \
  --env "LIFTGATE_ENV_FOO=$foo" --env "LIFTGATE_ENV_API_TOKEN=preview $token"
expect "secret mount of a preview build" "$(docker run --rm "$registry/test/preview:latest" cat /api-token.sha256)" "$(sha "preview $token")"

build named-user named-user --env LIFTGATE_BUILD_STRATEGY=dockerfile
named=$registry/test/named-user:latest
expect "writes of uid 1000 to a WORKDIR the image gave uid 1001" \
  "$(restricted --rm --entrypoint /busybox "$named" sh -c '/busybox touch /srv/new /srv/data/new && echo y >> /srv/data/file && [ ! -e /srv/ignored.txt ] && /busybox stat -c %u /etc/passwd')" 0
expect "the user of a Dockerfile image" "$(docker image inspect -f '{{.Config.User}}' "$named")" 1000:1000

build next next --env LIFTGATE_BUILD_STRATEGY=auto \
  --env "LIFTGATE_BUILD_ENV_NAMES=NEXT_PUBLIC_GREETING API_TOKEN" --env LIFTGATE_BUILD_ARG_NAMES=NEXT_PUBLIC_GREETING \
  --env "LIFTGATE_ENV_NEXT_PUBLIC_GREETING=$greeting" --env "LIFTGATE_ENV_API_TOKEN=$token"
restricted --detach --name "$run-next" --env PORT=3000 --publish 127.0.0.1::3000 "$registry/test/next:latest" > /dev/null
next=http://$(docker port "$run-next" 3000/tcp | head -n 1)
page=$(curl --silent --show-error --retry 60 --retry-all-errors --retry-delay 1 "$next/")
case "$page" in
  *"$greeting"*) ;;
  *) echo "the Next.js page does not render NEXT_PUBLIC_GREETING: $page" >&2; exit 1 ;;
esac
resized="$next/_next/image?url=%2Fsample.png&w=64&q=75"
curl --silent --show-error --output /dev/null "$resized"
expect "x-nextjs-cache of the second image request" \
  "$(curl --silent --show-error --dump-header - --output /dev/null "$resized" | tr -d '\r' | awk -F ': ' 'tolower($1) == "x-nextjs-cache" { print $2 }')" HIT
writable next .next
if docker logs "$run-next" 2>&1 | grep EACCES; then
  echo "the Next.js app was denied a write" >&2
  exit 1
fi

build hello hello --env LIFTGATE_BUILD_STRATEGY=auto
restricted --detach --name "$run-hello" --env PORT=8080 --publish 127.0.0.1::8080 "$registry/test/hello:latest" > /dev/null
expect "the getting-started sample" "$(curl --silent --show-error --retry 60 --retry-all-errors --retry-delay 1 "http://$(docker port "$run-hello" 8080/tcp | head -n 1)/")" "hello from liftgate"

build fastapi fastapi --env LIFTGATE_BUILD_STRATEGY=auto --env LIFTGATE_BUILD_ENV_NAMES=API_TOKEN --env "LIFTGATE_ENV_API_TOKEN=$token"
expect "the FastAPI sample" "$(serve fastapi / "$registry/test/fastapi:latest")" '"hello from fastapi"'

build sveltekit-node sveltekit-node --env LIFTGATE_BUILD_STRATEGY=auto --env "LIFTGATE_START_COMMAND=node build" \
  --env LIFTGATE_BUILD_ENV_NAMES=API_TOKEN --env "LIFTGATE_ENV_API_TOKEN=$token"
expect "SvelteKit with adapter-node" "$(serve sveltekit-node /runtime "$registry/test/sveltekit-node:latest")" "hello from node"

mkdir -m 755 "$work/shim"
cat > "$work/shim/buildctl-daemonless.sh" << 'SHIM'
#!/bin/sh
printf 'buildctl %s\n' "$@" >&2
exec /usr/bin/buildctl-daemonless.sh "$@"
SHIM
chmod 755 "$work/shim/buildctl-daemonless.sh"
build pnpm-workspace pnpm-workspace --env LIFTGATE_BUILD_STRATEGY=auto --volume "$work/shim:/usr/local/sbin:ro" \
  --env "LIFTGATE_BUILD_COMMAND=pnpm --filter web build" --env "LIFTGATE_START_COMMAND=pnpm --filter web start" \
  --env "LIFTGATE_BUILD_ENV_NAMES=RAILPACK_BUILD_CMD API_TOKEN" --env "LIFTGATE_ENV_RAILPACK_BUILD_CMD=exit 1" --env "LIFTGATE_ENV_API_TOKEN=$token"
expect "secrets named RAILPACK_BUILD_CMD" "$(grep -c '^buildctl id=RAILPACK_BUILD_CMD,' "$work/pnpm-workspace.log")" 1
expect "a pnpm workspace app started through /bin/sh -c" \
  "$(serve pnpm-workspace / --entrypoint /bin/sh "$registry/test/pnpm-workspace:latest" -c "pnpm --filter web start")" "hello from a pnpm workspace"

build vite vite --env LIFTGATE_BUILD_STRATEGY=auto --env LIFTGATE_BUILD_ENV_NAMES=API_TOKEN --env "LIFTGATE_ENV_API_TOKEN=$token"
expect "GET /health on the Vite sample" "$(serve vite /health "$registry/test/vite:latest" | grep -o "hello from vite")" "hello from vite"
writable vite dist

build php php --env LIFTGATE_BUILD_STRATEGY=auto
expect "the PHP app's own front controller" "$(docker run --rm --entrypoint cat "$registry/test/php:latest" public/index.php)" "$(cat "$fixtures/php/public/index.php")"
restricted --detach --name "$run-php" --entrypoint sleep "$registry/test/php:latest" infinity > /dev/null
writable php public

docker run --rm --entrypoint cat "$image" /usr/local/bin/build.sh | sed '/^  jq /,/^  mv /d' > "$work/plain.sh"
chmod 755 "$work/plain.sh"
build php-mise php-mise --env LIFTGATE_BUILD_STRATEGY=auto
build php-mise php-mise-plain --env LIFTGATE_BUILD_STRATEGY=auto --env PRODUCTION_CACHE=registry:5000/test/php-mise:cache \
  --volume "$work/plain.sh:/usr/local/bin/build.sh:ro"
expect "the environment of a PHP image with mise packages" "$(environment php-mise)" "$( (environment php-mise-plain; echo HOME=/home/liftgate) | sort)"

expect "railpack info" "$(contract)" "$(jq --sort-keys . "$fixtures/expected.json")"

echo "build variables reach Railpack and Dockerfile builds, secrets stay out of the image, and uid 1000 can write to the app's directory"
