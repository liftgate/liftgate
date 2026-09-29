# Upgrading Liftgate

Upgrade from a checkout of the new tag, with the values file of the running release. The steps
assume release `liftgate` in `liftgate-system` with `postgres.backup.enabled=true`.

1. Record the schema version, take a backup and, once it has completed, record the time:

   ```sh
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
   date -u +%Y-%m-%dT%H:%M:%SZ
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

## Upgrading from v0.2.0-alpha.2 or older

v0.2.0-alpha.3 moved build images from `<org>/<project>-<service>` to
`<org>/<project>/<environment>/<service>`. For that release only, a build that was already running
when it was installed could still push to the old name, and the registry janitor kept the image
it pushed. Later releases do neither, so upgrading straight from v0.2.0-alpha.2 or older skips that
transition: a build running during the upgrade may fail to push with `registryAuth: token`, or lose
its image to the janitor before it deploys. Retry such builds after the upgrade, or upgrade to
v0.2.0-alpha.3 first.
