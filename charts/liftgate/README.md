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

Google, GitLab, Bitbucket, email codes, passkeys and SAML sign-in are optional; see
[Sign-in providers](#sign-in-providers).

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

## Sign-in providers

GitHub sign-in always works through the GitHub App above. Every other method is optional and
turns on when its values are set; the login page shows only the enabled ones. Client IDs go
to the ConfigMap, client secrets and the SMTP URL to the Secret, and empty values are left
out. Callback URLs are built from `publicUrl` and must match character for character.

A user who signs in with a new method is linked to an existing account only when the
provider reports the email address as verified and exactly one account has it. Anyone can add
or remove methods under Account in the dashboard; the last one cannot be removed.

### Google

1. In the Google Cloud console, open Google Auth Platform and fill in Branding and Audience
   (External for anyone with a Google account, Internal for a Workspace organisation only).
2. Under Clients, choose Create client, application type Web application.
3. Add the authorized redirect URI `<publicUrl>/api/v1/auth/google/callback`.
4. Copy the client ID and secret; Google shows the secret only once.
5. For an External app, open Audience and choose Publish app. Google starts new apps in
   Testing, where only listed test users can sign in and everyone else sees "access blocked".
   The `openid`, `email` and `profile` scopes need no verification. To stay in Testing, add
   every account that should sign in as a test user.

```yaml
google:
  clientId: 1234567890-abc.apps.googleusercontent.com
  clientSecret: GOCSPX-...
```

### GitLab

Create an application under your avatar, Edit profile, Applications (or a group's Settings,
Applications, or Admin, Applications on a self-managed instance):

- Redirect URI: `<publicUrl>/api/v1/auth/gitlab/callback`
- Confidential: checked
- Scopes: `read_user` only

```yaml
gitlab:
  url: https://gitlab.example.com
  clientId: <Application ID>
  clientSecret: <Secret>
```

Leave `url` empty for gitlab.com.

### Bitbucket

In Bitbucket Cloud open the workspace, Settings, Workspace settings, OAuth consumers, Add
consumer:

- Callback URL: `<publicUrl>/api/v1/auth/bitbucket/callback`
- Permissions: Account, Email and Account, Read

```yaml
bitbucket:
  clientId: <Key>
  clientSecret: <Secret>
```

### Email codes

Liftgate mails a six-digit code valid for ten minutes. It needs an SMTP server and a sender
address, and turns on only when both are set. `smtp://` connects with STARTTLS (usually port
587), `smtps://` with implicit TLS (port 465). Percent-encode reserved characters in the user
name and password (`@` becomes `%40`).

A self-hosted server such as Postfix, Stalwart or Maddy, with a mailbox for Liftgate:

```yaml
email:
  smtpUrl: smtp://liftgate%40example.com:password@mail.example.com:587
  from: Liftgate <login@example.com>
```

A hosted provider on its free tier, here Resend. Verify the sending domain in Resend first;
the user name is `resend` and the password is an API key:

```yaml
email:
  smtpUrl: smtps://resend:re_xxxxxxxx@smtp.resend.com:465
  from: Liftgate <login@example.com>
```

The control plane pods need outbound access to the SMTP port. Publish SPF and DKIM for the
sending domain or the codes land in spam.

### Passkeys

Passkeys need no setup and are always on. The relying party ID defaults to the host of
`dashboardUrl`; `passkeys.rpId` may instead name a parent domain of it (`example.com` for
`app.example.com`). A passkey is bound to the relying party ID it was created under, so
changing `rpId` or the dashboard host later strands every existing passkey.
`passkeys.rpName` is the name the browser shows, `Liftgate` by default.

### SAML single sign-on

SAML is configured per organisation by an owner in the dashboard at
`/<org>/settings/sso`, not in the chart. The page shows two URLs to give the identity
provider (Okta, Microsoft Entra ID, Google Workspace, Keycloak and others):

- SP entity ID and metadata URL: `<publicUrl>/api/v1/auth/sso/<org>/metadata`. Providers that
  import metadata can read everything from it.
- Assertion Consumer Service URL: `<publicUrl>/api/v1/auth/sso/<org>/acs`, HTTP-POST binding.

Configure the SAML application in the identity provider so that it:

- uses the metadata URL above as the audience (entity ID) and the ACS URL as the recipient;
- signs the response, the assertion or both;
- sends the user's email address as the NameID, format `emailAddress`;
- has clocks within three minutes of the cluster.

Then enter the provider's side in Liftgate: its entity ID (issuer), its single sign-on URL
for the HTTP-Redirect binding, its X.509 signing certificate in PEM, the email domains the
organisation owns, and the role new members get (`member` or `admin`). Owners can also use
`PUT /api/v1/orgs/<org>/sso`.

Each email domain must then be verified. The page lists a TXT record per domain: publish the
connection's verification token at `_liftgate.<domain>` and choose Verify domains (or
`POST /api/v1/orgs/<org>/sso/verify`). A domain verified by one organisation cannot be
verified by another.

