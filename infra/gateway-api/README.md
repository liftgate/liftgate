# Gateway API

Cilium 1.20 requires Gateway API v1.6.1 and reads the CRDs when it starts, so they are
installed before Cilium. `install.sh` applies the standard channel CRDs Cilium lists as
mandatory:

```sh
for crd in gatewayclasses gateways httproutes referencegrants grpcroutes backendtlspolicies tlsroutes; do
  kubectl apply --server-side -f "https://raw.githubusercontent.com/kubernetes-sigs/gateway-api/v1.6.1/config/crd/standard/gateway.networking.k8s.io_${crd}.yaml"
done
```

Cilium creates the `cilium` GatewayClass itself. The Liftgate chart renders the `Gateway`
(`liftgate` in `liftgate-system`), the HTTPRoutes for the control plane and dashboard, and the
reconciler creates one HTTPRoute per tenant service in its environment namespace.

If you need experimental fields, install the experimental channel of the same version instead;
never mix channels or versions, and read the Cilium upgrade notes before moving to a newer
Gateway API release.
