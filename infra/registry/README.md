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
while it runs. Nodes log in as `pull`, which can read every repository and write none.

## Switch-over

The registry accepts anonymous requests until step 4, so builds keep pushing and nodes keep
pulling while Liftgate and the nodes get their logins; step 4 turns authentication on once both
have them.

1. Create the signing key, its certificate and the pull password:

   ```sh
   install -d -m 700 /etc/liftgate-registry
   cd /etc/liftgate-registry
   openssl req -x509 -newkey rsa:4096 -nodes -days 3650 -subj /CN=liftgate-registry-token \
     -keyout token.key -out token.crt
   openssl rand -hex 32 > pull-password
   ```

2. Upgrade Liftgate with `registryAuth=token`, `registryTokenKey` from `token.key`,
   `registryTokenCertificate` from `token.crt` and `registryPullPassword`, as in the chart
   README. Then run a build and confirm it pushes.

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

4. Replace the container with one that reads `config.yml` and the certificate:

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
