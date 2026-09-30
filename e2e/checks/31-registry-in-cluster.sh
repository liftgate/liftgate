#!/usr/bin/env sh
set -eu

ns=liftgate-build
system=liftgate-system
address=$(kubectl get service kubernetes -o jsonpath='{.spec.clusterIP}' | sed 's/[0-9]*$/50/')
registry=$address:5000
realm=http://$address:5001/api/v1/registry/token
service=00000000-0000-4000-8000-000000000321
own=00000000-0000-4000-8000-000000000322
deployment=00000000-0000-4000-8000-000000000323
repository=e2e/hello/production/registry
rival=e2e-b/hello-b/production/web
shipped=$(openssl rand -hex 20)
doomed=$(openssl rand -hex 20)
image=$registry/$repository:$shipped
own_password=$(openssl rand -hex 16)
pull_password=$(openssl rand -hex 16)
janitor_password=$(openssl rand -hex 16)
node=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
work=$(mktemp -d)

. e2e/registry.sh

report() {
  code=$?
  if [ "$code" -ne 0 ]; then
    kubectl -n $system get pods -o wide || true
    kubectl -n $system logs statefulset/liftgate-registry --all-containers --prefix --tail=80 || true
    kubectl -n $system logs deployment/liftgate-control-plane -c control-plane --tail=80 || true
    for pod in registry-doomed registry-rival registry-app; do kubectl -n $ns logs "$pod" --all-containers --prefix --tail=40 || true; done
    kubectl -n env-e2e describe pods -l liftgate.dev/service=registry || true
    docker exec liftgate-control-plane journalctl -u containerd --no-pager -n 40 || true
  fi
  kubectl -n $system delete httproute registry-edge --ignore-not-found
  kubectl -n $ns delete pod probe --ignore-not-found --wait=false
  kubectl -n default delete pod registry-outsider --ignore-not-found --wait=false
  rm -rf "$work"
  exit "$code"
}
trap report EXIT

expect() {
  [ "$2" = "$3" ] || { echo "FAIL: $1 is '$2', expected '$3'" >&2; exit 1; }
  echo "$1: $2"
}

sql() {
  kubectl -n $system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 "$@"
}

blob() {
  probe curl -s -o /dev/null -w '%{http_code}' -I -H "Authorization: Bearer $(token janitor "$janitor_password" "$repository:pull")" \
    "http://$registry/v2/$repository/blobs/$1"
}

manifest() {
  probe curl -sS --fail -H "Authorization: Bearer $(token janitor "$janitor_password" "$repository:pull")" \
    -H 'Accept: application/vnd.oci.image.manifest.v1+json' "http://$registry/v2/$repository/manifests/$1"
}

tcp() {
  kubectl -n "$1" exec "$2" -- sh -c 'nc -z -w 5 "$0" "$1" && echo open || echo closed' "$3" "$4"
}

docker exec -i liftgate-control-plane sh -c "mkdir -p /etc/containerd/certs.d/$registry \
  && echo 'server = \"http://$registry\"' > /etc/containerd/certs.d/$registry/hosts.toml \
  && cat >> /etc/containerd/config.toml && systemctl restart containerd" <<EOF

[plugins."io.containerd.grpc.v1.cri".registry]
  config_path = "/etc/containerd/certs.d"
[plugins."io.containerd.grpc.v1.cri".registry.configs."$registry".auth]
  username = "pull"
  password = "$pull_password"
EOF
for attempt in $(seq 30); do docker exec liftgate-control-plane crictl info > /dev/null 2>&1 && break; sleep 2; done

signing_key
helm upgrade liftgate charts/liftgate --namespace $system --reuse-values \
  --set inClusterRegistry.enabled=true --set inClusterRegistry.clusterIP="$address" \
  --set registryPullPassword="$pull_password" --set registryJanitorPassword="$janitor_password" \
  --set-file registryTokenKey="$work/token.key" --set-file registryTokenCertificate="$work/token.crt"
kubectl -n $system rollout status statefulset/liftgate-registry --timeout=5m
kubectl -n $system rollout status deployment/liftgate-control-plane --timeout=10m

kubectl -n $ns run probe --image=liftgate/build-image:e2e --image-pull-policy=Never --restart=Never --command -- sleep 1800
kubectl -n default run registry-outsider --image=busybox:1.36 --restart=Never --command -- sleep 1800
kubectl -n $ns wait pod/probe --for=condition=Ready --timeout=2m
kubectl -n default wait pod/registry-outsider --for=condition=Ready --timeout=2m

pod=$(kubectl -n $system get pod liftgate-registry-0 -o jsonpath='{.status.podIP}')
expect "anonymous build request to the registry" "$(probe curl -s -o /dev/null -w '%{http_code}' "http://$registry/v2/")" 401
expect "build request to another path of the token proxy" "$(probe curl -s -o /dev/null -w '%{http_code}' "http://$address:5001/api/v1/me")" 404
for target in "$address 5000" "$address 5001" "$pod 5000" "$pod 5001"; do
  expect "tenant tcp to $target" "$(tcp env-e2e deploy/web $target)" closed
  expect "tcp from another namespace to $target" "$(tcp default registry-outsider $target)" closed
done
for port in 5000 5001; do
  expect "tcp from outside the cluster to the node's port $port" "$(timeout 5 bash -c "< /dev/tcp/$node/$port" 2> /dev/null && echo open || echo closed)" closed
done
kubectl apply -f - <<EOF
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: registry-edge
  namespace: $system
