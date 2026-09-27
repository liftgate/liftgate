# Registry

Liftgate Cloud keeps build output in [CNCF Distribution](https://distribution.github.io/distribution/)
2.8.3 (`registry:2`) on the host, reachable from the VMs at `10.200.0.1:5050`. It runs as the
Docker container `liftgate-registry` with host networking and its data in
`/srv/liftgate-registry`.

## Why token auth

Until the switch below, the registry has no authentication. Checked from the host on
2026-09-27: `GET http://10.200.0.1:5050/v2/` returns 200 and `/v2/_catalog` lists every
repository, and the container listens on `0.0.0.0:5050`. Any build can therefore read, and
overwrite, every tenant's `:<sha>` and `:cache` tags, which the next build of that tenant
imports.

[`config.yml`](config.yml) turns on token auth with the control plane as the token service
(`registryAuth: token` in the chart), binds to `10.200.0.1:5050` only, and enables deletes for
image retention:

| Setting | Value | Must match |
|---|---|---|
| `auth.token.realm` | `https://liftgate.dev/api/v1/registry/token` | chart `publicUrl` |
| `auth.token.service` | `10.200.0.1:5050` | chart `registry` |
| `auth.token.issuer` | `liftgate` | fixed in the control plane |
| `auth.token.rootcertbundle` | `/etc/docker/registry/token.crt` | chart `registryTokenCertificate` |
| `storage.delete.enabled` | `true` | |

Each build logs in as `build-<build id>` and gets tokens for its own repository only, and only
while it runs. Nodes log in as `pull`, which can read every repository and write none. The
builder's image janitor logs in as `janitor`, which can pull and delete in every repository and
push to none.

## Switch-over

The registry accepts anonymous requests until step 4, so builds keep pushing and nodes keep
pulling while Liftgate and the nodes get their logins; step 4 turns authentication on once both
have them.

1. Create the signing key, its certificate and the pull and janitor passwords:

   ```sh
   install -d -m 700 /etc/liftgate-registry
   cd /etc/liftgate-registry
   openssl req -x509 -newkey rsa:4096 -nodes -days 3650 -subj /CN=liftgate-registry-token \
     -keyout token.key -out token.crt
   openssl rand -hex 32 > pull-password
   openssl rand -hex 32 > janitor-password
   ```

2. Upgrade Liftgate with `registryAuth=token`, `registryTokenKey` from `token.key`,
   `registryTokenCertificate` from `token.crt`, `registryPullPassword` and
   `registryJanitorPassword`, as in the chart README. Then run a build and confirm it pushes.

3. On every node, add the pull account to `/etc/rancher/k3s/registries.yaml` and restart k3s
   (`k3s-agent` on agents):

   ```yaml
   mirrors:
     "10.200.0.1:5050":
       endpoint:
         - "http://10.200.0.1:5050"
   configs:
     "10.200.0.1:5050":
       auth:
         username: pull
         password: <contents of pull-password>
   ```

4. Re-push the platform images as their amd64 manifests, which the garbage collection under
   [Image retention](#image-retention) needs and which no account can push once authentication
   is on:

   ```sh
   for r in build-image control-plane dashboard; do
     m=http://10.200.0.1:5050/v2/liftgate/$r/manifests
     d=$(curl -sf -H 'Accept: application/vnd.oci.image.index.v1+json' "$m/0.1.0-alpha.6" |
       jq -r '.manifests[] | select(.platform.architecture == "amd64") | .digest')
     curl -sf -H 'Accept: application/vnd.oci.image.manifest.v1+json' "$m/$d" |
       curl -sf -X PUT -H 'Content-Type: application/vnd.oci.image.manifest.v1+json' --data-binary @- "$m/0.1.0-alpha.6"
   done
   ```

   Then replace the container with one that reads `config.yml` and the certificate:

   ```sh
   install -m 644 infra/registry/config.yml /etc/liftgate-registry/config.yml
   docker rm -f liftgate-registry
   docker run -d --name liftgate-registry --network host --restart unless-stopped \
     -v /srv/liftgate-registry:/var/lib/registry \
     -v /etc/liftgate-registry/config.yml:/etc/docker/registry/config.yml:ro \
     -v /etc/liftgate-registry/token.crt:/etc/docker/registry/token.crt:ro \
     registry:2.8.3
   ```

5. Verify:

   ```sh
   curl -si http://10.200.0.1:5050/v2/ | grep -i -e '^HTTP' -e '^www-authenticate'
   TOKEN=$(curl -s -u "pull:$(cat /etc/liftgate-registry/pull-password)" \
     "https://liftgate.dev/api/v1/registry/token?service=10.200.0.1:5050&scope=repository:<org>/<project>-<service>:pull,push" | jq -r .token)
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://10.200.0.1:5050/v2/<org>/<project>-<service>/tags/list
   curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" http://10.200.0.1:5050/v2/<org>/<project>-<service>/blobs/uploads/
   ```

   Expect 401 with `Bearer realm="https://liftgate.dev/api/v1/registry/token"`, then 200 for
   the tag list and 401 for the upload. Then deploy a service and restart a running one with
   `kubectl rollout restart` so a node pulls with the pull account.

To roll back, recreate the container as it ran before; the chart can stay on `token`:

```sh
docker rm -f liftgate-registry
docker run -d --name liftgate-registry --network host --restart unless-stopped \
  -e REGISTRY_HTTP_ADDR=0.0.0.0:5050 -v /srv/liftgate-registry:/var/lib/registry registry:2.8.3
```

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
organization slug `liftgate` is reserved so that none of them holds the platform's own
`liftgate/*` images. With token auth the janitor logs in to the control plane's token service
as `janitor` and gets a token for `pull,delete` on one repository at a time.

Deleting a manifest frees no disk. `registry garbage-collect --delete-untagged` does: it removes
the pruned images and the `:cache` manifests that newer builds replaced, together with every blob
that no tagged manifest references. It must not run while builds push, so a weekly timer puts the
registry in read-only mode while it runs. Checked on 2026-09-27: `registry:2.8.3` started with
`REGISTRY_STORAGE_MAINTENANCE_READONLY='{"enabled":true}'` answers uploads with 405 and still
serves `GET /v2/`, so nodes keep pulling. `RegistryJanitorTest` runs this garbage collection
against the same image after 30 deploys and checks that the kept images and cache survive it.

On 2.8.3 garbage collection keeps only the manifests a tag points at, so it also removes the
manifests that a tagged OCI index or manifest list lists, with their config and layers. While the
timer runs, this registry must hold no index or list whose entries are manifests. Builds push
their image and `:cache` as single OCI manifests (checked on 2026-09-27 by running
`build-image/build.sh` with buildctl v0.33.0 against a scratch registry). The platform's own
images are indexes: on 2026-09-27, `HEAD` with an `Accept` of the OCI index and manifest types and
the Docker list and manifest types answered `application/vnd.oci.image.index.v1+json` for
`liftgate/build-image`, `liftgate/control-plane` and `liftgate/dashboard` at `0.1.0-alpha.6`,
each listing an amd64 manifest and an attestation manifest, and nodes pull them from here. On a
copy of `/srv/liftgate-registry`, garbage collection left their amd64 manifests unfetchable and
shrank the copy from 414 MB to 1 MB; after the re-push in step 4 of the switch-over it kept all
three with every layer. `RegistryJanitorTest` checks both shapes.

Install it only after the switch-over above, because it starts the registry from `config.yml`.
`/usr/local/sbin/liftgate-registry-gc`, mode 755:

```sh
#!/bin/sh
set -eu
registry() {
  docker rm -f liftgate-registry >/dev/null
  docker run -d --name liftgate-registry --network host --restart unless-stopped \
    -v /srv/liftgate-registry:/var/lib/registry \
    -v /etc/liftgate-registry/config.yml:/etc/docker/registry/config.yml:ro \
    -v /etc/liftgate-registry/token.crt:/etc/docker/registry/token.crt:ro \
    "$@" registry:2.8.3 >/dev/null
}
trap registry EXIT
registry -e REGISTRY_STORAGE_MAINTENANCE_READONLY='{"enabled":true}'
docker exec liftgate-registry registry garbage-collect --delete-untagged /etc/docker/registry/config.yml
```

`/etc/systemd/system/liftgate-registry-gc.service`:

```ini
[Unit]
Description=Liftgate registry garbage collection
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/liftgate-registry-gc
```

`/etc/systemd/system/liftgate-registry-gc.timer`:

```ini
[Unit]
Description=Weekly Liftgate registry garbage collection

[Timer]
OnCalendar=Sun *-*-* 04:30:00 UTC
RandomizedDelaySec=15m
Persistent=true

[Install]
WantedBy=timers.target
```

```sh
systemctl daemon-reload
systemctl enable --now liftgate-registry-gc.timer
systemctl start liftgate-registry-gc.service
journalctl -u liftgate-registry-gc.service -n 50
du -sh /srv/liftgate-registry
```

A build that pushes during the run fails and can be redeployed.
