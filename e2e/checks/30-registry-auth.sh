#!/usr/bin/env sh
set -eu

ns=registry-e2e
registry=registry.$ns.svc.cluster.local:5000
realm=http://liftgate-control-plane.liftgate-system.svc.cluster.local:8080/api/v1/registry/token
own=00000000-0000-4000-8000-000000000311
rival=00000000-0000-4000-8000-000000000312
own_password=$(openssl rand -hex 16)
rival_password=$(openssl rand -hex 16)
pull_password=$(openssl rand -hex 16)
janitor_password=$(openssl rand -hex 16)
work=$(mktemp -d)

report() {
  code=$?
  if [ "$code" -ne 0 ]; then
    kubectl -n $ns get pods -o wide || true
    for pod in $(kubectl -n $ns get pods -o name); do kubectl -n $ns logs "$pod" --all-containers --prefix --tail=80 || true; done
    kubectl -n liftgate-system logs deployment/liftgate-control-plane --tail=80 || true
  fi
  rm -rf "$work"
  exit "$code"
}
trap report EXIT

expect() {
  [ "$2" = "$3" ] || { echo "$1: expected $3, got $2" >&2; exit 1; }
}

probe() {
  kubectl -n $ns exec probe -- "$@"
}

token_status() {
  probe curl -s -o /dev/null -w '%{http_code}' -u "$1:$2" "$realm?service=$registry&scope=repository:$3"
}

token() {
  probe curl -sS --fail -u "$1:$2" "$realm?service=$registry&scope=repository:$3" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

registry_status() {
  probe curl -s -o /dev/null -w '%{http_code}' -X "$1" -H "Authorization: Bearer $3" "http://$registry/v2/$2"
}

finished() {
  for attempt in $(seq 120); do
    phase=$(kubectl -n $ns get pod "$1" -o jsonpath='{.status.phase}')
    case "$phase" in Succeeded | Failed) break ;; esac
    sleep 5
  done
  echo "$phase"
}

build() {
  auth=$(printf 'build-%s:%s' "$2" "$3" | base64 -w0)
  kubectl -n $ns create secret generic "build-$2" --from-literal=token=ghs_e2e_git_token \
    --from-literal=.dockerconfigjson="{\"auths\":{\"$registry\":{\"auth\":\"$auth\"}}}"
  kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $1
  namespace: $ns
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
  initContainers:
    - name: repository
      image: liftgate/build-image:e2e
      imagePullPolicy: Never
      command:
        - sh
        - -c
        - git init -q /repository/app && cp /fixture/Dockerfile /repository/app && git -C /repository/app add Dockerfile && git -C /repository/app -c user.name=e2e -c user.email=e2e@liftgate.test commit -qm fixture
      volumeMounts:
        - name: fixture
          mountPath: /fixture
        - name: repository
          mountPath: /repository
    - name: clone
      image: liftgate/build-image:e2e
      imagePullPolicy: Never
      command: [/usr/local/bin/clone.sh]
      env:
        - name: LIFTGATE_REPO_URL
          value: file:///repository/app
        - name: LIFTGATE_COMMIT
          value: HEAD
        - name: LIFTGATE_GIT_TOKEN
          valueFrom:
            secretKeyRef:
              name: build-$2
              key: token
      volumeMounts:
        - name: repository
          mountPath: /repository
        - name: workspace
          mountPath: /workspace
  containers:
    - name: build
      image: liftgate/build-image:e2e
      imagePullPolicy: Never
      env:
        - name: LIFTGATE_ROOT_DIR
          value: /
        - name: LIFTGATE_BUILD_STRATEGY
          value: dockerfile
        - name: LIFTGATE_DOCKERFILE_PATH
          value: Dockerfile
        - name: IMAGE
          value: $registry/$4:latest
        - name: CACHE
          value: $registry/$4:cache
        - name: DOCKER_CONFIG
          value: /home/user/.docker
        - name: LIFTGATE_REGISTRY_INSECURE
          value: "true"
      securityContext:
        seccompProfile:
          type: Unconfined
        appArmorProfile:
          type: Unconfined
      volumeMounts:
        - name: docker-config
          mountPath: /home/user/.docker
          readOnly: true
        - name: workspace
          mountPath: /workspace
        - name: buildkit
          mountPath: /home/user/.config/buildkit
  volumes:
    - name: fixture
      configMap:
        name: $1
    - name: repository
      emptyDir: {}
    - name: workspace
      emptyDir: {}
    - name: docker-config
      secret:
        secretName: build-$2
        items:
          - key: .dockerconfigjson
            path: config.json
    - name: buildkit
      configMap:
        name: buildkit
EOF
}

openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=liftgate-registry-token \
  -keyout "$work/token.key" -out "$work/token.crt" 2> /dev/null
docker buildx build --load --quiet --tag liftgate/build-image:e2e build-image
kind load docker-image --name liftgate liftgate/build-image:e2e

kubectl create namespace $ns
kubectl -n $ns create configmap registry --from-file=config.yml=infra/registry/config.yml --from-file=token.crt="$work/token.crt"
printf '[registry."%s"]\n  http = true\n' "$registry" > "$work/buildkitd.toml"
kubectl -n $ns create configmap buildkit --from-file=buildkitd.toml="$work/buildkitd.toml"
kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: registry
  namespace: $ns
spec:
  selector:
    matchLabels:
      app: registry
  template:
    metadata:
      labels:
        app: registry
    spec:
      containers:
        - name: registry
          image: registry:2.8.3
          env:
            - name: REGISTRY_HTTP_ADDR
              value: ":5000"
            - name: REGISTRY_AUTH_TOKEN_REALM
              value: $realm
            - name: REGISTRY_AUTH_TOKEN_SERVICE
              value: $registry
          volumeMounts:
            - name: config
              mountPath: /etc/docker/registry
      volumes:
        - name: config
          configMap:
            name: registry
---
apiVersion: v1
kind: Service
metadata:
  name: registry
  namespace: $ns
spec:
  selector:
    app: registry
  ports:
    - port: 5000
EOF

helm upgrade liftgate charts/liftgate --namespace liftgate-system --reuse-values \
  --set registry=$registry --set registryAuth=token --set registryPullPassword="$pull_password" \
  --set registryJanitorPassword="$janitor_password" \
  --set-file registryTokenKey="$work/token.key" --set-file registryTokenCertificate="$work/token.crt"
kubectl -n liftgate-system rollout status deployment/liftgate-control-plane --timeout=10m
kubectl -n $ns rollout status deployment/registry --timeout=5m

for build in $own $rival; do
  kubectl apply -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: build-$build
  namespace: liftgate-build
  labels:
    liftgate.dev/build: $build
spec:
  suspend: true
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: build
          image: busybox:1.36
EOF
done

kubectl -n liftgate-system exec -i liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 \
  --set own="$own_password" --set rival="$rival_password" <<'EOF'
insert into organizations (id, slug, name) values
    ('00000000-0000-4000-8000-000000000301', 'e2e-rival', 'Rival');

insert into projects (id, org_id, slug, name, repo_full_name, installation_id) values
    ('00000000-0000-4000-8000-000000000302', '00000000-0000-4000-8000-000000000301', 'app', 'App', 'e2e-rival/app', 1);

insert into environments (id, project_id, slug, name, kind, branch, namespace) values
    ('00000000-0000-4000-8000-000000000303', '00000000-0000-4000-8000-000000000302', 'production', 'Production', 'production', 'main', 'env-e2e-rival');

insert into services (id, environment_id, slug, name, kind) values
    ('00000000-0000-4000-8000-000000000304', '00000000-0000-4000-8000-000000000303', 'web', 'Web', 'worker');

