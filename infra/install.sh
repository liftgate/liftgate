#!/usr/bin/env sh
set -eu

: "${LETSENCRYPT_EMAIL:?set LETSENCRYPT_EMAIL to the address Let's Encrypt should notify}"
command -v kubectl >/dev/null || { echo "kubectl not found" >&2; exit 1; }
command -v helm >/dev/null || { echo "helm not found" >&2; exit 1; }

cd "$(dirname "$0")"

GATEWAY_API_VERSION=v1.6.1
CILIUM_VERSION=1.20.2
CERT_MANAGER_VERSION=v1.21.2
CNPG_VERSION=0.29.0
BARMAN_CLOUD_VERSION=0.8.0
PROMETHEUS_VERSION=29.30.1
GATEWAY_NAME="${LIFTGATE_GATEWAY_NAME:-liftgate}"
GATEWAY_NAMESPACE="${LIFTGATE_GATEWAY_NAMESPACE:-liftgate-system}"
K8S_API_HOST="${K8S_API_HOST:-$(kubectl -n default get endpointslice kubernetes -o jsonpath='{.endpoints[0].addresses[0]}')}"

found() {
  kubectl get "$@" >/dev/null 2>&1 || return 1
  echo "    found $*, skipping"
}

labelled() {
  namespace="$(kubectl get "$1" --all-namespaces --selector "$2" --output jsonpath='{.items[*].metadata.namespace}')"
  [ -n "$namespace" ] && echo "    found $1 $2 in $namespace, skipping"
}

if [ "${LIFTGATE_INSTALL_CILIUM:-}" = 1 ]; then
  if ! kubectl -n kube-system get daemonset cilium >/dev/null 2>&1; then
    networks="$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].message}{"\n"}{end}')"
    if [ -z "$networks" ] || printf '%s\n' "$networks" | grep -qv NetworkReady=false; then
      echo "not every node reports NetworkReady=false: another CNI runs here or the nodes are still starting; install.sh never installs Cilium over another CNI" >&2
      exit 1
    fi
  fi

  echo "==> Gateway API CRDs $GATEWAY_API_VERSION"
  for crd in gatewayclasses gateways httproutes referencegrants grpcroutes backendtlspolicies tlsroutes; do
    found crd "$crd.gateway.networking.k8s.io" || kubectl apply --server-side -f "https://raw.githubusercontent.com/kubernetes-sigs/gateway-api/${GATEWAY_API_VERSION}/config/crd/standard/gateway.networking.k8s.io_${crd}.yaml"
  done

  echo "==> Cilium $CILIUM_VERSION (API server $K8S_API_HOST)"
  found daemonset cilium -n kube-system || helm install cilium cilium --repo https://helm.cilium.io/ --version "$CILIUM_VERSION" \
    --namespace kube-system \
    -f cilium/values.yaml \
    --set k8sServiceHost="$K8S_API_HOST" \
    --wait --timeout 10m
elif ! kubectl get gatewayclasses -o name 2>/dev/null | grep -q .; then
  echo "no GatewayClass found: install a Gateway API implementation and set the chart's gateway.className, or set LIFTGATE_INSTALL_CILIUM=1 on a cluster without a CNI" >&2
  exit 1
fi

echo "==> gVisor RuntimeClass"
found runtimeclass gvisor || kubectl apply -f gvisor/runtimeclass.yaml

echo "==> cert-manager $CERT_MANAGER_VERSION"
labelled deployments app.kubernetes.io/name=cert-manager,app.kubernetes.io/component=controller || helm install cert-manager cert-manager --repo https://charts.jetstack.io --version "$CERT_MANAGER_VERSION" \
  --namespace cert-manager --create-namespace \
  --set crds.enabled=true \
  --set config.gatewayAPI.enabled=true \
  --wait --timeout 10m

echo "==> ClusterIssuer letsencrypt for Gateway $GATEWAY_NAMESPACE/$GATEWAY_NAME"
found clusterissuer letsencrypt || sed \
  -e "s/LETSENCRYPT_EMAIL/$LETSENCRYPT_EMAIL/" \
  -e "s/GATEWAY_NAMESPACE/$GATEWAY_NAMESPACE/" \
  -e "s/GATEWAY_NAME/$GATEWAY_NAME/" \
  cert-manager/clusterissuer.yaml | kubectl apply -f -

echo "==> CloudNativePG operator $CNPG_VERSION"
labelled deployments app.kubernetes.io/name=cloudnative-pg || helm install cnpg cloudnative-pg --repo https://cloudnative-pg.github.io/charts --version "$CNPG_VERSION" \
  --namespace cnpg-system --create-namespace \
  --wait --timeout 10m
CNPG_NAMESPACE="${namespace:-cnpg-system}"
case "$CNPG_NAMESPACE" in *" "*) echo "CloudNativePG operators run in several namespaces ($CNPG_NAMESPACE); keep one, because the Barman Cloud plugin must run in the operator's namespace" >&2; exit 1 ;; esac

echo "==> Barman Cloud plugin $BARMAN_CLOUD_VERSION in $CNPG_NAMESPACE"
if labelled services cnpg.io/pluginName=barman-cloud.cloudnative-pg.io; then
  [ "$namespace" = "$CNPG_NAMESPACE" ] || { echo "the Barman Cloud plugin runs in $namespace, but it must run in $CNPG_NAMESPACE with the CloudNativePG operator" >&2; exit 1; }
else
  helm install plugin-barman-cloud plugin-barman-cloud --repo https://cloudnative-pg.github.io/charts --version "$BARMAN_CLOUD_VERSION" \
    --namespace "$CNPG_NAMESPACE" \
    --wait --timeout 10m
fi

echo "==> Prometheus $PROMETHEUS_VERSION"
found service prometheus -n liftgate-system || helm install prometheus prometheus --repo https://prometheus-community.github.io/helm-charts --version "$PROMETHEUS_VERSION" \
  --namespace liftgate-system --create-namespace \
  -f prometheus/values.yaml \
  --wait --timeout 10m

echo "==> Baseline ready. Install the chart: helm install liftgate oci://ghcr.io/liftgate/charts/liftgate --version <version> -n liftgate-system"
