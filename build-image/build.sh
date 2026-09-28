#!/bin/sh
set -eu

src=/workspace/src

inside() {
  case "$1/" in
    "$src"/*) ;;
    *) echo "$2 is outside the repository" >&2; exit 1 ;;
  esac
}

value() {
  printenv "LIFTGATE_ENV_$1"
  echo .
}

prepare() {
  for name in ${LIFTGATE_BUILD_ENV_NAMES:-}; do
    env=$(value "$name")
    set -- "$@" --env "$name=${env%??}"
  done
  railpack prepare "$@"
}

src=$(realpath "$src")
context=$(realpath "$src/${LIFTGATE_ROOT_DIR#/}")
inside "$context" "the root directory"
dockerfile="$context/$LIFTGATE_DOCKERFILE_PATH"
if [ -f "$dockerfile" ]; then
  dockerfile=$(realpath "$dockerfile")
  inside "$dockerfile" "the Dockerfile"
elif [ "$LIFTGATE_BUILD_STRATEGY" = dockerfile ]; then
  echo "no Dockerfile at $LIFTGATE_DOCKERFILE_PATH" >&2
  exit 1
fi

if [ "$LIFTGATE_BUILD_STRATEGY" = dockerfile ] || [ -f "$dockerfile" ]; then
  PRODUCTION_CACHE=
  set -- --frontend dockerfile.v0 --local "dockerfile=$(dirname "$dockerfile")" --opt "filename=$(basename "$dockerfile")"
  for name in ${LIFTGATE_BUILD_ARG_NAMES:-}; do
    arg=$(value "$name")
    set -- "$@" --opt "build-arg:$name=${arg%??}"
  done
else
  plan=$(mktemp -d)
  prepare --plan-out "$plan/railpack-plan.json" "$context"
  hash=$(for name in ${LIFTGATE_BUILD_ENV_NAMES:-}; do printf '%s\0' "$name"; value "$name"; printf '\0'; done | sha256sum | cut -d ' ' -f 1)
  set -- --frontend gateway.v0 --opt "source=ghcr.io/railwayapp/railpack-frontend:v$RAILPACK_VERSION" --local "dockerfile=$plan" --opt "build-arg:secrets-hash=$hash"
fi

for name in ${LIFTGATE_BUILD_ENV_NAMES:-}; do
  set -- "$@" --secret "id=$name,env=LIFTGATE_ENV_$name"
done

insecure=""
if [ "${LIFTGATE_REGISTRY_INSECURE:-}" = true ]; then
  insecure=",registry.insecure=true"
fi

if [ -n "${PRODUCTION_CACHE:-}" ]; then
  set -- "$@" --import-cache "type=registry,ref=$PRODUCTION_CACHE$insecure"
fi

exec buildctl-daemonless.sh build "$@" \
  --local "context=$context" \
  --output "type=image,name=$IMAGE,push=true$insecure" \
  --export-cache "type=registry,ref=$CACHE,mode=max$insecure" \
  --import-cache "type=registry,ref=$CACHE$insecure"
