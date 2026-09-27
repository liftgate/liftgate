# Upgrading Liftgate

Upgrade from a checkout of the new tag, with the values file of the running release. The steps
assume release `liftgate` in `liftgate-system` with `postgres.backup.enabled=true`.

1. Record the time and the schema version, and take a backup:

   ```sh
   date -u +%Y-%m-%dT%H:%M:%SZ
   PRIMARY=$(kubectl -n liftgate-system get cluster liftgate-postgres -o jsonpath='{.status.currentPrimary}')
   kubectl -n liftgate-system exec "$PRIMARY" -c postgres -- psql -U postgres -d liftgate -Atc \
     'select max(installed_rank) from flyway_schema_history where success'
   BACKUP=$(kubectl -n liftgate-system create -o name -f - <<EOF
   apiVersion: postgresql.cnpg.io/v1
   kind: Backup
   metadata:
     generateName: pre-upgrade-
   spec:
     cluster:
       name: liftgate-postgres
     method: plugin
     pluginConfiguration:
       name: barman-cloud.cloudnative-pg.io
   EOF
   )
   kubectl -n liftgate-system wait "$BACKUP" --for=jsonpath='{.status.phase}'=completed --timeout=60m
   ```

2. Snapshot the disk that holds the node's volumes, for a VM its data drive.

3. Upgrade and wait for every workload to become ready:

   ```sh
   helm upgrade liftgate charts/liftgate -n liftgate-system -f liftgate-values.yaml --wait --timeout 15m
   ```

4. Check that `<publicUrl>/api/v1/auth/providers` answers 200 and that a push to a test
   application is built and served.

## Rolling back

Run the query from step 1 again. If `max(installed_rank)` did not change, no migration ran:

```sh
helm rollback liftgate -n liftgate-system --wait
```

If it grew, do not stop at `helm rollback`: after it, restore the database to the time recorded
in step 1 by following [Restore](cnpg/README.md#restore) with the previous release's chart.
