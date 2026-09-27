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
helm upgrade --install liftgate oci://ghcr.io/liftgate/charts/liftgate --version <version> \
  --namespace liftgate-system --create-namespace \
  --set deployDomain=apps.example.net \
  --set publicUrl=https://liftgate.example.com \
  --set secrets.masterKey="$MASTER_KEY" \
  --set github.appId=123456 \
  --set github.clientId=Iv1.abc \
  --set github.clientSecret="$GITHUB_CLIENT_SECRET" \
  --set github.webhookSecret="$GITHUB_WEBHOOK_SECRET" \
  --set-file github.privateKey=github-app.pem
```

Add `--set profile=ha --set nats.config.cluster.enabled=true --set postgres.backup.enabled=true`,
the contents of `values-ha.yaml`, for the `ha` profile. The backups it turns on also need
`postgres.backup.destinationPath`, the Barman Cloud plugin and a credentials Secret; see
[Backups](#backups). Values files keep secrets out of shell history; pass them with `-f` instead
of `--set` in production.

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

## Separate sites

Tenant apps must not share a site with the dashboard and API. Browsers treat hosts under the
same registrable domain as one site, so an app at `shop.apps.example.com` is same-site with
`liftgate.example.com`: requests it triggers carry the `SameSite=Lax` session cookie, and it
can set cookies for `example.com` that the API receives. Give `deployDomain` its own
registrable domain, such as `apps.example.net` next to `publicUrl: https://liftgate.example.com`.

The chart refuses to render when `deployDomain` ends in the same two labels as the host of
`publicUrl` or `dashboardUrl`. The check cannot tell a public suffix such as `co.uk` from a
registrable domain; set `allowSharedSite: true` only when the shared labels are a public suffix.

## Client IP

Rate limits key anonymous requests by client IP. By default the control plane takes the entry
`controlPlane.trustedProxies` places from the right of `X-Forwarded-For`. The Cilium gateway
appends the address it received the request from, so the default of `1` fits clients that reach
the gateway directly. Add one for every proxy in front of the gateway that appends to the
header; a proxy that replaces the header counts as one entry. Behind Cloudflare and a reverse
proxy that sets `X-Forwarded-For` from `CF-Connecting-IP`, the control plane receives
`<client>, <proxy>`, so set `trustedProxies: 2`. IPv6 clients share one key per /64.

Alternatively `controlPlane.clientIpHeader` names a header that carries the client address,
such as `CF-Connecting-IP`. The control plane reads it only on connections from an address in
`controlPlane.trustedProxyCidrs`, falls back to `X-Forwarded-For` otherwise, and refuses to
start with a header but no CIDRs. The gateway forwards a header the client sent unchanged, so
with the gateway inside `trustedProxyCidrs`, a client that reaches the gateway directly picks its
own address. Set `clientIpHeader` only when every request passes through an upstream proxy that
overwrites that header, and confirm it with `controlPlane.upstreamOverwritesClientIpHeader: true`;
the chart refuses to render a header without it.

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

## Backups

