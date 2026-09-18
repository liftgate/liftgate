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

Backups are not configured here; add `spec.backup` with the Barman Cloud plugin and an object
store before running production data. The Liftgate chart does not touch that section.
