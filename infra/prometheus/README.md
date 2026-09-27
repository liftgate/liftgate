# Prometheus

[`install.sh`](../install.sh) installs the prometheus-community `prometheus` chart as release
`prometheus` in `liftgate-system` with [`values.yaml`](values.yaml) and the alert rules in
[`rules.yaml`](rules.yaml). It runs Prometheus with 15 days of retention, capped at 6 GB,
Alertmanager, kube-state-metrics and node-exporter. kube-state-metrics adds
`label_liftgate_dev_org_id` and `label_liftgate_dev_service_id` to `kube_pod_labels`, and
`label_cnpg_io_cluster` to `kube_persistentvolumeclaim_labels`. Every alert has a section in
[`RUNBOOK.md`](../RUNBOOK.md).

## Targets

| Target | Found by |
|---|---|
| Control plane `/metrics` on 8080 | `prometheus.io/scrape` pod annotations, job `kubernetes-pods` |
| NATS | the chart's `liftgate-nats-exporter` Deployment on 7777, through pod annotations; a NATS installed with [`nats/values.yaml`](../nats/values.yaml) runs the same exporter as a sidecar |
| CloudNativePG instances on 9187 | job `cnpg`: running pods with a `cnpg.io/cluster` label and a container port named `metrics` |
| kube-state-metrics, node-exporter | `prometheus.io/scrape` Service annotations, job `kubernetes-service-endpoints` |
| cert-manager | the pod annotations its chart sets |
| kubelet and cAdvisor | jobs `kubernetes-nodes` and `kubernetes-nodes-cadvisor` |

The release namespace denies ingress by default. The chart's `liftgate-prometheus`
NetworkPolicy lets Prometheus server pods in that namespace reach ports 7777, 8080, 8081, 9093
and 9187 of every pod there; a Prometheus in another namespace needs its own policy.

```sh
kubectl -n liftgate-system port-forward service/prometheus 9090:9090 &
curl -s localhost:9090/api/v1/targets | jq -r '.data.activeTargets[] | [.labels.job, .scrapeUrl, .health] | @tsv'
```

[`e2e/checks/60-monitoring.sh`](../../e2e/checks/60-monitoring.sh) checks in CI that these
targets are up and that the series most rules read exist.

