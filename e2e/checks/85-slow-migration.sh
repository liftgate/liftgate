#!/usr/bin/env sh
set -eu

ns=liftgate-system
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

sql() {
  kubectl -n $ns exec liftgate-postgres-1 -c postgres -- psql --username postgres --dbname liftgate --tuples-only --no-align --command "$1"
}

tag() {
  helm upgrade liftgate charts/liftgate --namespace $ns --reuse-values --set controlPlane.tag="$1"
  kubectl -n $ns rollout status deployment/liftgate-control-plane --timeout=10m
}

container="$(docker create liftgate/control-plane:e2e)"
docker cp "$container:/opt/liftgate/lib" "$work/lib"
docker rm "$container" > /dev/null
mkdir -p "$work/image" "$work/db/migration"
jar="$(basename "$(ls "$work"/lib/liftgate-control-plane*.jar)")"
cp "$work/lib/$jar" "$work/image/"
cat > "$work/db/migration/V9999__e2e_slow_migration.sql" <<'SQL'
do $$
begin
    if current_setting('lock_timeout') <> '10s' then
        raise exception 'lock_timeout is %', current_setting('lock_timeout');
    end if;
    perform pg_sleep(240);
end
$$;
SQL
(cd "$work" && zip -q "image/$jar" db/migration/V9999__e2e_slow_migration.sql)
printf 'FROM liftgate/control-plane:e2e\nCOPY %s /opt/liftgate/lib/\n' "$jar" > "$work/image/Dockerfile"
docker build --quiet --tag liftgate/control-plane:e2e-slow "$work/image"
kind load docker-image --name liftgate liftgate/control-plane:e2e-slow

tag e2e-slow
test "$(sql "select success from flyway_schema_history where version = '9999'")" = t
restarts="$(kubectl -n $ns get pods -l app.kubernetes.io/component=control-plane \
  -o jsonpath='{range .items[*]}{.status.initContainerStatuses[*].restartCount} {.status.containerStatuses[*].restartCount} {end}')"
echo "restart counts: $restarts"
test -z "$(echo "$restarts" | tr ' ' '\n' | grep -v -x -e 0 -e '')"
tag e2e
