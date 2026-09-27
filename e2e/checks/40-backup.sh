#!/usr/bin/env sh
set -eu

ns=liftgate-system
access="GK$(openssl rand -hex 12)"
secret="$(openssl rand -hex 32)"

sql() {
  kubectl -n "$ns" exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --tuples-only --no-align --set ON_ERROR_STOP=1 --command "$1"
}
eventually() {
  for attempt in $(seq 60); do "$@" && return; sleep 5; done
  "$@"
}
sidecar() {
  kubectl -n "$ns" get pod liftgate-postgres-1 --output jsonpath='{.spec.initContainers[*].name}' | grep -qw plugin-barman-cloud
}
archived() {
  test "$(sql "select last_archived_wal >= '$wal' from pg_stat_archiver")" = t
}
backed_up() {
  test "$(kubectl -n "$ns" get backups --selector "cnpg.io/scheduled-backup=$1" --output jsonpath='{.items[*].status.phase}')" = completed
}

test "$(helm get manifest liftgate --namespace "$ns" | grep -c -e ObjectStore -e ScheduledBackup -e barman-cloud)" = 0

if ! kubectl get crd objectstores.barmancloud.cnpg.io > /dev/null 2>&1; then
  kubectl apply --filename https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/v0.15.0/manifest.yaml
  kubectl -n cnpg-system rollout status deployment/barman-cloud --timeout=5m
fi

kubectl apply --filename - <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: garage
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: garage
  namespace: garage
data:
  garage.toml: |
    metadata_dir = "/var/lib/garage/meta"
    data_dir = "/var/lib/garage/data"
    db_engine = "sqlite"
    replication_factor = 1
    rpc_bind_addr = "[::]:3901"
    rpc_public_addr = "127.0.0.1:3901"
    rpc_secret = "$(openssl rand -hex 32)"

    [s3_api]
    s3_region = "garage"
    api_bind_addr = "[::]:3900"
---
apiVersion: v1
kind: Pod
metadata:
  name: garage
  namespace: garage
  labels:
    app: garage
spec:
  containers:
    - name: garage
      image: dxflrs/garage:v2.4.1
      command: [/garage, server, --single-node, --default-bucket]
      env:
        - name: GARAGE_DEFAULT_ACCESS_KEY
          value: "$access"
        - name: GARAGE_DEFAULT_SECRET_KEY
          value: "$secret"
        - name: GARAGE_DEFAULT_BUCKET
          value: liftgate-pg
      volumeMounts:
        - name: config
          mountPath: /etc/garage.toml
          subPath: garage.toml
        - name: data
          mountPath: /var/lib/garage
  volumes:
    - name: config
      configMap:
        name: garage
    - name: data
      emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: garage
  namespace: garage
spec:
  selector:
    app: garage
  ports:
    - port: 3900
EOF
kubectl -n garage wait pod/garage --for=condition=Ready --timeout=5m
kubectl -n "$ns" create secret generic liftgate-postgres-backup \
  --from-literal=ACCESS_KEY_ID="$access" \
  --from-literal=ACCESS_SECRET_KEY="$secret" \
  --from-literal=ACCESS_REGION=garage

helm upgrade liftgate charts/liftgate --namespace "$ns" --reuse-values \
  --set postgres.backup.enabled=true \
  --set postgres.backup.endpointUrl=http://garage.garage.svc:3900 \
  --set postgres.backup.destinationPath=s3://liftgate-pg/
kubectl -n "$ns" get objectstore/liftgate-postgres scheduledbackup/liftgate-postgres
eventually sidecar
kubectl -n "$ns" wait pod/liftgate-postgres-1 --for=condition=Ready --timeout=5m

kubectl -n "$ns" apply --filename - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: e2e
spec:
  cluster:
    name: liftgate-postgres
  method: plugin
  pluginConfiguration:
    name: barman-cloud.cloudnative-pg.io
EOF
kubectl -n "$ns" wait backup/e2e --for=jsonpath='{.status.phase}'=completed --timeout=5m

sleep 2
target="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sql "update organizations set name = 'Renamed after the target' where slug = 'e2e'"
wal="$(sql "select pg_walfile_name(pg_switch_wal())")"
eventually archived

test "$(kubectl -n "$ns" get pvc --selector cnpg.io/cluster=liftgate-postgres --output name)" = persistentvolumeclaim/liftgate-postgres-1
pv="$(kubectl -n "$ns" get pvc --selector cnpg.io/cluster=liftgate-postgres --output jsonpath='{.items[*].spec.volumeName}')"
test "$(kubectl get pv "$pv" --output jsonpath='{.spec.persistentVolumeReclaimPolicy}')" = Delete
kubectl patch pv "$pv" --patch '{"spec":{"persistentVolumeReclaimPolicy":"Retain"}}'
kubectl -n "$ns" delete cluster liftgate-postgres
kubectl -n "$ns" wait pvc --selector cnpg.io/cluster=liftgate-postgres --for=delete --timeout=5m
kubectl -n "$ns" wait secret/liftgate-postgres-app --for=delete --timeout=5m
kubectl wait pv "$pv" --for=jsonpath='{.status.phase}'=Released --timeout=5m

helm upgrade liftgate charts/liftgate --namespace "$ns" --reuse-values \
  --set postgres.backup.recoverFrom=liftgate-postgres \
  --set postgres.backup.recoverTo="$target" \
  --set postgres.backup.serverName=liftgate-postgres-restored
kubectl -n "$ns" wait cluster/liftgate-postgres --for=condition=Ready --timeout=10m
test "$(sql "select count(*) from projects where slug = 'hello'")" = 1
test "$(sql "select name from organizations where slug = 'e2e'")" = "End to end"
eventually backed_up liftgate-postgres-restored
kubectl patch pv "$pv" --patch '{"spec":{"persistentVolumeReclaimPolicy":"Delete"}}'
kubectl wait pv "$pv" --for=delete --timeout=5m

kubectl -n "$ns" rollout restart deployment/liftgate-control-plane
kubectl -n "$ns" rollout status deployment/liftgate-control-plane --timeout=5m
kubectl -n "$ns" port-forward service/liftgate-control-plane 8080:8080 > /dev/null &
trap "kill $!" EXIT
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 http://localhost:8080/readyz