insert into builds (id, service_id, commit_sha, branch, status, started_at, registry_secret_hash) values
    ('00000000-0000-4000-8000-000000000311', '00000000-0000-4000-8000-000000000005', 'HEAD', 'main', 'running', now(),
     rtrim(translate(encode(sha256(convert_to(:'own', 'UTF8')), 'base64'), '+/', '-_'), '=')),
    ('00000000-0000-4000-8000-000000000312', '00000000-0000-4000-8000-000000000304', 'HEAD', 'main', 'running', now(),
     rtrim(translate(encode(sha256(convert_to(:'rival', 'UTF8')), 'base64'), '+/', '-_'), '='));
EOF

kubectl -n $ns run probe --image=liftgate/build-image:e2e --image-pull-policy=Never --restart=Never --command -- sleep 3600
kubectl -n $ns wait pod/probe --for=condition=Ready --timeout=2m

expect "anonymous registry access" "$(probe curl -s -o /dev/null -w '%{http_code}' "http://$registry/v2/")" 401
mine=$(token "build-$own" "$own_password" e2e/hello/production/web:pull,push)
expect "push to its own repository" "$(registry_status POST e2e/hello/production/web/blobs/uploads/ "$mine")" 202
theirs=$(token "build-$own" "$own_password" e2e-rival/app/production/web:pull,push)
expect "push to another org's repository" "$(registry_status POST e2e-rival/app/production/web/blobs/uploads/ "$theirs")" 401

cat > "$work/isolated" <<'EOF'
FROM busybox:1.36
RUN echo "buildkitd-visible $(cat /proc/*/comm 2>/dev/null | grep -c -x buildkitd)" \
 && echo "git-token $(grep -l LIFTGATE_GIT_TOKEN /proc/*/environ 2>/dev/null | wc -l)" \
 && echo "docker-config $(ls /proc/*/root/home/user/.docker 2>/dev/null | wc -l)"
EOF
kubectl -n $ns create configmap isolated --from-file=Dockerfile="$work/isolated"
build isolated "$rival" "$rival_password" e2e-rival/app/production/web
expect "isolated build" "$(finished isolated)" Succeeded
kubectl -n $ns logs pod/isolated -c build > "$work/isolated.log"
grep -E ' (buildkitd-visible|git-token|docker-config) [0-9]+$' "$work/isolated.log"
expect "buildkitd seen from a build step" "$(sed -n 's/.* buildkitd-visible //p' "$work/isolated.log")" 1
expect "git token seen from a build step" "$(sed -n 's/.* git-token //p' "$work/isolated.log")" 0
expect "docker config listed from a build step" "$(sed -n 's/.* docker-config //p' "$work/isolated.log")" 0

pulled=$(token pull "$pull_password" e2e-rival/app/production/web:pull,push)
expect "node pull of a tenant image" "$(registry_status GET e2e-rival/app/production/web/tags/list "$pulled")" 200
expect "node push" "$(registry_status POST e2e-rival/app/production/web/blobs/uploads/ "$pulled")" 401
pruning=$(token janitor "$janitor_password" e2e-rival/app/production/web:pull,push,delete)
expect "janitor read of a tenant image" "$(registry_status GET e2e-rival/app/production/web/tags/list "$pruning")" 200
expect "janitor push" "$(registry_status POST e2e-rival/app/production/web/blobs/uploads/ "$pruning")" 401
theirs=$(token "build-$own" "$own_password" e2e-rival/app/production/web:pull)
expect "read of another org's image" "$(registry_status GET e2e-rival/app/production/web/tags/list "$theirs")" 401

printf 'FROM %s/e2e-rival/app/production/web:latest\n' "$registry" > "$work/cross"
kubectl -n $ns create configmap cross --from-file=Dockerfile="$work/cross"
build cross "$own" "$own_password" e2e/hello/production/web
expect "build from another org's image" "$(finished cross)" Failed
kubectl -n $ns logs pod/cross -c build | grep -i -E '401|403|insufficient_scope|unauthorized'

expect "token for a running build" "$(token_status "build-$own" "$own_password" e2e/hello/production/web:pull)" 200
kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --set ON_ERROR_STOP=1 \
  --command "update builds set status = 'succeeded', finished_at = now() where id = '$own'"
expect "token after the build finished" "$(token_status "build-$own" "$own_password" e2e/hello/production/web:pull)" 401
