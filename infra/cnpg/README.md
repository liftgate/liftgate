# CloudNativePG

The operator runs PostgreSQL for Liftgate when the chart is installed with
`postgres.managed=true` (the default). `install.sh` installs it:

```sh
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm upgrade --install cnpg cnpg/cloudnative-pg --version 0.29.0 -n cnpg-system --create-namespace --wait
```

The chart then renders a `Cluster` named `<release>-postgres` (1 instance for `single`, 3 for
`ha`) with database `liftgate` owned by `liftgate`. The operator generates the credentials in
Secret `<release>-postgres-app` and exposes the primary as Service `<release>-postgres-rw`.

Useful commands:

```sh
kubectl -n liftgate-system get cluster
kubectl -n liftgate-system get secret liftgate-postgres-app -o jsonpath='{.data.password}' | base64 -d
kubectl -n liftgate-system exec -it liftgate-postgres-1 -- psql -U postgres liftgate
```

## Backups

`postgres.backup` in the chart turns on continuous WAL archiving and scheduled base backups
through the Barman Cloud plugin; see Backups in the [chart README](../../charts/liftgate/README.md#backups).
The archive holds one folder per server name under `destinationPath`: `liftgate-postgres` until
the first restore, then the value of `postgres.backup.serverName`.

## Restore

A restore replaces the `Cluster` in place. It keeps its name, Services and Secret name, recovers
into new volumes from the object store, and archives to a new folder from then on.

1. Pick the target time in RFC 3339, for example `2026-09-27T10:00:00Z`, or leave `recoverTo`
   out to replay everything that was archived. A completed backup must be older than the target:

   ```sh
   kubectl -n liftgate-system get backups
   ```

2. Delete the `Cluster`. This removes the Postgres pods, their volumes and the
   `liftgate-postgres-app` Secret; the object store is untouched.

   ```sh
   kubectl -n liftgate-system delete cluster liftgate-postgres
   kubectl -n liftgate-system wait pvc --selector cnpg.io/cluster=liftgate-postgres --for=delete --timeout=10m
   kubectl -n liftgate-system wait secret/liftgate-postgres-app --for=delete --timeout=10m
   ```

3. Recreate it from the archive, and keep the three values in your values file afterwards.
   `recoverFrom` is the folder the lost cluster archived to. `serverName` must be a folder that
   has never been used: CloudNativePG refuses to archive into one that already holds WAL, and the
   chart refuses to render when the two are equal.

   ```sh
   helm upgrade liftgate charts/liftgate -n liftgate-system -f liftgate-values.yaml \
     --set postgres.backup.recoverFrom=liftgate-postgres \
     --set postgres.backup.recoverTo=2026-09-27T10:00:00Z \
     --set postgres.backup.serverName=liftgate-postgres-20260927
   kubectl -n liftgate-system wait cluster/liftgate-postgres --for=condition=Ready --timeout=60m
   ```

4. Restart the control plane. The restore generates a new password for the `liftgate` role,
   and running pods still hold the old one.

   ```sh
   kubectl -n liftgate-system rollout restart deployment --selector app.kubernetes.io/component=control-plane
   kubectl -n liftgate-system rollout status deployment --selector app.kubernetes.io/component=control-plane
   ```

5. The `ScheduledBackup` is named after `serverName`, so the restored cluster takes its first
   base backup as soon as it is ready. Point-in-time restores of the new folder work once it has
   completed:

   ```sh
   kubectl -n liftgate-system get backups --selector cnpg.io/scheduled-backup=liftgate-postgres-20260927
   ```

A restore rewinds only the database. Namespaces, Deployments, routes and registry images created
after the target time stay in the cluster. The outbox relay publishes every outbox row that is
unpublished in the restored database, including rows it had already published after the target
time.

## Key escrow

The Postgres backups do not contain `secrets.masterKey` or `github.privateKey`; the chart keeps
them in a Secret that `helm uninstall` deletes. Every stored environment variable is sealed with
`secrets.masterKey`, so a restored database is only useful with that exact key, and it cannot be
reissued. Keep both offline in two separate places, for example a password manager and an
encrypted drive stored elsewhere. A lost GitHub App key can be replaced by generating a new one
in the App's settings.

## Point-in-time drill

The drill restores the production archive next to the running database, so nothing in
production changes. It needs disk space for two more copies of the database. Run it in the
release namespace, where the chart's `ObjectStore` lives, and use the production values for
`storage.size` and `max_connections`.

1. Restore the latest state into `drill-a`, which archives to its own folder. Point
   `externalClusters` at the folder production archives to (`liftgate-postgres` unless it has
   been restored before). The time from `apply` to `Ready` is the restore time of the whole
   database.

   ```yaml
   apiVersion: postgresql.cnpg.io/v1
   kind: Cluster
   metadata:
     name: drill-a
   spec:
     instances: 1
     postgresql:
       parameters:
         max_connections: "100"
     storage:
       size: 10Gi
     bootstrap:
       recovery:
         source: origin
         database: liftgate
         owner: liftgate
     externalClusters:
       - name: origin
         plugin:
           name: barman-cloud.cloudnative-pg.io
           parameters:
             barmanObjectName: liftgate-postgres
             serverName: liftgate-postgres
     plugins:
       - name: barman-cloud.cloudnative-pg.io
         isWALArchiver: true
         parameters:
           barmanObjectName: liftgate-postgres
           serverName: drill-a
   ```

   ```sh
   date -u; kubectl -n liftgate-system apply -f drill-a.yaml
   kubectl -n liftgate-system wait cluster/drill-a --for=condition=Ready --timeout=60m; date -u
   ```

2. Take a base backup of `drill-a`:

   ```sh
   kubectl -n liftgate-system apply -f - <<EOF
   apiVersion: postgresql.cnpg.io/v1
   kind: Backup
   metadata:
     name: drill-a
   spec:
     cluster:
       name: drill-a
     method: plugin
     pluginConfiguration:
       name: barman-cloud.cloudnative-pg.io
   EOF
   kubectl -n liftgate-system wait backup/drill-a --for=jsonpath='{.status.phase}'=completed --timeout=60m
   ```

3. At least 11 minutes after the backup completed, count the projects, note the time and delete
   them all:

   ```sh
   psql() { kubectl -n liftgate-system exec drill-a-1 -c postgres -- psql -U postgres -d liftgate -At "$@"; }
   psql -c 'select count(*) from projects'
   date -u +%Y-%m-%dT%H:%M:%SZ
   psql -c 'delete from projects' -c 'select pg_switch_wal()'
   ```

4. Restore `drill-a` to 10 minutes before the delete into `drill-b`: the manifest from step 1
   with name `drill-b`, `serverName: drill-a` under `externalClusters`, no `plugins` section, and

   ```yaml
       recovery:
         source: origin
         database: liftgate
         owner: liftgate
         recoveryTarget:
           targetTime: "<delete time minus 10 minutes>"
   ```

   Time it from `apply` to `Ready` as in step 1, then check that
   `select count(*) from projects` on `drill-b-1` matches the count from step 3.

5. Clean up, then delete the `drill-a` folder from the bucket with any S3 client:

   ```sh
   kubectl -n liftgate-system delete cluster drill-a drill-b
   kubectl -n liftgate-system delete backup drill-a
   ```

## Drill log

No drill has been recorded yet. Add a line per drill: the date, the database size, the time to
`Ready` of `drill-a` and `drill-b`, and whether the project count matched.
