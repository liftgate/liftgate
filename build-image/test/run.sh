#!/bin/sh
set -eu

image=$1
fixtures=$(cd "$(dirname "$0")" && pwd)
run=liftgate-build-test-$$
work=$(mktemp -d)
foo="plain value $$"
token="secret-$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
greeting="Hello from build time $$"

cleanup() {
  docker rm -f "$run-registry" "$run-next" > /dev/null 2>&1 || true
  docker network rm "$run" > /dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

expect() {
  [ "$2" = "$3" ] || { echo "$1: expected '$3', got '$2'" >&2; exit 1; }
}

build() {
  fixture=$1
  shift
  if ! docker run --rm --network "$run" --security-opt seccomp=unconfined --security-opt apparmor=unconfined \
    --volume "$fixtures/$fixture:/workspace/src:ro" \
    --env LIFTGATE_ROOT_DIR=/ --env LIFTGATE_DOCKERFILE_PATH=Dockerfile --env LIFTGATE_REGISTRY_INSECURE=true \
    --env "IMAGE=registry:5000/test/$fixture:latest" --env "CACHE=registry:5000/test/$fixture:cache" --env PRODUCTION_CACHE= \
    "$@" "$image" > "$work/$fixture.log" 2>&1; then
    cat "$work/$fixture.log"
    exit 1
  fi
  docker pull --quiet "$registry/test/$fixture:latest" > /dev/null
}

docker network create "$run" > /dev/null
docker run --detach --name "$run-registry" --network "$run" --network-alias registry --publish 127.0.0.1::5000 \
  registry:2.8.3@sha256:a3d8aaa63ed8681a604f1dea0aa03f100d5895b6a58ace528858a7b332415373 > /dev/null
registry=$(docker port "$run-registry" 5000/tcp | head -n 1)

build dockerfile --env LIFTGATE_BUILD_STRATEGY=dockerfile \
  --env "LIFTGATE_BUILD_ENV_NAMES=FOO API_TOKEN" --env LIFTGATE_BUILD_ARG_NAMES=FOO \
  --env "LIFTGATE_ENV_FOO=$foo" --env "LIFTGATE_ENV_API_TOKEN=$token"
built=$registry/test/dockerfile:latest
expect "ARG FOO" "$(docker run --rm "$built" cat /foo)" "$foo"
expect "ARG of a secret variable" "$(docker run --rm "$built" cat /api-token-arg)" ""
expect "secret mount" "$(docker run --rm "$built" cat /api-token.sha256)" "$(printf %s "$token" | sha256sum | cut -d ' ' -f 1)"
docker history --no-trunc "$built" > "$work/history"
docker image inspect "$built" >> "$work/history"
if grep -F "$token" "$work/history" "$work/dockerfile.log"; then
  echo "the secret variable leaked into the image history or the build log" >&2
  exit 1
fi

build next --env LIFTGATE_BUILD_STRATEGY=auto \
  --env LIFTGATE_BUILD_ENV_NAMES=NEXT_PUBLIC_GREETING --env LIFTGATE_BUILD_ARG_NAMES=NEXT_PUBLIC_GREETING \
  --env "LIFTGATE_ENV_NEXT_PUBLIC_GREETING=$greeting"
docker run --detach --name "$run-next" --env PORT=3000 --publish 127.0.0.1::3000 "$registry/test/next:latest" > /dev/null
page=$(curl --silent --show-error --retry 60 --retry-all-errors --retry-delay 1 "http://$(docker port "$run-next" 3000/tcp | head -n 1)/")
case "$page" in
  *"$greeting"*) ;;
  *) echo "the Next.js page does not render NEXT_PUBLIC_GREETING: $page" >&2; exit 1 ;;
esac

echo "build variables reach Railpack and Dockerfile builds, and secrets stay out of the image"
