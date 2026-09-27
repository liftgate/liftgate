#!/usr/bin/env sh
set -eu

build=liftgate-build
sa=system:serviceaccount:liftgate-system:liftgate
policy="ValidatingAdmissionPolicy 'liftgate'"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT

allowed() {
  kubectl --as="$sa" "$@" > "$out" 2>&1 || { cat "$out"; echo "FAIL: the control plane may not $*"; exit 1; }
  echo "allowed: $*"
}

denied() {
  reason="$1"
  shift
  if kubectl --as="$sa" "$@" > "$out" 2>&1; then echo "FAIL: the control plane may $*"; exit 1; fi
  grep -qF "$reason" "$out" || { cat "$out"; echo "FAIL: $* was refused for another reason"; exit 1; }
  echo "denied: $*"
}

namespace() {
  kubectl create namespace "$1" --dry-run=client -o json | jq ".metadata.labels = $2"
}

job() {
  kubectl create job "e2e-$1" -n "$build" --image=busybox:1.36 --dry-run=client -o json |
    jq '.spec.template.spec.containers[0].securityContext = {seccompProfile: {type: "Unconfined"}, appArmorProfile: {type: "Unconfined"}}' | jq "$2"
}

denied "$policy" create deployment e2e -n kube-system --image=busybox:1.36 --dry-run=server
denied "$policy" create secret generic e2e -n liftgate-system --from-literal=key=value --dry-run=server
denied 'cannot list resource "secrets"' get secrets -n kube-system
denied 'cannot list resource "nodes"' get nodes
denied "$policy" create namespace e2e-unlabelled --dry-run=server
namespace e2e-permissive '{"liftgate.dev/managed": "true"}' | denied "$policy" create --dry-run=server -f -
denied "$policy" label namespace kube-system liftgate.dev/managed=true pod-security.kubernetes.io/enforce=restricted --dry-run=server
denied "$policy" delete namespace "$build" --dry-run=server
namespace e2e-managed '{"liftgate.dev/managed": "true", "pod-security.kubernetes.io/enforce": "restricted"}' | allowed create --dry-run=server -f -
allowed create deployment e2e -n env-e2e --image=busybox:1.36 --dry-run=server
allowed create secret generic e2e -n "$build" --from-literal=token=value --dry-run=server
test "$(kubectl --as="$sa" auth can-i get secrets -n "$build")" = yes

job build . | allowed create --dry-run=server -f -
job privileged '.spec.template.spec.containers[0].securityContext.privileged = true' | denied "$policy" create --dry-run=server -f -
job capabilities '.spec.template.spec.containers[0].securityContext.capabilities = {add: ["SYS_ADMIN"]}' | denied "$policy" create --dry-run=server -f -
job host-pid '.spec.template.spec.hostPID = true' | denied "$policy" create --dry-run=server -f -
job host-path '.spec.template.spec.volumes = [{name: "root", hostPath: {path: "/"}}]' | denied "$policy" create --dry-run=server -f -
