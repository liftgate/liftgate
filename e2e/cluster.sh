#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

GATEWAY_API_VERSION=v1.6.1
CILIUM_VERSION=1.20.2
CERT_MANAGER_VERSION=v1.21.2
CNPG_VERSION=0.29.0
NAMESPACE=liftgate-system

kind create cluster --name liftgate --config - <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  disableDefaultCNI: true
  kubeProxyMode: none
EOF
kind load docker-image --name liftgate liftgate/control-plane:e2e liftgate/dashboard:e2e

kubectl apply --server-side -f "https://github.com/kubernetes-sigs/gateway-api/releases/download/${GATEWAY_API_VERSION}/standard-install.yaml"
helm repo add cilium https://helm.cilium.io/ --force-update
helm repo add jetstack https://charts.jetstack.io --force-update
helm repo add cnpg https://cloudnative-pg.github.io/charts --force-update
helm upgrade --install cilium cilium/cilium --version "$CILIUM_VERSION" \
  --namespace kube-system \
  -f infra/cilium/values.yaml \
  --set k8sServiceHost="$(kubectl -n default get endpointslice kubernetes -o jsonpath='{.endpoints[0].addresses[0]}')" \
  --set ipam.mode=kubernetes \
  --wait --timeout 10m
helm upgrade --install cert-manager jetstack/cert-manager --version "$CERT_MANAGER_VERSION" \
  --namespace cert-manager --create-namespace --set crds.enabled=true --wait --timeout 10m
helm upgrade --install cnpg cnpg/cloudnative-pg --version "$CNPG_VERSION" \
  --namespace cnpg-system --create-namespace --wait --timeout 10m

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f - <<EOF
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: selfsigned
spec:
  selfSigned: {}
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: liftgate-wildcard
  namespace: $NAMESPACE
spec:
  secretName: liftgate-wildcard-tls
  dnsNames:
    - "*.liftgate.app"
  issuerRef:
    kind: ClusterIssuer
    name: selfsigned
EOF

key=$(mktemp)
openssl genrsa -traditional -out "$key" 2048
helm dependency update charts/liftgate
helm upgrade --install liftgate charts/liftgate \
  --namespace "$NAMESPACE" \
  --values e2e/values.yaml \
  --set secrets.masterKey="$(openssl rand -base64 32)" \
  --set-file github.privateKey="$key" \
  --set-string github.appId=1 \
  --set github.clientId=e2e \
  --set github.clientSecret=e2e \
  --set github.webhookSecret=e2e
rm "$key"
kubectl -n "$NAMESPACE" rollout status deployment/liftgate-control-plane --timeout=15m
kubectl -n "$NAMESPACE" rollout status deployment/liftgate-dashboard --timeout=5m
