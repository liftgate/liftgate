# Cluster baseline

Everything the Liftgate chart expects from a cluster, in install order:

| Step | Component | Version | Source |
|---|---|---|---|
| 1 | k3s without flannel, kube-proxy, Traefik and ServiceLB, with kubelet limits | v1.34.8+k3s1 | [`k3s/install.md`](k3s/install.md) |
| 2 | gVisor `runsc` on every node | latest release | [`gvisor/`](gvisor) |
| 3 | Gateway API CRDs | v1.6.1 | [`gateway-api/README.md`](gateway-api/README.md) |
| 4 | Cilium with kube-proxy replacement, Gateway API in host network mode and the bandwidth manager | 1.20.2 | [`cilium/values.yaml`](cilium/values.yaml) |
| 5 | gVisor RuntimeClass `gvisor` | | [`gvisor/runtimeclass.yaml`](gvisor/runtimeclass.yaml) |
| 6 | cert-manager with Gateway API support and ClusterIssuer `letsencrypt` | v1.21.2 | [`cert-manager/clusterissuer.yaml`](cert-manager/clusterissuer.yaml) |
| 7 | CloudNativePG operator | 0.29.0 (operator 1.30) | [`cnpg/README.md`](cnpg/README.md) |
| 8 | Barman Cloud plugin for CloudNativePG backups | chart 0.8.0 (plugin 0.15.0) | [`cnpg/README.md`](cnpg/README.md) |
| 9 | Prometheus as `prometheus.liftgate-system:9090`, with Alertmanager and the alert rules | chart 29.30.1 | [`prometheus/README.md`](prometheus/README.md) |

Steps 1 and 2 run on each node by hand. Steps 3 to 9 are [`install.sh`](install.sh), which needs
`kubectl`, `helm` and `LETSENCRYPT_EMAIL` in the environment:

```sh
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
LIFTGATE_INSTALL_CILIUM=1 LETSENCRYPT_EMAIL=ops@example.com sh infra/install.sh
```

Steps 3 and 4 run only with `LIFTGATE_INSTALL_CILIUM=1`, and then only when every node's
`Ready` condition says `NetworkReady=false`, so the script never installs Cilium over another
CNI. Without it the cluster must already have a GatewayClass; set the chart's
`gateway.className` to it. Every step is skipped when its component is already there (the
Gateway API CRDs, the `cilium` DaemonSet, the `gvisor` RuntimeClass, the cert-manager controller
Deployment, the `letsencrypt` ClusterIssuer, the CloudNativePG operator Deployment, the Service
that registers the Barman Cloud plugin, the `prometheus` Service), and nothing that exists is
upgraded, so a second run changes nothing. CRDs left behind by an uninstalled cert-manager or
CloudNativePG do not count. The plugin is installed into the namespace the CloudNativePG
operator runs in, and the script stops if the operator runs in several namespaces or the plugin
runs in another one. Upgrade a component with its own `helm upgrade`. A cert-manager that was
already there needs Gateway API support (`config.gatewayAPI.enabled=true`) for the `letsencrypt`
issuer to solve challenges.

`LIFTGATE_GATEWAY_NAME` and `LIFTGATE_GATEWAY_NAMESPACE` (default `liftgate` in
`liftgate-system`) name the Gateway the `letsencrypt` ClusterIssuer solves HTTP-01 challenges
through; match them to the chart's `gateway.name` and release namespace.

The bandwidth manager enforces the `kubernetes.io/egress-bandwidth` annotation that plans with an
`egressBandwidth` put on tenant pods. A Cilium installed before it was part of the values gets it
with:

```sh
helm upgrade cilium cilium --repo https://helm.cilium.io/ --version 1.20.2 -n kube-system --reuse-values --set bandwidthManager.enabled=true
kubectl -n kube-system rollout restart daemonset/cilium
```

Set `K8S_API_HOST` when the API server is not the first address of the `kubernetes`
EndpointSlice (multi-server clusters behind a load balancer).

Then install the published chart `oci://ghcr.io/liftgate/charts/liftgate`, documented in
[`charts/liftgate`](../charts/liftgate). NATS is installed by the
chart (`nats.managed=true`); [`nats/values.yaml`](nats/values.yaml) is for running NATS outside
the release:

```sh
helm repo add nats https://nats-io.github.io/k8s/helm/charts/
helm upgrade --install nats nats/nats --version 2.14.6 -n liftgate-system -f infra/nats/values.yaml
```

and then `--set nats.managed=false --set nats.externalUrl=nats://nats.liftgate-system.svc:4222`
on the chart.

## Not covered

A container registry for build output, unless the chart runs one (`inClusterRegistry`; otherwise
`registry` in the chart, and [`registry/`](registry) has a token-auth configuration for another
machine), DNS records, the wildcard certificate for the deploy domain
([`cert-manager/wildcard.yaml`](cert-manager/wildcard.yaml) is a DNS-01 recipe), the object store
for PostgreSQL backups, and backups of JetStream volumes.
[`documentation/self-hosting.md`](../documentation/self-hosting.md) sets up the registry, the DNS
records and the certificate on a fresh VM. PostgreSQL backup, restore and the upgrade procedure are in
[`cnpg/README.md`](cnpg/README.md) and [`UPGRADE.md`](UPGRADE.md). What to do when an alert
fires is in [`RUNBOOK.md`](RUNBOOK.md).
