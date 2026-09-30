# Registry on another machine

The chart can run the registry inside the cluster (`inClusterRegistry`, see In-cluster registry in the
[chart README](../../charts/liftgate/README.md#in-cluster-registry)). This recipe is for a registry
outside the cluster instead: [CNCF Distribution](https://distribution.github.io/distribution/) 2.8.3
(`registry:2.8.3`) in Docker on a machine of its own, with token authentication.

The examples use these names. Replace them with yours everywhere, [`config.yml`](config.yml) included.

| Example | Stands for |
|---|---|
| `192.168.100.3:5000` | The registry's address on a private network that only the cluster's machines share; the chart's `registry` |
| `https://liftgate.example.com` | The chart's `publicUrl` |
| `/etc/registry` | The registry's configuration, certificate and passwords |
| `/var/lib/registry` | Its data |

Build jobs reach private addresses only through `build.allowedEgressCidrs`, as
`{cidr: 192.168.100.3/32, ports: [5000]}`. Cilium never matches such address rules against the
cluster's own nodes and pods, so the registry has to run on a machine outside the cluster, on a network
numbered outside `10.0.0.0/8`, the range Cilium gives pods.

## Why token auth

Without authentication, or with one login that every build shares, any build can read, and overwrite,
every tenant's `:<sha>` and `:cache` tags, which the next build of that tenant imports.

[`config.yml`](config.yml) turns on token auth with the control plane as the token service
(`registryAuth: token` in the chart), binds to the private address only, and enables deletes for image
retention:

| Setting | Value | Must match |
|---|---|---|
| `auth.token.realm` | `https://liftgate.example.com/api/v1/registry/token` | chart `publicUrl` |
| `auth.token.service` | `192.168.100.3:5000` | chart `registry` |
| `auth.token.issuer` | `liftgate` | fixed in the control plane |
| `auth.token.rootcertbundle` | `/etc/docker/registry/token.crt` | chart `registryTokenCertificate` |
| `storage.delete.enabled` | `true` | |

Each build logs in as `build-<build id>` and gets tokens only while it runs, for pull and push on its
own repository and, for a preview build, pull on the same service's production repository. Nodes log in
as `pull`, which can read every repository and write none. The builder's image janitor logs in as
`janitor`, which can pull and delete in every repository and push to none.

Build jobs and nodes fetch their tokens from the realm, so both must reach `publicUrl`: build jobs over
their internet egress, nodes over their own network. When `publicUrl` resolves to an address of one of
the cluster's nodes, build jobs cannot reach it, because `build.allowedEgressCidrs` cannot open node
addresses either; run the in-cluster registry there.

## Setup

1. On the registry machine, create the signing key, its certificate and the pull and janitor passwords:

   ```sh
   install -d -m 700 /etc/registry
   cd /etc/registry
   openssl req -x509 -newkey rsa:4096 -nodes -days 3650 -subj /CN=liftgate-registry-token \
     -keyout token.key -out token.crt
   openssl rand -hex 32 > pull-password
   openssl rand -hex 32 > janitor-password
   ```

2. Copy [`config.yml`](config.yml) to `/etc/registry/config.yml` with your address and `publicUrl`.

3. Upgrade Liftgate with `registry=192.168.100.3:5000`, `registryInsecure=true`,
   `registryAuth=token`, `registryTokenKey` from `token.key`, `registryTokenCertificate` from
   `token.crt`, `registryPullPassword` and `registryJanitorPassword`, as Registry authentication in the
   chart README shows.

4. On every node, add the pull account to `/etc/rancher/k3s/registries.yaml` and restart k3s
   (`k3s-agent` on agents):

   ```yaml
   mirrors:
     "192.168.100.3:5000":
       endpoint:
         - "http://192.168.100.3:5000"
   configs:
     "192.168.100.3:5000":
       auth:
         username: pull
         password: <contents of pull-password>
   ```

5. Start the registry with `config.yml` and the certificate, keeping the data directory of a registry
   that ran there before:

   ```sh
   docker rm -f registry 2> /dev/null
   docker run -d --name registry --network host --restart unless-stopped \
     -v /var/lib/registry:/var/lib/registry \
     -v /etc/registry/config.yml:/etc/docker/registry/config.yml:ro \
     -v /etc/registry/token.crt:/etc/docker/registry/token.crt:ro \
     registry:2.8.3
   ```

6. Verify:

   ```sh
   curl -si http://192.168.100.3:5000/v2/ | grep -i -e '^HTTP' -e '^www-authenticate'
   TOKEN=$(curl -s -u "pull:$(cat /etc/registry/pull-password)" \
     "https://liftgate.example.com/api/v1/registry/token?service=192.168.100.3:5000&scope=repository:<org>/<project>/<environment>/<service>:pull,push" | jq -r .token)
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://192.168.100.3:5000/v2/<org>/<project>/<environment>/<service>/tags/list
   curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" http://192.168.100.3:5000/v2/<org>/<project>/<environment>/<service>/blobs/uploads/
   ```

   Expect 401 with `Bearer realm="https://liftgate.example.com/api/v1/registry/token"`, then 200 for
   the tag list, or 404 before the service's first build, and 401 for the upload. Then deploy a service and restart a running one with
   `kubectl rollout restart` so a node pulls with the pull account.

A registry that builds already use and that allows anonymous access keeps serving builds and nodes
until step 5, because clients send their new logins only when a registry asks for one. A registry
that asks for a password rejects the new logins of builds from step 3 and of nodes from step 4, so run
steps 3 to 5 together.

The registry listens on plain HTTP, so the private network carries the passwords and every image in
the clear; use it only on a network that carries nothing but machines you control.

## Rotating the signing key

`rootcertbundle` may hold several certificates. Append the new certificate to `token.crt` and
restart the registry, upgrade Liftgate with the new key and certificate, then remove the old
certificate and restart the registry again.

## Image retention

Every build pushes a `:<sha>` tag and replaces `:cache`, so the registry grows with every
deploy. Once a day, and whenever a builder pod takes the `liftgate-registry-janitor` lease, the
builder prunes the repositories Liftgate pushed to. It keeps:

- the images of pending, releasing and running deployments;
- the image of each service's newest deployment that reached running, because the pods of the
  last rollout that succeeded keep serving until another one does;
- the images of queued and running builds;
- the newest 10 successful builds of each service;
- each service's `:cache`.

Every other `:cache` or 40-character commit sha tag, the only tags builds push, is deleted with
`HEAD` for its `Docker-Content-Digest` and then `DELETE /v2/<repository>/manifests/<digest>`, and
its build is marked pruned, so a rollback to it answers 409 `image_pruned` and the dashboard
hides the button. Other tags stay, and so does every tag that shares its manifest with one that
stays. Repositories of deleted services, projects and organizations lose every tag builds
pushed. The janitor only reads the repositories of current and deleted services, and the
organization slug `liftgate` is reserved so that none of them holds the platform's own `liftgate/*`
images. With token auth the janitor logs in to the control plane's token service as `janitor` and
gets a token for `pull,delete` on one repository at a time.

Deleting a manifest frees no disk. `registry garbage-collect --delete-untagged` does: it removes
the pruned images and the `:cache` manifests that newer builds replaced, together with every blob
that no tagged manifest references. It must not run while builds push, so the weekly timer below
puts the registry in read-only mode while it runs. `registry:2.8.3` started with
`REGISTRY_STORAGE_MAINTENANCE_READONLY='{"enabled":true}'` answers uploads with 405 and still
serves `GET /v2/`, so nodes keep pulling. `RegistryJanitorTest` runs this garbage collection
against the same image after 30 deploys and checks that the kept images and cache survive it.

On 2.8.3 garbage collection keeps only the manifests a tag points at, so it also removes the
manifests that a tagged OCI index or manifest list lists, with their config and layers. While the
timer runs, the registry must hold no index or list whose entries are manifests. A Dockerfile
build pushes its image and `:cache` as single OCI manifests, checked by running
`build-image/build.sh` with buildctl v0.33.0 against a scratch registry. Keep other images, such as
multi-platform indexes, in another registry. `RegistryJanitorTest` checks both shapes.

`/usr/local/sbin/registry-gc`, mode 755:

```sh
#!/bin/sh
set -eu
registry() {
  docker rm -f registry >/dev/null
  docker run -d --name registry --network host --restart unless-stopped \
    -v /var/lib/registry:/var/lib/registry \
    -v /etc/registry/config.yml:/etc/docker/registry/config.yml:ro \
    -v /etc/registry/token.crt:/etc/docker/registry/token.crt:ro \
    "$@" registry:2.8.3 >/dev/null
}
trap registry EXIT
registry -e REGISTRY_STORAGE_MAINTENANCE_READONLY='{"enabled":true}'
docker exec registry registry garbage-collect --delete-untagged /etc/docker/registry/config.yml
```

`/etc/systemd/system/registry-gc.service`:

```ini
[Unit]
Description=Registry garbage collection
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/registry-gc
```

`/etc/systemd/system/registry-gc.timer`:

```ini
[Unit]
Description=Weekly registry garbage collection

[Timer]
OnCalendar=Sun *-*-* 04:30:00 UTC
RandomizedDelaySec=15m
Persistent=true

[Install]
WantedBy=timers.target
```

```sh
systemctl daemon-reload
systemctl enable --now registry-gc.timer
systemctl start registry-gc.service
journalctl -u registry-gc.service -n 50
du -sh /var/lib/registry
```

A build that pushes during the run fails and can be started again.
