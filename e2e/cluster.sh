#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

NAMESPACE=liftgate-system
CHART="${CHART:-charts/liftgate}"

kind create cluster --name liftgate --config - <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  disableDefaultCNI: true
  kubeProxyMode: none
EOF
kind load docker-image --name liftgate liftgate/control-plane:e2e liftgate/dashboard:e2e ${EXTRA_IMAGES:-}

LIFTGATE_INSTALL_CILIUM=1 LETSENCRYPT_EMAIL=e2e@liftgate.test sh infra/install.sh

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
helm dependency update "$CHART"
helm upgrade --install liftgate "$CHART" \
  --namespace "$NAMESPACE" \
  --values "${VALUES:-e2e/values.yaml}" \
  --set secrets.masterKey="$(openssl rand -base64 32)" \
  --set-file github.privateKey="$key" \
  --set-string github.appId=1 \
  --set github.clientId=e2e \
  --set github.clientSecret=e2e \
  --set github.webhookSecret=e2e \
  "$@"
rm "$key"
kubectl -n "$NAMESPACE" rollout status deployment/liftgate-control-plane --timeout=15m
kubectl -n "$NAMESPACE" rollout status deployment/liftgate-dashboard --timeout=5m