With `postgres.backup.enabled=true` the CloudNativePG cluster archives every WAL segment to an
S3-compatible object store and takes a base backup on `postgres.backup.schedule` and once right
after the schedule is created, through the
[Barman Cloud plugin](https://cloudnative-pg.io/plugin-barman-cloud/). The chart renders a
`barmancloud.cnpg.io/v1` `ObjectStore`, registers the plugin as the cluster's WAL archiver and
adds a `ScheduledBackup`. Backups and WAL that fall out of the `postgres.backup.retention`
recovery window are deleted. Enabling backups requires `postgres.backup.destinationPath`, and
with a managed database the `ha` profile refuses to render without backups.

Install the plugin into the operator's namespace; it needs cert-manager:

```sh
kubectl apply -f https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/v0.15.0/manifest.yaml
kubectl -n cnpg-system rollout status deployment/barman-cloud
```

Create a bucket and a key that can read and write it on any S3-compatible store (Garage, MinIO,
AWS S3), then the Secret named by `postgres.backup.credentialsSecret`. `ACCESS_REGION` must be
the region the store signs for (Garage: its `s3_region`):

```sh
kubectl -n liftgate-system create secret generic liftgate-postgres-backup \
  --from-literal=ACCESS_KEY_ID=<key id> \
  --from-literal=ACCESS_SECRET_KEY=<secret key> \
  --from-literal=ACCESS_REGION=<region>
```

```yaml
postgres:
  backup:
    enabled: true
    endpointUrl: http://garage.example.internal:3900
    destinationPath: s3://liftgate-pg/
```

Enabling backups on a running cluster replaces its Postgres pods once to add the plugin
sidecar. Then check that a backup completed:

```sh
kubectl -n liftgate-system get backups
```

Restoring, the point-in-time drill and key escrow are in
[`infra/cnpg/README.md`](../../infra/cnpg/README.md).

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

Liftgate signs a GitLab login into an existing account with the same email only when GitLab lists
that address as confirmed. For a self-managed `url` this is off unless you set `trustEmail: true`,
because the administrators of that instance decide what counts as confirmed; without it, a GitLab
login is never matched to an existing account by email. `trustEmail: false` turns it off for
gitlab.com as well. The `url` must use `https://`, because Liftgate sends GitLab access tokens to it.

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

## Sign-up and accounts

`signup.mode` decides what the first sign-in of an unknown identity does:

| Mode | First sign-in |
|---|---|
| `approval` (default) | Creates a pending account. It can sign in and manage its sign-in methods, and gets `403 account_pending` from organization calls until an operator approves it |
| `open` | Creates an active account |
| `closed` | Is refused with `403 signup_closed`; existing accounts still sign in |

Identities in `signup.allow` are active from their first sign-in: verified emails
(`dean@example.com`), email domains (`@example.com`) and GitHub logins (`github:dean`). Put
the first admin there. SAML users who join an organization through a verified email domain are
active while that organization has an active owner and is not suspended.

Operators approve and suspend with the control plane's admin commands. In the `ha` profile run
them in `deploy/<fullname>-api` instead:

```sh
kubectl -n liftgate-system exec deploy/liftgate-control-plane -- /opt/liftgate/bin/liftgate-control-plane admin list-pending
```

| Command | Effect |
|---|---|
| `list-pending` | Lists pending accounts |
| `approve <user>` | Activates a pending account |
| `suspend <org> <reason>` | Scales every app of the organization to zero, suspends its cron jobs, removes its routes, cancels its queued builds and refuses new builds and admin changes (`403 org_suspended`). Releases keep it stopped |
| `unsuspend <org>` | Restores each service's configured replicas and routes |
| `suspend-user <user> <reason>` | Deletes the account's sessions and API tokens and refuses its sign-ins |
| `unsuspend-user <user>` | Reactivates a suspended account |
| `plan <org> <plan>` | Moves the organization onto one of `plans`, or onto `default` to follow `defaultPlan`, and re-renders its apps |

`<user>` is a user id, login or email. Each change is written together with an `audit_log` row
and an outbox event in one transaction.

Set `legal.termsUrl`, `legal.privacyUrl` and `legal.aupUrl` to show a consent line on sign-in
and a footer with the documents; new accounts then record when they accepted the terms. Left
empty, neither appears.

## Values

| Key | Default | Description |
|---|---|---|
| `profile` | `single` | `single` or `ha` |
| `imagePullSecrets` | `[]` | Pull secrets for both images |
| `controlPlane.image` | `ghcr.io/liftgate/control-plane` | |
| `controlPlane.tag` | `""` | Empty means the chart's `appVersion` |
| `controlPlane.replicas.{api,reconciler,builder,meter}` | `3,2,2,1` | Replicas per role in `ha` |
| `controlPlane.logLevel` | `INFO` | `LIFTGATE_LOG_LEVEL` |
| `controlPlane.trustedProxies` | `1` | `LIFTGATE_TRUSTED_PROXIES`: how many proxies append to `X-Forwarded-For` before the API. Rate limits read the client IP this many entries from the right. Count the gateway and every proxy in front of it, each of which must keep the incoming header (Caddy needs `trusted_proxies`); `0` uses the connection address. See [Client IP](#client-ip) |
| `controlPlane.clientIpHeader` | `""` | `LIFTGATE_CLIENT_IP_HEADER`, e.g. `CF-Connecting-IP`; read only on connections from `trustedProxyCidrs`, and only when every request passes through an upstream proxy that overwrites it. See [Client IP](#client-ip) |
| `controlPlane.upstreamOverwritesClientIpHeader` | `false` | Required with `clientIpHeader`: confirms that an upstream proxy overwrites that header on every request |
| `controlPlane.trustedProxyCidrs` | `[]` | `LIFTGATE_TRUSTED_PROXY_CIDRS`: CIDRs of the proxy that connects to the control plane |
| `controlPlane.javaOpts` | `-XX:MaxRAMPercentage=75.0` | `JAVA_TOOL_OPTIONS` |
| `controlPlane.resources` | 250m / 768Mi, limit 1536Mi | |
| `dashboard.image` | `ghcr.io/liftgate/dashboard` | |
| `dashboard.tag` | `""` | Empty means the chart's `appVersion` |
| `dashboard.resources` | 100m / 128Mi, limit 256Mi | |
| `postgres.managed` | `true` | Render a CloudNativePG `Cluster` |
| `postgres.externalUrl` | `""` | JDBC URL when not managed |
| `postgres.externalUser` | `""` | |
| `postgres.externalPassword` | `""` | |
| `postgres.storage` | `10Gi` | Volume per instance |
| `postgres.maxConnections` | `100` | PostgreSQL `max_connections` |
| `postgres.backup.enabled` | `false` | WAL archiving and scheduled base backups, see [Backups](#backups); required for `ha` with a managed database |
| `postgres.backup.endpointUrl` | `""` | S3 endpoint; empty means AWS S3 |
| `postgres.backup.destinationPath` | `""` | `s3://<bucket>/<optional prefix>`; required when `enabled` with a managed database |
| `postgres.backup.credentialsSecret` | `liftgate-postgres-backup` | Secret with `ACCESS_KEY_ID`, `ACCESS_SECRET_KEY` and `ACCESS_REGION` |
| `postgres.backup.retention` | `30d` | Recovery window: `<n>d`, `<n>w` or `<n>m` |
| `postgres.backup.schedule` | `0 0 3 * * *` | Base backup schedule, cron with a leading seconds field |
| `postgres.backup.serverName` | `""` | Folder under `destinationPath` the cluster archives to; empty means the Cluster name. Give each restored cluster a new one |
| `postgres.backup.recoverFrom` | `""` | Folder to restore from when the Cluster is created; empty creates an empty database |
| `postgres.backup.recoverTo` | `""` | RFC 3339 time to stop the restore at; empty replays all archived WAL |
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
| `deployDomain` | `liftgate.app` | `LIFTGATE_DEPLOY_DOMAIN`, at most 189 characters so every generated hostname fits in 253 |
| `publicUrl` | `https://liftgate.dev` | `LIFTGATE_PUBLIC_URL` |
| `dashboardUrl` | `""` | `LIFTGATE_DASHBOARD_URL`; empty means `publicUrl` |
| `github.appId` | `""` | `LIFTGATE_GITHUB_APP_ID` |
| `github.clientId` | `""` | `LIFTGATE_GITHUB_CLIENT_ID` |
| `github.clientSecret` | `""` | `LIFTGATE_GITHUB_CLIENT_SECRET` |
| `github.webhookSecret` | `""` | `LIFTGATE_GITHUB_WEBHOOK_SECRET` |
| `github.privateKey` | `""` | `LIFTGATE_GITHUB_APP_PRIVATE_KEY`, PEM |
| `google.clientId` | `""` | `LIFTGATE_GOOGLE_CLIENT_ID` |
| `google.clientSecret` | `""` | `LIFTGATE_GOOGLE_CLIENT_SECRET`, Secret |
| `gitlab.url` | `""` | `LIFTGATE_GITLAB_URL`, `https://` only; empty means `https://gitlab.com` |
| `gitlab.clientId` | `""` | `LIFTGATE_GITLAB_CLIENT_ID` |
| `gitlab.clientSecret` | `""` | `LIFTGATE_GITLAB_CLIENT_SECRET`, Secret |
| `gitlab.trustEmail` | `null` | `LIFTGATE_GITLAB_TRUST_EMAIL`; unset trusts confirmed addresses on gitlab.com only, `true` or `false` decides for any `url` |
| `bitbucket.clientId` | `""` | `LIFTGATE_BITBUCKET_CLIENT_ID` |
| `bitbucket.clientSecret` | `""` | `LIFTGATE_BITBUCKET_CLIENT_SECRET`, Secret |
| `email.smtpUrl` | `""` | `LIFTGATE_SMTP_URL`, Secret; `smtp://` for STARTTLS, `smtps://` for implicit TLS |
| `email.from` | `""` | `LIFTGATE_EMAIL_FROM`, e.g. `Liftgate <login@example.com>` |
| `passkeys.rpId` | `""` | `LIFTGATE_WEBAUTHN_RP_ID`; empty means the host of `dashboardUrl` |
| `passkeys.rpName` | `""` | `LIFTGATE_WEBAUTHN_RP_NAME`; empty means `Liftgate` |
| `secrets.masterKey` | `""` | `LIFTGATE_SECRETS_MASTER_KEY`, base64 of 32 bytes |
| `registry` | `registry.liftgate.internal` | `LIFTGATE_REGISTRY` |
| `build.image` | `""` | `LIFTGATE_BUILD_IMAGE`; empty means `ghcr.io/liftgate/build-image:<appVersion>` |
| `build.namespace` | `liftgate-build` | `LIFTGATE_BUILD_NAMESPACE`; the chart creates it |
| `build.allowedEgressCidrs` | `[]` | Private addresses build jobs may reach, such as the registry, each as `{cidr: 10.0.0.5/32, ports: [5000]}` (TCP); everything else private is blocked. A bare CIDR string still works but opens every port, and the install notes warn about it |
| `build.registryCredentials` | `""` | Docker `config.json` content; rendered as Secret `registry-credentials` in `build.namespace` and mounted by build jobs. `registryAuth: shared` only |
| `build.nodeSelector` | `{}` | `LIFTGATE_BUILD_NODE_SELECTOR`; node labels for build jobs, `nodeSelector` when empty |
| `build.tolerations` | `[]` | `LIFTGATE_BUILD_TOLERATIONS`; taints build jobs tolerate, in the same form as `workloads.tolerations` |
| `runtimeClass` | `gvisor` | `LIFTGATE_RUNTIME_CLASS`; the RuntimeClass of every tenant pod. The chart refuses to render when it is empty unless `allowUnsandboxedTenants=true` |
| `allowUnsandboxedTenants` | `false` | With an empty `runtimeClass`, renders `LIFTGATE_ALLOW_RUNC=true` so tenant pods run under runc on the node kernel. Only for clusters where every tenant is trusted |
| `workloads.nodeSelector` | `{}` | `LIFTGATE_WORKLOAD_NODE_SELECTOR`; node labels for tenant pods, `nodeSelector` when empty |
| `workloads.tolerations` | `[]` | `LIFTGATE_WORKLOAD_TOLERATIONS`; taints tenant pods tolerate, written as for `kubectl taint`: `key=value:Effect`, `key:Effect` or `key` |
| `nodeSelector` | `{}` | Node labels that pin the control plane, dashboard and CloudNativePG cluster; rendered into `LIFTGATE_NODE_SELECTOR` as `key=value,key=value`, which tenant pods and build jobs use when `workloads.nodeSelector` or `build.nodeSelector` is empty. See [Node pools](#node-pools) for NATS |
| `registryInsecure` | `false` | `LIFTGATE_REGISTRY_INSECURE`; build jobs push to `registry` over plain HTTP |
| `registryAuth` | `shared` | `LIFTGATE_REGISTRY_AUTH`: `shared` or `token`, see [Registry authentication](#registry-authentication) |
| `registryTokenKey` | `""` | `LIFTGATE_REGISTRY_TOKEN_KEY`, Secret; RSA private key in PEM that signs registry tokens. Required with `token` |
| `registryTokenCertificate` | `""` | `LIFTGATE_REGISTRY_TOKEN_CERTIFICATE`; the certificate of `registryTokenKey`, which the registry trusts. Required with `token` |
| `registryPullPassword` | `""` | `LIFTGATE_REGISTRY_PULL_PASSWORD`, Secret; password of the `pull` account nodes use. Required with `token` |
| `prometheusUrl` | `http://prometheus.liftgate-system:9090` | `LIFTGATE_PROMETHEUS_URL` |
| `signup.mode` | `approval` | `LIFTGATE_SIGNUP`: `open`, `approval` or `closed`; see [Sign-up and accounts](#sign-up-and-accounts) |
| `signup.allow` | `[]` | `LIFTGATE_SIGNUP_ALLOW`: emails, `@domains` and `github:<login>` entries that are active from their first sign-in |
| `legal.termsUrl` | `""` | `LIFTGATE_TERMS_URL` |
| `legal.privacyUrl` | `""` | `LIFTGATE_PRIVACY_URL` |
| `legal.aupUrl` | `""` | `LIFTGATE_AUP_URL`, the acceptable use policy |
| `customDomains.enabled` | `true` | `LIFTGATE_CUSTOM_DOMAINS_ENABLED`; `false` replaces the dashboard's add-domain form with a notice, for edges that cannot route customer hostnames yet |
| `customDomains.max` | `null` | `LIFTGATE_CUSTOM_DOMAINS_MAX`; the number of custom domains across every organization, unlimited when `null` |
| `plans` | `free`, `unlimited` | `LIFTGATE_PLANS`; see [Plans](#plans) |
| `defaultPlan` | `unlimited` | `LIFTGATE_DEFAULT_PLAN`; the plan of every organization that has not been given one with `admin plan` |
| `allowSharedSite` | `false` | Render although `deployDomain` ends in the same two labels as `publicUrl` or `dashboardUrl`; see [Separate sites](#separate-sites) |

Derived variables: `LIFTGATE_DATABASE_URL` points at the CloudNativePG `-rw` Service (or
`postgres.externalUrl`), `LIFTGATE_DATABASE_USER` and `LIFTGATE_DATABASE_PASSWORD` come from
the CloudNativePG `<fullname>-postgres-app` Secret (or the `external*` values; `<fullname>`
is the release name when it contains `liftgate`, otherwise `<release>-liftgate`),
`LIFTGATE_NATS_URL` points at the subchart Service (or `nats.externalUrl`),
`LIFTGATE_NATS_REPLICAS` is `3` when the managed NATS cluster is enabled and `1` otherwise,
`LIFTGATE_HAZELCAST_KUBERNETES` is `true` in `ha`, `LIFTGATE_GATEWAY_NAMESPACE` is the release
namespace, `LIFTGATE_LEADER_ELECTION` is `kubernetes` and `LIFTGATE_HTTP_PORT` is `8080`.
Empty optional values are left out of the ConfigMap and Secret so the control plane reports
missing configuration instead of running with blank secrets.

## Plans

Every organization is on a plan from `plans`: `defaultPlan` unless an operator ran `admin plan`.
A limit that is left out is unlimited, so `unlimited: {}` limits nothing.

| Field | Limits |
|---|---|
| `ownedOrgs` | Organizations one user can own, taken from `defaultPlan` |
| `projects`, `services`, `customDomains` | Per organization |
| `environmentsPerProject` | Per project |
| `replicas`, `cpuMillis`, `memoryMb` | Sums of pods, pods × `cpuMillis` and pods × `memoryMb` over the organization's services, where a service's pods are its `replicas` and a cron service counts as one pod |
| `concurrentBuilds` | Running builds per organization; further builds wait in the queue. Needs `buildsPerHour` |
| `buildsPerHour` | Builds per organization started in the last hour or waiting in the queue; further builds fail |
| `cpuRequestRatio` | CPU request as a share of the limit (default `1`) |
| `ephemeralMb` | Disk per container (default `2048`) |
| `egressBandwidth` | `kubernetes.io/egress-bandwidth` of tenant pods, such as `20M`; needs the Cilium bandwidth manager |
| `udp` | `false` closes UDP to the internet, leaving DNS to `kube-system` and traffic inside the environment (default `true`) |

Creating or resizing past a limit answers `409 plan_limit` naming it, and nothing is written;
changes that do not add to an organization that is already over a limit still pass. Each
environment namespace gets a ResourceQuota of twice `replicas`, `cpuMillis`, `memoryMb` and
`replicas × ephemeralMb`, so a rolling update can surge. SMTP ports 25, 465, 587 and 2525 are
closed on every plan. `GET /api/v1/orgs/<org>/usage` reports what an organization uses against its plan.

## Node pools

`nodeSelector` pins the platform (control plane, dashboard, CloudNativePG). Tenant pods are
placed with `workloads.nodeSelector` and `workloads.tolerations`, build jobs with
`build.nodeSelector` and `build.tolerations`, and each falls back to `nodeSelector` when its
own selector is empty.

The `nats` subchart reads its own values, and Helm cannot template one value from another, so
repeat the platform selector under `nats.podTemplate.merge.spec.nodeSelector`:

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

## Network policies

The release namespace denies ingress by default. The control plane can reach every pod in it.
The control plane's HTTP port, the dashboard and cert-manager's HTTP-01 solver pods accept
traffic from anywhere; Hazelcast only from the control plane; NATS only from this release's
pods; and PostgreSQL only from its own instances and the CloudNativePG operator. Anything else
installed in the release namespace, such as the Prometheus from [`infra/`](../../infra), is
reachable only from the control plane unless it brings its own NetworkPolicy.

## Registry authentication

With `registryAuth: shared`, the default, every build job mounts the same
`build.registryCredentials`, so a build can push to and pull from every repository in the
registry. Use it when one team owns every project.

With `registryAuth: token`, the registry must be [CNCF Distribution](https://distribution.github.io/distribution/)
with token authentication that points at Liftgate:

- Each build logs in as `build-<build id>` with a random password from its own Secret. The
  control plane stores only a hash of it and accepts it only while the build runs. The build job no
  longer mounts `registry-credentials`.
- `<publicUrl>/api/v1/registry/token` answers the registry's token requests with a token valid
  for 5 minutes that allows pull and push on the build's own repository,
  `<org>/<project>-<service>`, and nothing else. Build jobs reach it over their internet egress.
- Nodes pull as `pull` with `registryPullPassword`, which reads every repository and writes
  none. Add it to `/etc/rancher/k3s/registries.yaml` on every node.

Create the signing key and certificate, and a pull password:

```sh
openssl req -x509 -newkey rsa:4096 -nodes -days 3650 -subj /CN=liftgate-registry-token \
  -keyout registry-token.key -out registry-token.crt
openssl rand -hex 32
```

```sh
helm upgrade liftgate charts/liftgate --reuse-values \
  --set registryAuth=token --set registryPullPassword="$PULL_PASSWORD" \
  --set-file registryTokenKey=registry-token.key --set-file registryTokenCertificate=registry-token.crt
```

Configure the registry with `realm` `<publicUrl>/api/v1/registry/token`, `service` equal to
`registry`, `issuer` `liftgate` and `rootcertbundle` pointing at `registry-token.crt`.
[`infra/registry`](../../infra/registry) has the configuration Liftgate Cloud uses and the order
of the switch-over.

## Build namespace

`build.namespace` is created with `pod-security.kubernetes.io/enforce: privileged` because
rootless BuildKit needs unconfined seccomp and AppArmor profiles. Do not schedule anything
else there.

## Uninstall

```sh
helm uninstall liftgate --namespace liftgate-system
```

The CloudNativePG `Cluster` and the backup `ObjectStore` carry `helm.sh/resource-policy: keep`,
so the database, its volumes and its `-app` Secret survive the uninstall. Installing the same
release name into the same namespace again adopts them and keeps the data. Install with the same
`secrets.masterKey`: the Secret holding it is deleted with the release. To delete the data too:

```sh
kubectl -n liftgate-system delete cluster liftgate-postgres
kubectl -n liftgate-system delete objectstore liftgate-postgres
```

The `ObjectStore` only points at the bucket, so the backups and WAL stay there, and nothing in the
cluster prunes them once the `Cluster` is gone. Delete everything under
`postgres.backup.destinationPath` with any S3 client to remove the data for good. Before installing
again, do that or set a `destinationPath` that has never been used: CloudNativePG refuses to archive
into a folder that already holds WAL. Also remove `recoverFrom`, `recoverTo` and `serverName` from the
values file: with `recoverFrom` set, the new `Cluster` restores from the archive instead of starting
empty.
