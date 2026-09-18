<p align="center"><img src=".github/assets/logo.png" alt="Liftgate" width="96" height="96"></p>

# Liftgate

Open-source full-stack application hosting. Connect a GitHub repository and Liftgate builds a container image on every push and runs it on Kubernetes, with a hostname on `liftgate.app`, custom domains, environment variables, logs and rollbacks. The same software runs [Liftgate Cloud](https://liftgate.dev) and self-hosted installs.

> **Status: pre-alpha.** Liftgate is being built towards its first milestone. The API, the database schema and the chart values change without notice, there are no tagged releases or published images yet, and nothing has run in production. Read the code, try it on a cluster you can throw away, open issues.

## What it does

- Signs users in with GitHub, Google, GitLab, Bitbucket, passkeys, emailed one-time codes or an organisation's SAML identity provider.
- Builds from GitHub on push with BuildKit, using your Dockerfile or a Railpack build plan when there is none.
- Runs web services, workers and cron jobs on Kubernetes with a platform hostname, verified custom domains and Let's Encrypt certificates.
- Keeps environment variables encrypted at rest with AES-256-GCM and injects them as Kubernetes Secrets.
- Streams build and service logs live, keeps deployment history and rolls back to any earlier successful build.
- Isolates tenants: one namespace per environment, default-deny network policies, the restricted pod security profile, gVisor on shared clusters.
- Meters CPU, memory and network per service from Prometheus.
- Installs as one Helm chart: the `single` profile for a laptop or a small VPS, the `ha` profile for a real cluster.

## Architecture

```
  GitHub: OAuth login, push webhooks                  developer: browser, API token
                  |                                                 |
                  v                                                 v
          +---------------+          REST /api/v1           +---------------+
          |      api      |<--------------------------------|   dashboard   |
          +-------+-------+       WebSocket log streams     +---------------+
                  | state change and outbox row in one transaction
                  v
          +---------------+      outbox relay (leader)      +----------------+
          |  PostgreSQL   |-------------------------------->| NATS JetStream |
          +---------------+                                 +-------+--------+
                  ^                                                 | durable pull consumers
                  | build, deployment and usage updates             |
          +-------+-------------------------------------------------+------+
          |       builder                reconciler                meter   |
          +-------+-----------------------------+-----------------------^--+
                  | Kubernetes Job              | server-side apply     |
                  | BuildKit + Railpack         | into env-<id>         | PromQL
                  v                             v                       |
          +---------------+   pull   +------------------------+   +-----+------+
          |   registry    |--------->| tenant workloads       |-->| Prometheus |
          +---------------+          | Deployment or CronJob  |   +------------+
                                     | Secret, Service        |
                                     | HTTPRoute              |
                                     | NetworkPolicy          |
                                     | Certificate            |
                                     +-----------+------------+
                                                 | Gateway API, cert-manager
                                                 v
                          https://<service>-<project>-<org>.liftgate.app
```

One Kotlin/JVM artifact runs every role; `LIFTGATE_ROLE` selects `api`, `reconciler`, `builder`, `meter` or `all`.

| Role | Responsibility |
|---|---|
| `api` | REST API under `/api/v1`, sign-in and sessions, GitHub webhooks, WebSocket log streams, the outbox relay under leader election. |
| `builder` | Turns a queued build into a Kubernetes Job that clones the commit, builds with BuildKit and pushes the image, streaming its logs. |
| `reconciler` | Server-side applies namespaces, Deployments, CronJobs, Secrets, Services, HTTPRoutes, NetworkPolicies and Certificates, and tracks rollouts. |
| `meter` | Queries Prometheus every minute and records CPU, memory and network usage per service. |

Postgres decides, NATS carries, Hazelcast remembers. Every state change writes an outbox row in the same transaction; a leader-elected relay publishes the rows to the JetStream stream `LIFTGATE`; durable consumers do the work idempotently; embedded Hazelcast caches sessions across control plane pods.

The API speaks JSON under `/api/v1`, authenticated by the session cookie or `Authorization: Bearer lg_<token>`; an organization admin mints a token with `POST /api/v1/orgs/{slug}/tokens {"name": "ci"}`, and the response shows it once. Health is at `/healthz` and `/readyz`, Prometheus metrics at `/metrics`.

Stack: Kotlin 2.4, Ktor 3.6, Exposed 1.5, Flyway, PostgreSQL 16, NATS JetStream, Hazelcast 5.7, fabric8 7.9; Next.js 16, React 19, Tailwind 4; Kubernetes with Cilium, Gateway API, cert-manager, CloudNativePG and gVisor.

## Self-hosting

### Prerequisites

- A Kubernetes cluster with the baseline under `infra/`. Install k3s and gVisor by hand following `infra/k3s/install.md`, then run `LETSENCRYPT_EMAIL=you@example.com sh infra/install.sh`, which installs the Gateway API CRDs, Cilium, the gVisor RuntimeClass, cert-manager, CloudNativePG and Prometheus in order. NATS comes with the Liftgate chart.
- A container registry that build jobs can push to and the nodes can pull from: set `registry`, and `build.registryCredentials` when it needs a login. The default `registry.liftgate.internal` does not exist.
- A TLS secret named `liftgate-wildcard-tls` in the release namespace for `*.<deployDomain>`, issued with a DNS-01 `Certificate` for your DNS provider; the `https-apps` listener reads it.
- Helm (CI uses 4.3.0).
- A wildcard DNS record for the app domain (`*.apps.example.com`) and a record for the control plane hostname (`liftgate.example.com`), both pointing at the gateway's address.
- A GitHub App, described below.

### Install

Images are published to GHCR on tagged releases. Until the first tag exists, build them yourself from `control-plane/`, `dashboard/` and `build-image/` and point the chart's image values at your registry.

```sh
git clone https://github.com/liftgate/liftgate.git
cd liftgate
helm dependency update charts/liftgate
helm install liftgate charts/liftgate --namespace liftgate-system --create-namespace --values my-values.yaml
```

A minimal `my-values.yaml`:

```yaml
profile: single
deployDomain: apps.example.com
publicUrl: https://liftgate.example.com
github:
  appId: "123456"
  clientId: "Iv1.abc"
  clientSecret: "..."
  webhookSecret: "..."
  privateKey: |
    -----BEGIN RSA PRIVATE KEY-----
    ...
    -----END RSA PRIVATE KEY-----
secrets:
  masterKey: "..."
```

Generate `masterKey` with `openssl rand -base64 32`. `dashboardUrl` defaults to `publicUrl`, which serves the dashboard and the API on one host. With `postgres.managed` and `nats.managed` left at `true` the chart renders a CloudNativePG cluster and installs the NATS chart; set `externalUrl` on either to bring your own. For high availability install with `--values charts/liftgate/values-ha.yaml`: it sets `profile: ha` (three `api`, two `reconciler`, two `builder`, one `meter` replica and a three-instance database) and turns on the three-server NATS cluster, which `profile: ha` alone leaves at one server. Every value is documented in `charts/liftgate/README.md`.

### GitHub App

Liftgate signs users in and reads repositories through one GitHub App. Create it under Settings, Developer settings, GitHub Apps, with:

- Callback URL: `<publicUrl>/api/v1/auth/github/callback`
- Webhook URL: `<publicUrl>/api/v1/webhooks/github`, with a webhook secret
- Repository permissions: Contents read-only, Metadata read-only
- Account permissions: Email addresses read-only
- Subscribe to events: Push

Generate a private key, then install the app on the account or organisation whose repositories you want to deploy. The App ID, client ID, client secret, webhook secret and private key go into the chart's `github` values, or into `control-plane/.env` for local development. A new project asks only for the repository name: Liftgate looks up the installation itself and accepts the repository only when the signed-in GitHub account can push to it.

### Other sign-in methods

Google, GitLab, Bitbucket, email codes and passkeys are optional and configured with chart values; SAML single sign-on is set up per organisation by its owner in the dashboard. `charts/liftgate/README.md` under Sign-in providers lists where to create each set of credentials and which callback URL to register. A user who signs in without GitHub connects a GitHub account from Account before importing a repository.

## Local development

PostgreSQL and NATS come from Compose; the control plane and the dashboard run on the host.

```sh
docker compose up -d
```

Control plane:

```sh
cd control-plane
cp .env.example .env
set -a; . ./.env; set +a
./gradlew run
```

Fill in the GitHub App values and generate `LIFTGATE_SECRETS_MASTER_KEY` with `openssl rand -base64 32` before starting. The private key is multi-line; because the file is sourced by the shell, `LIFTGATE_GITHUB_APP_PRIVATE_KEY="$(cat path/to/key.pem)"` is the easiest way to set it. Any IDE run configuration that sets the same variables works too.

The other sign-in methods stay off while their variables are empty. Passkeys work on `http://localhost:3000` with no setup. Quote `LIFTGATE_EMAIL_FROM` in `.env` (`LIFTGATE_EMAIL_FROM="Liftgate <login@example.com>"`), because the shell reads `<` as a redirect. OAuth providers need `http://localhost:8080/api/v1/auth/<provider>/callback` registered as an extra redirect URI.

`.env.example` sets `LIFTGATE_ROLE=api` and `LIFTGATE_LEADER_ELECTION=off`, which needs no cluster. The `builder`, `reconciler` and `meter` roles (and `all`) need a kubeconfig for a cluster that has the `infra/` baseline. GitHub cannot deliver webhooks to localhost, so trigger builds with `POST /api/v1/services/{id}/deploy` (`ref` is optional and defaults to the head of the environment branch), or run a tunnel.

`./gradlew build` compiles and runs the tests; the repository and HTTP tests use Testcontainers and skip when Docker is not available.

Dashboard:

```sh
cd dashboard
npm ci
npm run dev
```

It serves on http://localhost:3000 and in development rewrites `/api/*` to the control plane on http://localhost:8080, so the session cookie stays same-origin. `npm run lint && npm test && npm run build` is what CI runs.

Chart:

```sh
helm dependency update charts/liftgate
helm lint charts/liftgate --set profile=single
helm template liftgate charts/liftgate --values charts/liftgate/values-ha.yaml
```

## Repository layout

```
liftgate/
  control-plane/        Kotlin/JVM service, Gradle. One artifact, four roles.
  dashboard/            Next.js 16, TypeScript, Tailwind 4.
  charts/liftgate/      Helm chart, profiles single and ha.
  infra/                Cluster baseline: k3s, Cilium, gVisor, cert-manager, Gateway API, CNPG, NATS.
  build-image/          Dockerfile for the build job image (BuildKit rootless + Railpack + git).
  .github/              CI workflows, issue and PR templates, CODEOWNERS.
  compose.yaml          PostgreSQL 16 and NATS with JetStream for local development.
```

CI runs on GitHub Actions: `control-plane.yml` builds and tests with Gradle on JDK 21, `dashboard.yml` lints and builds on Node 22, `chart.yml` lints and templates both profiles, and `images.yml` pushes the three images to GHCR on tags matching `v*`.

## Roadmap

Milestone 1, foundation, is in progress: connect a repository, build on push, release to Kubernetes, reach the service on a platform hostname or a custom domain, manage it from the dashboard, roll back, meter usage, install with Helm in `single` or `ha`.

After milestone 1, in no particular order: preview environments per pull request, sleeping and autoscaling, object storage as a product, a CLI, bring-your-own clusters, multi-region, device VPN, billing enforcement.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) for the house rules, the commit format and the pull request checklist. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Licence

Liftgate is licensed under the [GNU Affero General Public License v3.0](LICENSE).
