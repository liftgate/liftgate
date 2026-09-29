# Self-hosting

This guide installs Liftgate on one fresh virtual machine running k3s, from the published Helm chart
`oci://ghcr.io/liftgate/charts/liftgate` and the public images on GHCR, which need no login. At the end
the dashboard runs over HTTPS, you are signed in as its first admin, and an app answers at
`https://<service>-<project>-<org>.<deploy domain>` with a trusted certificate.

Read this guide at the tag of the release you install, for example
`https://github.com/liftgate/liftgate/blob/v0.2.0/documentation/self-hosting.md` for 0.2.0, because the
commands and values change between releases. The [releases page](https://github.com/liftgate/liftgate/releases)
lists them.

The examples use these names. Replace them with yours everywhere.

| Example | Stands for |
|---|---|
| `liftgate.example.com` | The dashboard and the API, the chart's `publicUrl` |
| `apps.example.net` | The deploy domain; apps get `<service>-<project>-<org>.apps.example.net` |
| `203.0.113.10` | The VM's public IPv4 address |
| `10.0.0.3` | The registry machine's address on the private network it shares with the VM |
| `ops@example.com` | The address Let's Encrypt writes to about your certificates |
| `your-github-login` | Your GitHub login |

The dashboard and the apps must not share a registrable domain: an app under `example.com` would be
same-site with a dashboard under `example.com`, and could send requests that carry the dashboard's
session cookie. The chart refuses to render such a pair, as Separate sites in the
[chart README](../charts/liftgate/README.md#separate-sites) explains.

## What you need

- A VM for the cluster: x86-64, because the images are published for `linux/amd64` only, with Ubuntu
  24.04 or 22.04, a public IPv4 address, and ports 22, 80 and 443 open to the internet. Start with 4
  vCPUs, 8 GB of memory and 80 GB of disk. That leaves room for the platform, one build at a time and a
  few small apps; it is a starting point, not a measured minimum. The kubelet settings reserve 1 CPU and
  2 GiB for the system, and a single build may use 2 CPUs, 4 GiB of memory and 20 GiB of disk.
- A second machine for the container registry, with a private network between it and the VM that
  carries only machines you control and trust. Step 2 explains why the registry cannot run on the VM.
- Two domains whose DNS you control, one for the dashboard and one for the apps. The apps' DNS must let
  cert-manager create TXT records, either through RFC 2136 dynamic updates or through one of the
  [DNS providers cert-manager supports](https://cert-manager.io/docs/configuration/acme/dns01/).
- A GitHub account or organization to own the GitHub App that signs users in and reads repositories.
- Optional: an S3-compatible bucket for database backups, and an SMTP server for sign-in codes and
  invitation mail.

Run every command below as root.

## 1. DNS

Create two `A` records and wait until they resolve:

| Name | Type | Value |
|---|---|---|
| `liftgate.example.com` | `A` | `203.0.113.10` |
| `*.apps.example.net` | `A` | `203.0.113.10` |

Let's Encrypt checks the first name over HTTP before it issues the dashboard's certificate.

## 2. The registry

Every build pushes an image to the registry, and the VM pulls it from there. Build jobs run in a
namespace whose network policy only lets them reach private addresses listed in the chart's
`build.allowedEgressCidrs`. On Cilium, which this install uses, such address rules
[never match a node or a pod of the cluster](https://docs.cilium.io/en/stable/security/policy/layer3/),
so a registry on the VM itself, or inside the cluster, is unreachable for builds. Run it on another
machine on the private network instead, with disk for the images Liftgate keeps: the newest 10 builds of
each service and a build cache per service.

On the registry machine, install Docker, create a password, and start
[CNCF Distribution](https://distribution.github.io/distribution/) with that login and deletes enabled,
so Liftgate can prune old images:

```sh
apt-get update && apt-get install -y docker.io
install -d -m 700 /etc/liftgate-registry
REGISTRY_PASSWORD=$(openssl rand -hex 32)
echo "$REGISTRY_PASSWORD"
docker run --rm --entrypoint htpasswd httpd:2.4 -Bbn liftgate "$REGISTRY_PASSWORD" > /etc/liftgate-registry/htpasswd
cat > /etc/liftgate-registry/config.yml <<'EOF'
version: 0.1
storage:
  filesystem:
    rootdirectory: /var/lib/registry
  delete:
    enabled: true
http:
  addr: :5000
auth:
  htpasswd:
    realm: liftgate
    path: /etc/docker/registry/htpasswd
EOF
docker run -d --name liftgate-registry --restart unless-stopped -p 10.0.0.3:5000:5000 \
  -v /srv/liftgate-registry:/var/lib/registry \
  -v /etc/liftgate-registry/config.yml:/etc/docker/registry/config.yml:ro \
  -v /etc/liftgate-registry/htpasswd:/etc/docker/registry/htpasswd:ro \
  registry:2.8.3
curl -s -o /dev/null -w '%{http_code}\n' -u "liftgate:$REGISTRY_PASSWORD" http://10.0.0.3:5000/v2/
```

The last command prints `200`. Note the password; the VM needs it in steps 3 and 7. The registry listens
only on the private address and speaks plain HTTP, which is why the chart values in step 7 mark it
insecure. Plain HTTP carries the password and every image in the clear, so anyone who can read the
private network's traffic gets both. Use this setup only on a network that carries nothing but machines
you control and trust. Without such a network, serve the registry over HTTPS under a DNS name with a
publicly trusted certificate, set `registry` to that name, and leave out `registryInsecure` and the
`http://` endpoint in `registries.yaml`. Build jobs have no way to trust a private certificate
authority.

Deleting an image frees no disk until the registry's garbage collection runs. Run it while no build
pushes, for example weekly from a timer:

```sh
docker exec liftgate-registry registry garbage-collect --delete-untagged /etc/docker/registry/config.yml
```

Image retention in [`infra/registry/README.md`](../infra/registry/README.md#image-retention) has the timer
Liftgate Cloud uses, which puts the registry in read-only mode while it collects, and the cases where
garbage collection removes more than it should.

This setup gives every build the same registry login, so a build can read other projects' images. That
suits an installation whose users trust each other. For strangers, switch to `registryAuth: token`, which
gives each build a login for its own repository only; see Registry authentication in the
[chart README](../charts/liftgate/README.md#registry-authentication).

## 3. Prepare the VM

Keep one shell open on the VM for steps 3 to 9: later steps use the variables set here. Fetch the
release you install, setting `VERSION` to its number without the leading `v`, such as `0.2.0`:

```sh
apt-get update && apt-get install -y git
VERSION=<the release>
git clone --depth 1 --branch "v$VERSION" https://github.com/liftgate/liftgate.git
cd liftgate
```

Tell k3s where the registry is and how to log in, before k3s starts for the first time. Use the password
from step 2:

```sh
REGISTRY_PASSWORD=<the password from step 2>
mkdir -p /etc/rancher/k3s
cat > /etc/rancher/k3s/registries.yaml <<EOF
mirrors:
  "10.0.0.3:5000":
    endpoint:
      - "http://10.0.0.3:5000"
configs:
  "10.0.0.3:5000":
    auth:
      username: liftgate
      password: $REGISTRY_PASSWORD
EOF
```

Then follow three sections of [`infra/k3s/install.md`](../infra/k3s/install.md), in order, from the
`liftgate` directory:

1. Kubelet limits, which reserves CPU and memory for the system and caps processes per pod.
2. Server, which installs the pinned k3s release without flannel, kube-proxy, Traefik and ServiceLB.
   The node reports `NotReady` until step 4 installs Cilium.
3. gVisor on every node, which installs `runsc` and lets containerd run pods in the gVisor sandbox. The
   chart runs every app under it.

## 4. The cluster baseline

Install Helm, then run [`infra/install.sh`](../infra/install.sh), which installs the Gateway API CRDs,
Cilium with its Gateway, the `gvisor` RuntimeClass, cert-manager with the `letsencrypt` ClusterIssuer,
CloudNativePG with its Barman Cloud plugin, and Prometheus:

```sh
curl -fsSLO https://get.helm.sh/helm-v4.3.0-linux-amd64.tar.gz
curl -fsSL https://get.helm.sh/helm-v4.3.0-linux-amd64.tar.gz.sha256sum | sha256sum -c
tar -xzf helm-v4.3.0-linux-amd64.tar.gz linux-amd64/helm
install -m 755 linux-amd64/helm /usr/local/bin/helm
rm -r linux-amd64 helm-v4.3.0-linux-amd64.tar.gz
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
LIFTGATE_INSTALL_CILIUM=1 LETSENCRYPT_EMAIL=ops@example.com sh infra/install.sh
kubectl get nodes
```

The node is `Ready` once the script finishes. The script skips components that are already installed and
stops, before it changes anything, on a cluster that runs another network plugin;
[`infra/README.md`](../infra/README.md) has the details and the pinned versions.

## 5. The wildcard certificate

Apps are served under `*.apps.example.net`, and the Gateway's listener for them reads a certificate from
the Secret `liftgate-wildcard-tls`. Let's Encrypt issues wildcard certificates only through a DNS-01
challenge, which proves control of the domain with a TXT record at `_acme-challenge.apps.example.net`.
[`infra/cert-manager/wildcard.yaml`](../infra/cert-manager/wildcard.yaml) is an Issuer that creates that
record through RFC 2136, which BIND, Knot, PowerDNS and some DNS hosts accept, and the Certificate that
fills the Secret.

On the DNS server that is authoritative for `apps.example.net`, create a TSIG key and let it update
`_acme-challenge.apps.example.net`. With BIND, `tsig-keygen -a hmac-sha256 liftgate` prints the key.
Then put the key's secret into the cluster and apply the recipe. `192.0.2.53:53` stands for your DNS
server's address and port, and `liftgate` for the key's name:

```sh
kubectl -n liftgate-system create secret generic liftgate-rfc2136 --from-literal=secret='<the TSIG secret, base64>'
sed -e s/LETSENCRYPT_EMAIL/ops@example.com/ -e s/DNS_SERVER/192.0.2.53:53/ -e s/TSIG_KEY_NAME/liftgate/ -e s/DEPLOY_DOMAIN/apps.example.net/ \
  infra/cert-manager/wildcard.yaml | kubectl -n liftgate-system apply -f -
kubectl -n liftgate-system wait certificate/liftgate-wildcard --for=condition=Ready --timeout=15m
```

The recipe expects an HMAC-SHA256 key; change `tsigAlgorithm` in it for another algorithm. If your DNS
provider does not accept RFC 2136 updates, replace the `rfc2136` block with the solver for your provider
from [cert-manager's DNS01 documentation](https://cert-manager.io/docs/configuration/acme/dns01/) and keep
the rest of the file. cert-manager renews the certificate on its own.

## 6. The GitHub App

On GitHub, open the settings of the account or organization that should own the App, then Developer
settings, GitHub Apps, New GitHub App, and fill in:

| Field | Value |
|---|---|
| Homepage URL | `https://liftgate.example.com` |
| Callback URL | `https://liftgate.example.com/api/v1/auth/github/callback` |
| Request user authorization (OAuth) during installation | Unchecked |
| Setup URL | `https://liftgate.example.com/dashboard?installed=1`, with Redirect on update checked |
| Webhook | Active, URL `https://liftgate.example.com/api/v1/webhooks/github`, and a secret from `openssl rand -hex 32` |
| Repository permissions | Contents: Read-only; Metadata: Read-only; Commit statuses: Read and write |
| Account permissions | Email addresses: Read-only |
| Subscribe to events | Push |
| Where can this GitHub App be installed? | Any account, unless every repository you deploy belongs to the App's owner |

Create the App. On its page, note the App ID and the Client ID, generate a client secret, and generate a
private key, which downloads a `.pem` file. Copy that file to the VM as
`github-app.private-key.pem` in the `liftgate` directory.

The Setup URL brings people who install the App from the dashboard back to it. The Commit statuses
permission lets Liftgate report each build on its commit; without it, builds still work.

## 7. Install Liftgate

Write the values file. Run this once: it generates the master key that encrypts every variable users
store, and a second run would replace it. Fill in the App ID, the Client ID and your GitHub login:

```sh
GITHUB_CLIENT_SECRET=<the client secret from step 6>
GITHUB_WEBHOOK_SECRET=<the webhook secret from step 6>
cat > liftgate-values.yaml <<EOF
deployDomain: apps.example.net
publicUrl: https://liftgate.example.com
registry: 10.0.0.3:5000
registryInsecure: true
build:
  allowedEgressCidrs:
    - cidr: 10.0.0.3/32
      ports: [5000]
  registryCredentials: '{"auths":{"10.0.0.3:5000":{"auth":"$(printf 'liftgate:%s' "$REGISTRY_PASSWORD" | base64 -w0)"}}}'
deniedEgressCidrs:
  - 203.0.113.10/32
signup:
  allow:
    - github:your-github-login
github:
  appId: "123456"
  clientId: Iv23liExample
  clientSecret: $GITHUB_CLIENT_SECRET
  webhookSecret: $GITHUB_WEBHOOK_SECRET
  privateKey: |
$(sed 's/^/    /' github-app.private-key.pem)
secrets:
  masterKey: $(openssl rand -base64 32)
EOF
chmod 600 liftgate-values.yaml
```

| Value | Why |
|---|---|
| `deployDomain`, `publicUrl` | The apps' domain and the dashboard's address. The dashboard and the API share `publicUrl` |
| `registry`, `registryInsecure` | Where builds push, over plain HTTP |
| `build.allowedEgressCidrs` | Lets build jobs reach the registry's private address, on port 5000 only |
| `build.registryCredentials` | The registry login for builds and for the daily image cleanup |
| `deniedEgressCidrs` | Keeps apps, builds and notification webhooks away from the VM's own public address |
| `signup.allow` | Makes your account active on its first sign-in; see step 8 |
| `github` | The GitHub App from step 6 |
| `secrets.masterKey` | Encrypts stored variables. Without it they cannot be read, so keep a copy |

Every other value keeps its default: the `single` profile, a PostgreSQL database run by CloudNativePG,
NATS, gateway-mode custom domains, the `unlimited` plan and sign-up by approval. The
[chart README](../charts/liftgate/README.md) documents all of them.

`liftgate-values.yaml` now holds the master key and the App's secrets. Keep it for every upgrade, and
keep a copy of it offline, away from the VM.

Install the chart and wait for it:

```sh
helm install liftgate oci://ghcr.io/liftgate/charts/liftgate --version "$VERSION" \
  --namespace liftgate-system --values liftgate-values.yaml
kubectl -n liftgate-system rollout status deployment/liftgate-control-plane --timeout=15m
kubectl -n liftgate-system rollout status deployment/liftgate-dashboard --timeout=5m
kubectl -n liftgate-system get certificates
```

Both certificates, `liftgate-liftgate-example-com` for the dashboard and `liftgate-wildcard`, show
`READY True` within a few minutes. The first control-plane start runs the database migrations.

## 8. Sign in

Open `https://liftgate.example.com` and choose Continue with GitHub. Because `github:your-github-login`
is in `signup.allow`, your account is active at once. Create an organization; you are its owner.

Everyone else who signs in waits for approval, because the chart's sign-up mode is `approval`. List and
approve them with the control plane's admin command:

```sh
kubectl -n liftgate-system exec deploy/liftgate-control-plane -- /opt/liftgate/bin/liftgate-control-plane admin list-pending
kubectl -n liftgate-system exec deploy/liftgate-control-plane -- /opt/liftgate/bin/liftgate-control-plane admin approve your-github-login
```

`approve` takes a user id, login or email. It also rescues you if you signed in before your login was in
`signup.allow`, which only applies to an account's first sign-in. `signup.allow` accepts verified emails
such as `dean@example.com`, whole domains such as `@example.com`, and GitHub logins such as
`github:dean`. `signup.mode: open` activates everyone, and `closed` refuses new accounts.

## 9. Deploy an app

Follow [Getting started](getting-started.md) from step 2. When the deployment runs, the app answers at
its address with the wildcard certificate, here for a service `web` in the project `hello` of the
organization `acme`:

```sh
curl -I https://web-hello-acme.apps.example.net
```

## Operating the installation

### Accounts

The admin command also suspends organizations and users and moves organizations between plans. Sign-up
and accounts in the [chart README](../charts/liftgate/README.md#sign-up-and-accounts) lists every
subcommand, and [`infra/ABUSE.md`](../infra/ABUSE.md) is the runbook for abuse reports.

### Sign-in providers

GitHub sign-in works through the App. Google, GitLab, Bitbucket, emailed codes and passkeys are optional
and turn on with chart values; organization owners set up SAML single sign-on in the dashboard. Sign-in
providers in the [chart README](../charts/liftgate/README.md#sign-in-providers) says where to create each
set of credentials and which callback URL to register. The SMTP server for emailed codes also sends
invitations and the admin command's approval and suspension notices.

### Custom domains

Custom domains work without further setup in gateway mode, the default: each verified domain gets its
own listener on the Gateway and a Let's Encrypt certificate through the `letsencrypt` ClusterIssuer, so
port 80 has to stay open. [Custom domains](custom-domains.md) covers what users do and how to debug a
certificate. A Gateway holds at most 64 listeners, and this install uses three of its own, so set
`customDomains.max` to 61 or less.

### Plans and limits

Every organization is on the `unlimited` plan until you change `defaultPlan` or move it with
`admin plan`. [Plans and limits](plans-and-limits.md) describes the plans and the limits that apply to
all of them.

### Alerts

The Prometheus from step 4 comes with Alertmanager and the alert rules in
[`infra/prometheus/rules.yaml`](../infra/prometheus/rules.yaml). [`infra/prometheus/README.md`](../infra/prometheus/README.md)
connects it to Discord or ntfy, and [`infra/RUNBOOK.md`](../infra/RUNBOOK.md) says what to do for each
alert.

## Backups and restore

The chart runs PostgreSQL without backups until you turn them on, and the install notes warn about it.
The database holds everything Liftgate knows; the apps' images live in the registry, and build logs live
in NATS for 7 days.

Backups need a bucket on an S3-compatible store outside the VM, such as a hosted object store or
[Garage](https://garagehq.deuxfleurs.fr) on another machine, reached over HTTPS, because the backups hold
the whole database. Create the bucket and a key that can read and write it, then the Secret the chart
reads:

```sh
kubectl -n liftgate-system create secret generic liftgate-postgres-backup \
  --from-literal=ACCESS_KEY_ID=<key id> \
  --from-literal=ACCESS_SECRET_KEY=<secret key> \
  --from-literal=ACCESS_REGION=<region>
```

Add the bucket to `liftgate-values.yaml`:

```yaml
postgres:
  backup:
    enabled: true
    endpointUrl: https://s3.example.com
    destinationPath: s3://liftgate-pg/
```

and upgrade with the same chart version:

```sh
helm upgrade liftgate oci://ghcr.io/liftgate/charts/liftgate --version "$VERSION" \
  --namespace liftgate-system --values liftgate-values.yaml
kubectl -n liftgate-system get backups
```

The database then archives every WAL segment and takes a base backup at 03:00 every day and once right
away, keeping 30 days. Enabling backups restarts the PostgreSQL pod once. Backups in the
[chart README](../charts/liftgate/README.md#backups) lists the other settings.

The backups do not contain `secrets.masterKey` or the GitHub App's private key, and a restored database is
useless without the master key. Keep `liftgate-values.yaml` offline in two places.

To restore, to the latest state or to a point in time, follow Restore in
[`infra/cnpg/README.md`](../infra/cnpg/README.md#restore). Its `helm upgrade` uses a checkout's
`charts/liftgate`; with the published chart, use `oci://ghcr.io/liftgate/charts/liftgate --version
"$VERSION"` in its place.

## Upgrading

Each release is tested by upgrading to it from the release before it, so upgrade one release at a time.
For each one:

1. Read its release notes on the [releases page](https://github.com/liftgate/liftgate/releases). They
   list new or changed values and anything to do before or after.
2. Record the schema version, take a backup and snapshot the VM's disk, as steps 1 and 2 of
   [`infra/UPGRADE.md`](../infra/UPGRADE.md) do. The backup needs backups turned on, as described
   above.
3. Upgrade with the new version and the same values file, and wait for it:

   ```sh
   VERSION=<the new version>
   helm upgrade liftgate oci://ghcr.io/liftgate/charts/liftgate --version "$VERSION" \
     --namespace liftgate-system --values liftgate-values.yaml --wait --timeout 15m
   ```

4. Check that `https://liftgate.example.com/api/v1/auth/providers` answers and that a push to an app is
   built and served.

Every control-plane pod first runs the database migrations in its `migrate` init container. A new
migration never drops or renames what the previous release reads, so the old pods keep working while the
new ones start. `helm rollback` does not undo them: if the schema version from step 2 changed, going
back means restoring the backup, as Rolling back in [`infra/UPGRADE.md`](../infra/UPGRADE.md#rolling-back)
describes.

`infra/install.sh` never upgrades a component that is already installed. Upgrade a baseline component
with its own `helm upgrade`, as [`infra/README.md`](../infra/README.md) shows for Cilium.

### Notes by release

From 0.1.0-alpha.6 to 0.2.0-alpha.1, follow the
[0.2.0-alpha.1 release notes](https://github.com/liftgate/liftgate/releases/tag/v0.2.0-alpha.1): that
release moves sign-up to approval mode, requires the gVisor runtime class and a deploy domain on its own
site, and signs everyone out once.

From 0.2.0-alpha.1 to 0.2.0-alpha.2 there is nothing to do: the release changes no values and adds no
migrations.

From 0.2.0-alpha.2 to 0.2.0-alpha.3:

- Migrations V24 to V28 add tables, columns and an index. A `helm rollback` does not undo them.
- The new values are optional: `deniedEgressCidrs`, `postgres.resources`, `nats.container.resources`,
  which restarts each NATS pod once, and the plan field `cronTimeoutSeconds`.
- The API may answer 503 for about 13 seconds while the new control-plane pod starts.
- Changing `postgres.resources` makes CloudNativePG recreate every PostgreSQL instance, and a single
  instance is down until its new pod is ready. Do it in a maintenance window.
- The builder holds the master key, so it can pass variables to builds. In the `ha` profile the chart
  adds it to the builder's Secret.
- Commit statuses need the App's Commit statuses: Read and write permission, and each installation of the
  App has to accept it. Until then builds work without statuses.
- Build images move from `<org>/<project>-<service>` to `<org>/<project>/<environment>/<service>`. The
  first build of each service after the upgrade has no layer cache.
- With `registryAuth: token`, let builds that started before this upgrade finish before the next one.
- Apps pick up the new rollout strategy and probes at their next deployment. Deployments made before the
  upgrade stored no settings, so a rollback to one uses the current settings.
- Run `helm upgrade` on the Prometheus release with the new `infra/prometheus/values.yaml` to give it
  resource requests.

## Uninstalling

`helm uninstall liftgate --namespace liftgate-system` keeps the database, its volumes and its backups,
and a later install with the same release name, namespace and `secrets.masterKey` picks them up again.
Uninstall in the [chart README](../charts/liftgate/README.md#uninstall) says how to delete the data too.

## Troubleshooting

Start with the pods and the control plane's log:

```sh
kubectl -n liftgate-system get pods
kubectl -n liftgate-system logs deploy/liftgate-control-plane -c migrate
kubectl -n liftgate-system logs deploy/liftgate-control-plane -c control-plane --tail=200
kubectl get --raw /api/v1/namespaces/liftgate-system/services/liftgate-control-plane:http/proxy/readyz
```

| Symptom | Check |
|---|---|
| `install.sh` stops with "not every node reports NetworkReady=false" | Another network plugin runs. Reinstall k3s with the flags from `infra/k3s/install.md`, which turn flannel off |
| The node stays `NotReady` after `install.sh` | `kubectl -n kube-system get pods` for the Cilium pods and their logs |
| The control plane restarts and its log names a missing `LIFTGATE_` variable | The matching chart value is empty. The chart README's Values table maps each variable to its value |
| A certificate stays not ready | `kubectl -n liftgate-system describe certificate <name>`, `kubectl get challenges --all-namespaces` and `kubectl -n cert-manager logs deploy/cert-manager`. The dashboard's certificate needs its `A` record and port 80; the wildcard needs the TSIG key, a DNS server the cluster can reach and an update policy that covers `_acme-challenge` |
| Sign-in with GitHub fails at the callback | The App's Callback URL must match `publicUrl` exactly |
| "Your account is waiting for approval" | Approve the account with `admin approve`, as in step 8 |
| The repository picker is empty | Install the App on the account that owns the repository; your GitHub account must be able to push to it. Then choose Refresh |
| Pushes do not start builds | The App's Advanced tab lists recent webhook deliveries and their responses. The webhook secret must match `github.webhookSecret`, and the push must be to an environment's branch |
| A build fails while pushing the image | `curl -u liftgate:<password> http://10.0.0.3:5000/v2/` from the VM, `build.allowedEgressCidrs` and `build.registryCredentials` |
| A deployment fails with `ImagePullBackOff` | `k3s crictl pull <image>` on the VM with the image from the build. After changing `/etc/rancher/k3s/registries.yaml`, run `systemctl restart k3s` |
| App pods never appear, or stay in `ContainerCreating` with a runtime handler error | `kubectl get runtimeclass gvisor`, `kubectl get pods --all-namespaces -l liftgate.dev/managed=true`, and the gVisor section of `infra/k3s/install.md` |
| The Metrics tab stays empty | `kubectl -n liftgate-system get pods` for Prometheus, and gVisor and cAdvisor in `infra/prometheus/README.md` |
