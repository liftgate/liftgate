# Cluster baseline

Everything the Liftgate chart expects from a cluster, in install order:

| Step | Component | Version | Source |
|---|---|---|---|
| 1 | k3s without flannel, kube-proxy, Traefik and ServiceLB | latest stable | [`k3s/install.md`](k3s/install.md) |
| 2 | gVisor `runsc` on every node | latest release | [`gvisor/`](gvisor) |
| 3 | Gateway API CRDs | v1.6.1 | [`gateway-api/README.md`](gateway-api/README.md) |
| 4 | Cilium with kube-proxy replacement and Gateway API in host network mode | 1.20.2 | [`cilium/values.yaml`](cilium/values.yaml) |
| 5 | gVisor RuntimeClass `gvisor` | | [`gvisor/runtimeclass.yaml`](gvisor/runtimeclass.yaml) |
| 6 | cert-manager with Gateway API support and ClusterIssuer `letsencrypt` | v1.21.2 | [`cert-manager/clusterissuer.yaml`](cert-manager/clusterissuer.yaml) |
| 7 | CloudNativePG operator | 0.29.0 (operator 1.30) | [`cnpg/README.md`](cnpg/README.md) |
| 8 | Prometheus as `prometheus.liftgate-system:9090` | chart 29.30.1 | [`prometheus/values.yaml`](prometheus/values.yaml) |

Steps 1 and 2 run on each node by hand. Steps 3 to 8 are [`install.sh`](install.sh), which is
idempotent and needs `kubectl`, `helm` and `LETSENCRYPT_EMAIL` in the environment:

```sh
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
LETSENCRYPT_EMAIL=ops@example.com sh infra/install.sh
```

Set `K8S_API_HOST` when the API server is not the first address of the `kubernetes`
EndpointSlice (multi-server clusters behind a load balancer).

Then install the chart from [`charts/liftgate`](../charts/liftgate). NATS is installed by the
chart (`nats.managed=true`); [`nats/values.yaml`](nats/values.yaml) is for running NATS outside
the release:

```sh
helm repo add nats https://nats-io.github.io/k8s/helm/charts/
helm upgrade --install nats nats/nats --version 2.14.6 -n liftgate-system -f infra/nats/values.yaml
```

and then `--set nats.managed=false --set nats.externalUrl=nats://nats.liftgate-system.svc:4222`
on the chart.

## Not covered

A container registry for build output (`registry` in the chart), DNS records, and backups for
PostgreSQL and JetStream volumes.
