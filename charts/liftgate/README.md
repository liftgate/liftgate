# liftgate

Helm chart for the Liftgate control plane, dashboard, database, message bus and gateway.

## Prerequisites

The chart expects the cluster baseline from [`infra/`](../../infra): Gateway API CRDs, a
Gateway controller (Cilium by default), cert-manager with Gateway API support and a
`letsencrypt` ClusterIssuer, the CloudNativePG operator and a Prometheus reachable at
`prometheusUrl`. Build jobs push to `registry`; nodes must be able to pull from it.

You also need a GitHub App (webhook URL `<publicUrl>/api/v1/webhooks/github`, OAuth callback
`<publicUrl>/api/v1/auth/github/callback`) and a random `secrets.masterKey`:

```sh
MASTER_KEY=$(openssl rand -base64 32)
```

`secrets.masterKey` encrypts every environment variable stored by Liftgate. Losing it makes
them unrecoverable; back it up.

## Install

```sh
helm dependency update charts/liftgate
helm upgrade --install liftgate charts/liftgate \
  --namespace liftgate-system --create-namespace \
  --set deployDomain=apps.example.com \
  --set publicUrl=https://liftgate.example.com \
  --set secrets.masterKey="$MASTER_KEY" \
  --set github.appId=123456 \
  --set github.clientId=Iv1.abc \
  --set github.clientSecret="$GITHUB_CLIENT_SECRET" \
  --set github.webhookSecret="$GITHUB_WEBHOOK_SECRET" \
  --set-file github.privateKey=github-app.pem
```

Add `-f charts/liftgate/values-ha.yaml` for the `ha` profile. Values files keep secrets out of
shell history; pass them with `-f` instead of `--set` in production.

## Profiles

| | `single` | `ha` |
|---|---|---|
| Control plane | one Deployment, `LIFTGATE_ROLE=all` | `api` x3, `reconciler` x2, `builder` x2, `meter` x1 (`controlPlane.replicas`) |
| Dashboard | 1 replica | 2 replicas |
| PostgreSQL (`postgres.managed`) | 1 instance | 3 instances |
| NATS (`nats.managed`) | 1 server | 3 servers via `values-ha.yaml` |
| Hazelcast | local | Kubernetes discovery through the `<fullname>-hazelcast` headless Service |

The NATS replica count lives in the subchart and cannot follow `profile` on its own, which is
why `values-ha.yaml` exists. The chart prints a warning when `profile=ha` runs one NATS server.

## Routing and TLS

With `gateway.create=true` the chart renders a Gateway API `Gateway` named `gateway.name` in
the release namespace with three kinds of listener:

- `http` on port 80: redirects to HTTPS and serves cert-manager HTTP-01 challenges from any
  namespace.
- `https-apps` on port 443 for `*.deployDomain`: tenant HTTPRoutes attach here. The
  listener reads TLS secret `gateway.wildcardSecret`; issue it with a DNS-01 `Certificate`
  for your DNS provider (HTTP-01 cannot issue wildcards).
- one HTTPS listener per distinct host in `publicUrl` and `dashboardUrl`, each with a
  cert-manager `Certificate` from ClusterIssuer `gateway.issuer`.

When `publicUrl` and `dashboardUrl` share a host, `/api` goes to the
control plane and everything else to the dashboard. With different hosts each host routes
`/` to its component, and the dashboard image must be built with
`--build-arg NEXT_PUBLIC_API_URL=<publicUrl>` because Next.js inlines that variable into the
browser bundle at build time; the published image uses same-host relative URLs. The control
plane then allows credentialed cross-origin requests from the `dashboardUrl` origin only.

Verified custom domains get their own HTTPS listener: the reconciler server-side applies one
listener per domain onto the Gateway (field manager `liftgate`) and keeps the matching
cert-manager `Certificate` beside the Gateway. A Gateway holds at most 64 listeners, which
caps custom domains per cluster. `helm upgrade` rewrites the listener list; the next release
or domain change restores the custom-domain listeners.

## External PostgreSQL and NATS

```yaml
postgres:
  managed: false
  externalUrl: jdbc:postgresql://db.example.com:5432/liftgate
  externalUser: liftgate
  externalPassword: change-me
nats:
  managed: false
  externalUrl: nats://nats.example.com:4222
```

PostgreSQL 16 or newer; NATS 2.10 or newer with JetStream enabled.

## Values

