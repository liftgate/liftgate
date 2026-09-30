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
    --from-literal=.dockerconfigjson="{\"auths\":{\"$registry\":{\"auth\":\"$auth\"}}}" --dry-run=client --output yaml | kubectl apply -f -
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
          value: $registry/$4:${5:-latest}
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