spec:
  parentRefs:
    - name: liftgate
      sectionName: https-apps
  hostnames:
    - registry-edge.liftgate.app
  rules:
    - backendRefs:
        - name: liftgate-registry
          port: 5000
EOF
for attempt in $(seq 60); do
  edge=$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 20 --resolve registry-edge.liftgate.app:443:$node https://registry-edge.liftgate.app/v2/ || true)
  case "$edge" in 401 | 503) break ;; esac
  sleep 2
done
expect "gateway route to the registry" "$edge" 503
kubectl -n $system delete httproute registry-edge

sql <<EOF
insert into services (id, environment_id, slug, name, kind, port, cpu_millis, memory_mb) values
    ('$service', '00000000-0000-4000-8000-000000000004', 'registry', 'Registry', 'web', 8080, 100, 64);
insert into domains (id, service_id, hostname, kind, verified_at, certificate_status) values
    ('00000000-0000-4000-8000-000000000324', '$service', 'registry-hello-e2e.liftgate.app', 'platform', now(), 'issued');
insert into builds (id, service_id, commit_sha, branch, status, started_at, registry_secret_hash) values
    ('$own', '$service', '$shipped', 'main', 'running', now(),
     '$(printf %s "$own_password" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=')');
EOF
job $own

printf 'FROM busybox:1.36\nRUN head -c 64 /dev/urandom > /doomed\n' > "$work/doomed"
printf 'FROM busybox:1.36\nRUN mkdir /www && echo in-cluster-registry > /www/index.html\nCMD ["httpd", "-f", "-p", "8080", "-h", "/www"]\n' > "$work/app"
kubectl -n $ns create configmap registry-doomed --from-file=Dockerfile="$work/doomed"
kubectl -n $ns create configmap registry-rival --from-file=Dockerfile="$work/doomed"
kubectl -n $ns create configmap registry-app --from-file=Dockerfile="$work/app"
build registry-doomed "$own" "$own_password" $repository "$doomed"
build registry-rival "$own" "$own_password" $rival "$shipped"
expect "build of an image no deployment uses" "$(finished registry-doomed)" Succeeded
build registry-app "$own" "$own_password" $repository "$shipped"
expect "build pushing to another org's repository" "$(finished registry-rival)" Failed
kubectl -n $ns logs pod/registry-rival -c build | grep -i -E '401|403|insufficient_scope|unauthorized'
expect "build of the app" "$(finished registry-app)" Succeeded

mine=$(token "build-$own" "$own_password" "$repository:pull")
theirs=$(token "build-$own" "$own_password" "$rival:pull")
expect "build pull of its own repository" "$(registry_status GET $repository/tags/list "$mine")" 200
expect "build pull of another org's repository" "$(registry_status GET $rival/tags/list "$theirs")" 401

sql <<EOF
update builds set status = 'succeeded', image_ref = '$image', finished_at = now() where id = '$own';
insert into deployments (id, service_id, build_id, status) values ('$deployment', '$service', '$own', 'pending');
insert into outbox (subject, payload) values ('liftgate.release.requested', '{"deploymentId": "$deployment"}');
EOF
kubectl -n env-e2e wait deployment/registry --for=create --timeout=5m
kubectl -n env-e2e rollout status deployment/registry --timeout=5m
pulled=$(kubectl -n env-e2e get pods -l liftgate.dev/service=registry -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')
expect "image the node pulled" "${pulled%@*}" "$registry/$repository"
expect "app through the gateway" "$(curl --fail --silent --show-error --insecure --retry 30 --retry-all-errors --retry-delay 2 \
  --resolve registry-hello-e2e.liftgate.app:443:$node https://registry-hello-e2e.liftgate.app)" in-cluster-registry

layer=$(manifest "$doomed" | jq -r '.layers[-1].digest')
old=$(kubectl -n $system get pods -l app.kubernetes.io/component=control-plane -o name)
kubectl -n $system rollout restart deployment/liftgate-control-plane
kubectl -n $system rollout status deployment/liftgate-control-plane --timeout=10m
kubectl -n $system wait --for=delete $old --timeout=5m
for attempt in $(seq 60); do
  kubectl -n $system logs deployment/liftgate-control-plane -c control-plane | grep 'registry janitor deleted' && break
  sleep 2
done
expect "tags after the janitor ran" "$(probe curl -sS --fail -H "Authorization: Bearer $(token janitor "$janitor_password" "$repository:pull")" \
  "http://$registry/v2/$repository/tags/list" | jq -r '.tags | sort | join(" ")')" "$(jq -nr --arg tag "$shipped" '[$tag, "cache"] | sort | join(" ")')"
expect "unreferenced layer before garbage collection" "$(blob "$layer")" 200

kubectl -n $system create job registry-gc-e2e --from=cronjob/liftgate-registry-gc
kubectl -n $system wait job/registry-gc-e2e --for=condition=complete --timeout=3m
kubectl -n $system rollout status statefulset/liftgate-registry --timeout=5m
for attempt in $(seq 60); do test "$(blob "$layer")" = 404 && break; sleep 2; done
expect "unreferenced layer after garbage collection" "$(blob "$layer")" 404
kubectl -n $system logs liftgate-registry-0 -c registry | grep -F "${layer#sha256:}"
for digest in $(manifest "$shipped" | jq -r '.config.digest, .layers[].digest'); do
  expect "blob $digest of the running image after garbage collection" "$(blob "$digest")" 200
done
docker exec liftgate-control-plane crictl rmi "$image"
docker exec liftgate-control-plane crictl pull "$image"