| Key | Default | Description |
|---|---|---|
| `profile` | `single` | `single` or `ha` |
| `imagePullSecrets` | `[]` | Pull secrets for both images |
| `controlPlane.image` | `ghcr.io/liftgate/control-plane` | |
| `controlPlane.tag` | `0.1.0` | |
| `controlPlane.replicas.{api,reconciler,builder,meter}` | `3,2,2,1` | Replicas per role in `ha` |
| `controlPlane.logLevel` | `INFO` | `LIFTGATE_LOG_LEVEL` |
| `controlPlane.javaOpts` | `-XX:MaxRAMPercentage=75.0` | `JAVA_TOOL_OPTIONS` |
| `controlPlane.resources` | 250m / 768Mi, limit 1536Mi | |
| `dashboard.image` | `ghcr.io/liftgate/dashboard` | |
| `dashboard.tag` | `0.1.0` | |
| `dashboard.resources` | 100m / 128Mi, limit 256Mi | |
| `postgres.managed` | `true` | Render a CloudNativePG `Cluster` |
| `postgres.externalUrl` | `""` | JDBC URL when not managed |
| `postgres.externalUser` | `""` | |
| `postgres.externalPassword` | `""` | |
| `postgres.storage` | `10Gi` | Volume per instance |
| `nats.managed` | `true` | Install the `nats` subchart |
| `nats.externalUrl` | `""` | NATS URL when not managed |
| `nats.config.*` | JetStream on, 5Gi | Passed through to the nats chart |
| `nats.natsBox.enabled` | `false` | Set `true` for a `nats` CLI pod to inspect streams |
| `gateway.create` | `true` | Render Gateway, HTTPRoutes and Certificates |
| `gateway.name` | `liftgate` | `LIFTGATE_GATEWAY_NAME` |
| `gateway.namespace` | `""` | `LIFTGATE_GATEWAY_NAMESPACE` for an existing Gateway when `gateway.create=false`; empty or `gateway.create=true` means the release namespace |
| `gateway.className` | `cilium` | |
| `gateway.wildcardSecret` | `liftgate-wildcard-tls` | TLS secret for `*.deployDomain` |
| `gateway.issuer` | `letsencrypt` | ClusterIssuer for the public hosts |
| `deployDomain` | `liftgate.app` | `LIFTGATE_DEPLOY_DOMAIN` |
| `publicUrl` | `https://liftgate.dev` | `LIFTGATE_PUBLIC_URL` |
| `dashboardUrl` | `""` | `LIFTGATE_DASHBOARD_URL`; empty means `publicUrl` |
| `github.appId` | `""` | `LIFTGATE_GITHUB_APP_ID` |
| `github.clientId` | `""` | `LIFTGATE_GITHUB_CLIENT_ID` |
| `github.clientSecret` | `""` | `LIFTGATE_GITHUB_CLIENT_SECRET` |
| `github.webhookSecret` | `""` | `LIFTGATE_GITHUB_WEBHOOK_SECRET` |
| `github.privateKey` | `""` | `LIFTGATE_GITHUB_APP_PRIVATE_KEY`, PEM |
| `secrets.masterKey` | `""` | `LIFTGATE_SECRETS_MASTER_KEY`, base64 of 32 bytes |
| `registry` | `registry.liftgate.internal` | `LIFTGATE_REGISTRY` |
| `build.image` | `ghcr.io/liftgate/build-image:latest` | `LIFTGATE_BUILD_IMAGE` |
| `build.namespace` | `liftgate-build` | `LIFTGATE_BUILD_NAMESPACE`; the chart creates it |
| `build.allowedEgressCidrs` | `[]` | Private CIDRs build jobs may reach, for an in-cluster registry; everything else private is blocked |
| `build.registryCredentials` | `""` | Docker `config.json` content; rendered as Secret `registry-credentials` in `build.namespace` and mounted by build jobs |
| `runtimeClass` | `""` | `LIFTGATE_RUNTIME_CLASS`, set `gvisor` on shared clusters |
| `prometheusUrl` | `http://prometheus.liftgate-system:9090` | `LIFTGATE_PROMETHEUS_URL` |

Derived variables: `LIFTGATE_DATABASE_URL` points at the CloudNativePG `-rw` Service (or
`postgres.externalUrl`), `LIFTGATE_DATABASE_USER` and `LIFTGATE_DATABASE_PASSWORD` come from
the CloudNativePG `<fullname>-postgres-app` Secret (or the `external*` values; `<fullname>`
is the release name when it contains `liftgate`, otherwise `<release>-liftgate`),
`LIFTGATE_NATS_URL` points at the subchart Service (or `nats.externalUrl`),
`LIFTGATE_HAZELCAST_KUBERNETES` is `true` in `ha`, `LIFTGATE_GATEWAY_NAMESPACE` is the release
namespace, `LIFTGATE_LEADER_ELECTION` is `kubernetes` and `LIFTGATE_HTTP_PORT` is `8080`.
Empty optional values are left out of the ConfigMap and Secret so the control plane reports
missing configuration instead of running with blank secrets.

## Build namespace

`build.namespace` is created with `pod-security.kubernetes.io/enforce: privileged` because
rootless BuildKit needs unconfined seccomp and AppArmor profiles. Do not schedule anything
else there.

## Uninstall

```sh
helm uninstall liftgate --namespace liftgate-system
```

The CloudNativePG cluster and its volumes are deleted with the release; take a backup first.
