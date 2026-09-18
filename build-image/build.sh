#!/bin/sh
set -eu

src="$HOME/src"
auth=$(printf 'x-access-token:%s' "$LIFTGATE_GIT_TOKEN" | base64 | tr -d '\n')

git init -q "$src"
git -C "$src" -c "http.extraHeader=Authorization: Basic $auth" fetch -q --depth 1 "$LIFTGATE_REPO_URL" "$LIFTGATE_COMMIT"
git -C "$src" checkout -q FETCH_HEAD

inside() {
  case "$1/" in
    "$src"/*) ;;
    *) echo "$2 is outside the repository" >&2; exit 1 ;;
  esac
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
  set -- --frontend dockerfile.v0 --local "dockerfile=$(dirname "$dockerfile")" --opt "filename=$(basename "$dockerfile")"
else
  plan=$(mktemp -d)
  railpack prepare --plan-out "$plan/railpack-plan.json" "$context"
  set -- --frontend gateway.v0 --opt source=ghcr.io/railwayapp/railpack-frontend --local "dockerfile=$plan"
fi

exec buildctl-daemonless.sh build "$@" \
  --local "context=$context" \
  --output "type=image,name=$IMAGE,push=true" \
  --export-cache "type=registry,ref=$CACHE,mode=max" \
  --import-cache "type=registry,ref=$CACHE"
