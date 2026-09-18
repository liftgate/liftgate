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
PROMETHEUS_VERSION=29.30.1
K8S_API_HOST="${K8S_API_HOST:-$(kubectl -n default get endpointslice kubernetes -o jsonpath='{.endpoints[0].addresses[0]}')}"

echo "==> Gateway API CRDs $GATEWAY_API_VERSION"
for crd in gatewayclasses gateways httproutes referencegrants grpcroutes backendtlspolicies tlsroutes; do
  kubectl apply --server-side -f "https://raw.githubusercontent.com/kubernetes-sigs/gateway-api/${GATEWAY_API_VERSION}/config/crd/standard/gateway.networking.k8s.io_${crd}.yaml"
done

echo "==> Helm repositories"
helm repo add cilium https://helm.cilium.io/ --force-update
helm repo add jetstack https://charts.jetstack.io --force-update
helm repo add cnpg https://cloudnative-pg.github.io/charts --force-update
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update
helm repo update

echo "==> Cilium $CILIUM_VERSION (API server $K8S_API_HOST)"
helm upgrade --install cilium cilium/cilium --version "$CILIUM_VERSION" \
  --namespace kube-system \
  -f cilium/values.yaml \
  --set k8sServiceHost="$K8S_API_HOST" \
  --wait --timeout 10m

echo "==> gVisor RuntimeClass"
kubectl apply -f gvisor/runtimeclass.yaml

echo "==> cert-manager $CERT_MANAGER_VERSION"
helm upgrade --install cert-manager jetstack/cert-manager --version "$CERT_MANAGER_VERSION" \
  --namespace cert-manager --create-namespace \
  --set crds.enabled=true \
  --set config.gatewayAPI.enabled=true \
  --wait --timeout 10m
sed "s/LETSENCRYPT_EMAIL/$LETSENCRYPT_EMAIL/" cert-manager/clusterissuer.yaml | kubectl apply -f -

echo "==> CloudNativePG operator $CNPG_VERSION"
helm upgrade --install cnpg cnpg/cloudnative-pg --version "$CNPG_VERSION" \
  --namespace cnpg-system --create-namespace \
  --wait --timeout 10m

echo "==> Prometheus $PROMETHEUS_VERSION"
helm upgrade --install prometheus prometheus-community/prometheus --version "$PROMETHEUS_VERSION" \
  --namespace liftgate-system --create-namespace \
  -f prometheus/values.yaml \
  --wait --timeout 10m

echo "==> Baseline ready. Install the chart: helm upgrade --install liftgate charts/liftgate -n liftgate-system"