VolumeFilling needs `kubelet_volume_stats_*`, which the kubelet reports only for volume types
that provide metrics. `hostPath` volumes do not (`MetricsNil` in the kubelet's hostpath plugin),
and they are what k3s's default `local-path` storage class creates. Such volumes are directories
on the node's filesystem, which NodeFilesystemLow watches instead.

## Upgrading

`install.sh` never touches a Prometheus that is already installed. Apply changes to values or
rules from a checkout of this repository, with the chart version from `install.sh`:

```sh
helm upgrade prometheus prometheus --repo https://prometheus-community.github.io/helm-charts --version 29.30.1 \
  -n liftgate-system -f infra/prometheus/values.yaml \
  --set-file ruleFiles.liftgate=infra/prometheus/rules.yaml \
  --set alertmanager.config.route.receiver=discord \
  --wait --timeout 10m
```

## Alert delivery

Alertmanager has three receivers, `none` (the default), `discord` and `ntfy`, and sends every
alert to the one named by `alertmanager.config.route.receiver`. Their URLs come from the
optional Secret `liftgate-alerts`, so they appear in no values file or ConfigMap:

```sh
kubectl -n liftgate-system create secret generic liftgate-alerts \
  --from-literal=discord-webhook-url='https://discord.com/api/webhooks/<id>/<token>' \
  --from-literal=ntfy-url='https://ntfy.example.com/liftgate-alerts?template=alertmanager'
```

Only the key of the chosen receiver is needed. The Discord URL is a channel webhook (channel
settings, Integrations, Webhooks). `template=alertmanager` makes ntfy 2.14 or newer format
Alertmanager's payload into a title and message. Set the receiver with the upgrade command
above. If the Secret did not exist when Alertmanager started, restart it, then send a test
alert:

```sh
kubectl -n liftgate-system rollout restart statefulset/prometheus-alertmanager
kubectl -n liftgate-system exec statefulset/prometheus-alertmanager -- \
  amtool alert add LiftgateTest severity=warning --annotation=summary='test alert' --alertmanager.url=http://localhost:9093
```

## Rules

[`rules.yaml`](rules.yaml) is a plain Prometheus rule file. [`rules.test.yaml`](rules.test.yaml)
holds promtool unit tests for OutboxStuck, PostgresDown, VolumeFilling and OrgCpuSaturated.
`chart.yml` runs both in CI:

```sh
promtool check rules infra/prometheus/rules.yaml
(cd infra/prometheus && promtool test rules rules.test.yaml)
```

## gVisor and cAdvisor

With gVisor, cAdvisor may not report per-container series for `runsc` pods
([gVisor issue 13067](https://github.com/google/gvisor/issues/13067)). OrgCpuSaturated reads the
pod-level series (`container=""`); metering reads the per-container ones (`container!=""`). On a
cluster whose tenant pods run under `runsc`, compare the tenant pod count with both:

```sh
q() { curl -s --get localhost:9090/api/v1/query --data-urlencode "query=$1" | jq -r '.data.result[0].value[1] // "0"'; }
q 'count(kube_pod_info{namespace=~"env-.+"})'
q 'count(container_cpu_usage_seconds_total{namespace=~"env-.+", container="", pod!=""})'
q 'count(container_cpu_usage_seconds_total{namespace=~"env-.+", container!="", container!="POD"})'
```

If the per-container count is lower than the pod count, metering and the service metrics must
use the pod-level series. If the pod-level count is lower too, OrgCpuSaturated cannot fire for
those pods.

| Date | Cluster | Tenant pods | Pod-level series | Per-container series |
|---|---|---|---|---|
| not yet recorded | Liftgate Cloud | | | |

## Certificates

CertificateExpiring reads cert-manager's `certmanager_certificate_expiration_timestamp_seconds`,
so it covers only certificates cert-manager issues. The chart does not create
`gateway.wildcardSecret`; if it was made by hand, nothing watches its expiry. This prints the
owning Certificate, and nothing for a hand-made Secret:

```sh
kubectl -n liftgate-system get secret liftgate-wildcard-tls -o jsonpath='{.metadata.annotations.cert-manager\.io/certificate-name}'
```

For a gateway behind a proxy that verifies it, issue the wildcard from a cert-manager CA and
add that CA to the proxy's upstream trust pool:

```sh
kubectl apply -f - <<'EOF'
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
  name: liftgate-gateway-ca
  namespace: cert-manager
spec:
  isCA: true
  commonName: liftgate-gateway-ca
  secretName: liftgate-gateway-ca
  duration: 87600h
  issuerRef:
    kind: ClusterIssuer
    name: selfsigned
---
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: liftgate-gateway-ca
spec:
  ca:
    secretName: liftgate-gateway-ca
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: liftgate-wildcard
  namespace: liftgate-system
spec:
  secretName: liftgate-wildcard-tls
  dnsNames:
    - "*.liftgate.app"
  issuerRef:
    kind: ClusterIssuer
    name: liftgate-gateway-ca
EOF
kubectl -n cert-manager get secret liftgate-gateway-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > liftgate-gateway-ca.crt
```

## Canary

[`canary.yml`](../../.github/workflows/canary.yml) runs on GitHub-hosted runners, only in
`liftgate/liftgate`. Every 10 minutes it requests `https://liftgate.dev/api/v1/auth/providers`
and `CANARY_URL`; once a day it pushes a commit that changes `index.html` in
`CANARY_REPOSITORY` and waits up to 10 minutes for `CANARY_URL` to serve the new content. Each
part skips when its settings are missing, so forks run nothing.

| Setting | Kind | Value |
|---|---|---|
| `CANARY_URL` | variable | URL of the canary app on Liftgate Cloud |
| `CANARY_REPOSITORY` | variable | `owner/name` of the canary repository |
| `CANARY_DEPLOY_KEY` | secret | private half of an SSH deploy key with write access to that repository |
| `ALERT_DISCORD_WEBHOOK_URL` | secret | Discord webhook for failures |
| `ALERT_NTFY_URL` | secret | ntfy topic URL for failures, without `?template=` |

The canary repository needs an `index.html` and this `Dockerfile`, imported on Liftgate Cloud
as a web service on port 8080 that deploys its default branch:

```dockerfile
FROM busybox:1.37
COPY index.html /www/index.html
CMD ["httpd", "-f", "-p", "8080", "-h", "/www"]
```
