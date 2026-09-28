#!/usr/bin/env sh
set -eu

kubectl -n liftgate-system port-forward service/prometheus 9090:9090 > /dev/null &
trap "kill $!" EXIT
api=http://localhost:9090/api/v1
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 --output /dev/null http://localhost:9090/-/ready

eventually() {
  for attempt in $(seq 60); do
    found="$(curl --fail --silent --get --data-urlencode "query=$1" "$api/query" | jq '.data.result | length')" || found=0
    test "$found" -gt 0 && { echo "found $1"; return; }
    sleep 5
  done
  echo "FAIL: Prometheus has nothing for $1"
  exit 1
}

for target in \
  'app_kubernetes_io_component="control-plane"' \
  'app_kubernetes_io_component="nats-exporter"' \
  'job="cnpg"' \
  'service="prometheus-kube-state-metrics"' \
  'service="prometheus-prometheus-node-exporter"'; do
  eventually "min(up{$target}) == 1"
done

for series in \
  liftgate_outbox_oldest_pending_seconds \
  'liftgate_builds{status="queued"}' \
  'max_over_time(liftgate_messages_total{outcome="acked"}[1h])' \
  'max_over_time(liftgate_release_duration_seconds_count{status="running"}[1h])' \
  'ktor_http_server_requests_seconds_count{route=~"/(healthz|readyz|metrics)"}' \
  nats_server_max_storage \
  nats_server_total_message_bytes \
  cnpg_collector_up \
  cnpg_pg_stat_archiver_last_archived_time \
  barman_cloud_cloudnative_pg_io_last_available_backup_timestamp \
  'kube_pod_labels{label_liftgate_dev_org_id!=""}' \
  'kube_persistentvolumeclaim_labels{label_cnpg_io_cluster!=""}' \
  'kube_pod_container_resource_requests{resource="cpu"}' \
  'kube_pod_status_phase{phase="Running"}' \
  'kube_node_status_allocatable{resource="memory"}' \
  'container_cpu_usage_seconds_total{namespace=~"env-.+", container="", pod!=""}' \
  certmanager_certificate_expiration_timestamp_seconds \
  node_filesystem_avail_bytes \
  node_memory_MemAvailable_bytes; do
  eventually "$series"
done

curl --fail --silent --show-error "$api/rules" | jq -e '[.data.groups[] | select(.name == "liftgate") | .rules[]] | length > 0 and all(.health == "ok")'
curl --fail --silent --show-error "$api/alertmanagers" | jq -e '.data.activeAlertmanagers | length > 0'
