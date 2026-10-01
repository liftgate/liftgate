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

setting() {
  if [ -n "$2" ]; then
    export "LIFTGATE_ENV_$1=$2"
    case " ${LIFTGATE_BUILD_ENV_NAMES:-} " in
      *" $1 "*) ;;
      *) LIFTGATE_BUILD_ENV_NAMES="${LIFTGATE_BUILD_ENV_NAMES:-} $1" ;;
    esac
  fi
}

own() {
  echo "${2-}find $1 '(' -type d -o ! -path '*/node_modules/*' ')' '(' ! -user 1000 -o ! -group 1000 ')' -exec ${2-}chown -h 1000:1000 {} +"
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
  wrap=$(mktemp -d)
  file=$(basename "$dockerfile")
  cp "$dockerfile" "$wrap/$file"
  if [ -f "$dockerfile.dockerignore" ]; then
    inside "$(realpath "$dockerfile.dockerignore")" "the Dockerfile's ignore file"
    cp "$dockerfile.dockerignore" "$wrap/$file.dockerignore"
  fi
  printf '\nUSER 0:0\nRUN --mount=type=bind,from=busybox:1.37.0-musl@sha256:5cec3fc171c87218698e85a52af7087de727372aae264a787b8112901a5b0092,source=/bin/busybox,target=/.liftgate/busybox ["/.liftgate/busybox","sh","-c","case $(pwd) in /) ;; *) %s ;; esac"]\nUSER 1000:1000\n' \
    "$(own . '/.liftgate/busybox ')" >> "$wrap/$file"
  set -- --frontend dockerfile.v0 --local "dockerfile=$wrap" --opt "filename=$file"
  for name in ${LIFTGATE_BUILD_ARG_NAMES:-}; do
    arg=$(value "$name")
    set -- "$@" --opt "build-arg:$name=${arg%??}"
  done
else
  setting RAILPACK_BUILD_CMD "${LIFTGATE_BUILD_COMMAND:-}"
  setting RAILPACK_START_CMD "${LIFTGATE_START_COMMAND:-}"
  plan=$(mktemp -d)
  prepare --plan-out "$plan/railpack-plan.json" "$context"
  jq --arg own "$(own /app)" '
    [.deploy.inputs[]? | select(.step) | {step, include: [.include[]? | select(. == "/app" or startswith("/app/") or (startswith("/") | not))], exclude: ["*"]} | select(.include != [])] as $roots
    | ([$roots[].step] | unique) as $sources
    | .steps += [$sources[] | {name: "liftgate:own:\(.)", inputs: [{step: .}], commands: [{cmd: $own}]}]
    | .steps += [{name: "liftgate:own", inputs: ([.deploy.base] + $roots), commands: [{
        cmd: "sh -ec \"\($own); mkdir -p /home/liftgate; chown 1000:1000 /home/liftgate; cut -d: -f3 /etc/passwd | grep -qx 1000 || echo liftgate:x:1000:1000::/home/liftgate:/bin/sh >> /etc/passwd\""
      }]}]
    | .deploy.inputs |= if (. // []) == [] then . else map(if .step | IN($sources[]) then .step |= "liftgate:own:\(.)" else . end) + [{step: "liftgate:own", include: ["/."], exclude: ["*", "!app"]}] end
    | .deploy.base = {step: "liftgate:own"}
    | .deploy.variables.HOME = "/home/liftgate"
  ' "$plan/railpack-plan.json" > "$plan/owned.json"
  mv "$plan/owned.json" "$plan/railpack-plan.json"
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