Members choose Continue with SAML SSO on the login page and enter their work email;
Liftgate finds the organisation by a verified email domain. Until a domain is verified, the
login page cannot find it, and a sign-in started at `<publicUrl>/api/v1/auth/sso/<org>/login`
creates a separate account instead of joining an existing account with the same email. Sign-in
must start from Liftgate and finish in the same browser:
IdP-initiated logins from a provider's app dashboard are rejected because every response has
to answer a request Liftgate sent. Only emails in the configured domains are accepted, and a
first sign-in adds the user to the organisation with the default role.

## Values

| Key | Default | Description |
|---|---|---|
| `profile` | `single` | `single` or `ha` |
| `imagePullSecrets` | `[]` | Pull secrets for both images |
| `controlPlane.image` | `ghcr.io/liftgate/control-plane` | |
| `controlPlane.tag` | `0.1.0` | |
| `controlPlane.replicas.{api,reconciler,builder,meter}` | `3,2,2,1` | Replicas per role in `ha` |
| `controlPlane.logLevel` | `INFO` | `LIFTGATE_LOG_LEVEL` |
| `controlPlane.trustedProxies` | `1` | `LIFTGATE_TRUSTED_PROXIES`: how many proxies append to `X-Forwarded-For` before the API. Rate limits read the client IP this many entries from the right. Count the gateway and every proxy in front of it, each of which must keep the incoming header (Caddy needs `trusted_proxies`); `0` uses the connection address |
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
| `google.clientId` | `""` | `LIFTGATE_GOOGLE_CLIENT_ID` |
| `google.clientSecret` | `""` | `LIFTGATE_GOOGLE_CLIENT_SECRET`, Secret |
| `gitlab.url` | `""` | `LIFTGATE_GITLAB_URL`; empty means `https://gitlab.com` |
| `gitlab.clientId` | `""` | `LIFTGATE_GITLAB_CLIENT_ID` |
| `gitlab.clientSecret` | `""` | `LIFTGATE_GITLAB_CLIENT_SECRET`, Secret |
| `bitbucket.clientId` | `""` | `LIFTGATE_BITBUCKET_CLIENT_ID` |
| `bitbucket.clientSecret` | `""` | `LIFTGATE_BITBUCKET_CLIENT_SECRET`, Secret |
| `email.smtpUrl` | `""` | `LIFTGATE_SMTP_URL`, Secret; `smtp://` for STARTTLS, `smtps://` for implicit TLS |
| `email.from` | `""` | `LIFTGATE_EMAIL_FROM`, e.g. `Liftgate <login@example.com>` |
| `passkeys.rpId` | `""` | `LIFTGATE_WEBAUTHN_RP_ID`; empty means the host of `dashboardUrl` |
| `passkeys.rpName` | `""` | `LIFTGATE_WEBAUTHN_RP_NAME`; empty means `Liftgate` |
| `secrets.masterKey` | `""` | `LIFTGATE_SECRETS_MASTER_KEY`, base64 of 32 bytes |
| `registry` | `registry.liftgate.internal` | `LIFTGATE_REGISTRY` |
| `build.image` | `ghcr.io/liftgate/build-image:latest` | `LIFTGATE_BUILD_IMAGE` |
| `build.namespace` | `liftgate-build` | `LIFTGATE_BUILD_NAMESPACE`; the chart creates it |
| `build.allowedEgressCidrs` | `[]` | Private CIDRs build jobs may reach, for an in-cluster registry; everything else private is blocked |
| `build.registryCredentials` | `""` | Docker `config.json` content; rendered as Secret `registry-credentials` in `build.namespace` and mounted by build jobs |
| `runtimeClass` | `""` | `LIFTGATE_RUNTIME_CLASS`, set `gvisor` on shared clusters |
| `nodeSelector` | `{}` | Node labels that pin the control plane, dashboard, CloudNativePG cluster, tenant pods and build jobs; rendered into `LIFTGATE_NODE_SELECTOR` as `key=value,key=value`. See [Node pinning](#node-pinning) for NATS |
| `registryInsecure` | `false` | `LIFTGATE_REGISTRY_INSECURE`; build jobs push to `registry` over plain HTTP |
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

## Node pinning

`nodeSelector` covers everything this chart renders and everything the control plane
schedules. The `nats` subchart reads its own values, and Helm cannot template one value from
another, so repeat the selector under `nats.podTemplate.merge.spec.nodeSelector`:

```yaml
nodeSelector:
  kubernetes.io/hostname: node-1
nats:
  podTemplate:
    merge:
      spec:
        nodeSelector:
          kubernetes.io/hostname: node-1
```

## Build namespace

`build.namespace` is created with `pod-security.kubernetes.io/enforce: privileged` because
rootless BuildKit needs unconfined seccomp and AppArmor profiles. Do not schedule anything
else there.

## Uninstall

```sh
helm uninstall liftgate --namespace liftgate-system
```

The CloudNativePG cluster and its volumes are deleted with the release; take a backup first.
