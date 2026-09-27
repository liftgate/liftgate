# Runbook

One section per alert in [`prometheus/rules.yaml`](prometheus/rules.yaml), plus the canary. Each
alert's `description` links here. The commands assume release `liftgate` in `liftgate-system`;
in the `ha` profile the control plane runs as `liftgate-api`, `liftgate-reconciler`,
`liftgate-builder` and `liftgate-meter` instead of `liftgate-control-plane`.

Page yourself means: stop what you are doing and fix it now. Wait means: fix it the same day.

## NodeFilesystemLow

A node filesystem has less than 15% free. At 10% the kubelet starts evicting pods
(`eviction-hard` in [`k3s/install.md`](k3s/install.md)), and PostgreSQL, NATS and the registry
stop writing when their disk is full.

```sh
kubectl get nodes -o wide
ssh <node> df -h
ssh <node> sudo du -xh --max-depth=3 /var/lib/rancher | sort -h | tail -20
```

Page yourself below 10% or when it dropped more than 5 points in an hour; wait otherwise. Unused
images go with `sudo k3s crictl rmi --prune`; registry storage is bounded by the janitor in
[`registry/README.md`](registry/README.md).

## NodeMemoryLow

A node has less than 10% of its memory available. The kubelet evicts pods below 500Mi, and the
kernel OOM killer may kill any container, PostgreSQL included.

```sh
kubectl top nodes
kubectl top pods --all-namespaces --sort-by=memory | head -20
kubectl get events --all-namespaces --field-selector reason=Evicted --sort-by=.lastTimestamp | tail
```

Page yourself if platform pods (`liftgate-system`) are being evicted or OOM killed; wait if one
tenant namespace is growing, and check its plan limits.

## VolumeFilling

A PersistentVolumeClaim is more than 80% full. The usual ones are PostgreSQL (WAL piles up when
archiving fails), NATS JetStream, Prometheus and Alertmanager. Volumes of the k3s `local-path`
class report no usage; they fill the node disk and raise [NodeFilesystemLow](#nodefilesystemlow).

```sh
kubectl get pvc --all-namespaces
kubectl -n <namespace> describe pvc <pvc> | grep 'Used By'
kubectl -n <namespace> exec <pod> -- df -h
```

For PostgreSQL check [WalArchivingFailing](#walarchivingfailing) first. Page yourself above 90%;
wait otherwise.

## NodeRequestsHigh

Pods on a node request more than 85% of its allocatable CPU or memory, so new pods, rollouts
and builds will soon stay `Pending`.

```sh
kubectl describe node <node> | sed -n '/Allocated resources/,/Events/p'
kubectl get pods --all-namespaces --field-selector status.phase=Pending
kubectl get pods --all-namespaces --field-selector spec.nodeName=<node> -o wide
```

Wait, unless pods are already `Pending`; then page yourself and add capacity or lower a plan's
limits.

## PodCrashLooping

A platform container (any namespace except tenant `env-*` namespaces) has been restarting in
`CrashLoopBackOff` for 10 minutes.

```sh
kubectl -n <namespace> describe pod <pod>
kubectl -n <namespace> logs <pod> -c <container> --previous --tail=200
kubectl -n <namespace> get events --sort-by=.lastTimestamp | tail -20
```

Page yourself for anything in `liftgate-system`, `kube-system` or `cert-manager`; wait for the
rest.

## ControlPlaneDown

No control plane pod of a role answers on `/metrics`, or none is scraped at all. The role's work
stops while it lasts: the API, builds, releases or metering.

```sh
kubectl -n liftgate-system get pods -l app.kubernetes.io/component=control-plane -o wide
kubectl -n liftgate-system logs deployment/liftgate-control-plane --tail=200
kubectl -n liftgate-system describe pods -l app.kubernetes.io/component=control-plane | grep -A8 'Last State'
```

Page yourself. A pod that exits at startup usually cannot reach PostgreSQL or NATS; check
[PostgresDown](#postgresdown) and the NATS pods.

## ApiErrors

More than 5% of API requests (health probes excluded) return a 5xx for 10 minutes.

```sh
kubectl -n liftgate-system logs -l app.kubernetes.io/component=control-plane --tail=1000 | grep 'unhandled error'
kubectl get --raw /api/v1/namespaces/liftgate-system/services/liftgate-control-plane:http/proxy/readyz
kubectl -n liftgate-system get cluster liftgate-postgres
```

Page yourself.

## PostgresDown

The namespace has CloudNativePG volumes but no PostgreSQL instance reports as running, so the
API and every worker are failing.

```sh
kubectl -n liftgate-system get cluster liftgate-postgres
kubectl -n liftgate-system get pods -l cnpg.io/cluster=liftgate-postgres -o wide
kubectl -n liftgate-system logs liftgate-postgres-1 -c postgres --tail=100
```

Page yourself. Check the cluster's annotations for `cnpg.io/hibernation` or `cnpg.io/fencedInstances`
before anything else. If the data is gone, follow [Restore](cnpg/README.md#restore).

## WalArchivingFailing

PostgreSQL has failed to archive WAL for 5 minutes. Point-in-time recovery stops at the last
archived segment, and WAL accumulates on the database volume until it fills.

```sh
kubectl -n liftgate-system get cluster liftgate-postgres -o jsonpath='{.status.conditions[?(@.type=="ContinuousArchiving")]}'
kubectl -n liftgate-system logs liftgate-postgres-1 -c plugin-barman-cloud --tail=100
kubectl -n liftgate-system get objectstore liftgate-postgres -o yaml
```

Page yourself if it lasts an hour: that is an hour of writes you cannot restore. Usual causes are
the object store being unreachable or its credentials Secret having changed.

## BackupTooOld

The newest completed base backup is more than 26 hours old, so the daily `ScheduledBackup` has
missed a run.

```sh
kubectl -n liftgate-system get backups --sort-by=.metadata.creationTimestamp | tail -5
kubectl -n liftgate-system describe scheduledbackup liftgate-postgres
kubectl -n liftgate-system describe backup <newest failed backup>
```

Wait, but take an on-demand backup (step 1 of [`UPGRADE.md`](UPGRADE.md)) once the cause is
fixed. Page yourself if [WalArchivingFailing](#walarchivingfailing) fires as well.

## JetStreamStorageHigh

JetStream uses more than 80% of its file store. When it is full, publishing fails and the
outbox stops draining ([OutboxStuck](#outboxstuck)).

```sh
kubectl -n liftgate-system get pvc | grep nats
helm upgrade liftgate oci://ghcr.io/liftgate/charts/liftgate --version <running version> -n liftgate-system --reuse-values --set nats.natsBox.enabled=true
kubectl -n liftgate-system exec deployment/liftgate-nats-box -- nats stream report
```

Wait. Messages expire after 7 days; grow `nats.config.jetstream.fileStore.pvc.size` if the
stream is legitimately busy. Turn nats-box off again afterwards.

## OutboxStuck

The oldest event in the outbox has waited more than 2 minutes for NATS, or no control plane pod
is relaying events at all. Builds, releases and teardowns wait until it clears; the events stay
in PostgreSQL and are published once NATS is back.

```sh
kubectl -n liftgate-system get pods -l app.kubernetes.io/component=nats
kubectl -n liftgate-system get lease liftgate-outbox-relay -o jsonpath='{.spec.holderIdentity}'
kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql -U postgres -d liftgate -Atc 'select count(*), min(created_at) from outbox where published_at is null'
```

Page yourself. Look for `outbox relay failed` in the lease holder's log.

## MessageRedeliveries

A consumer has failed more than 10 deliveries in 15 minutes, for 15 minutes. Failed messages are
retried with a growing delay up to 10 minutes, forever, so a broken dependency or a message that
always fails shows up here.

```sh
kubectl -n liftgate-system logs -l app.kubernetes.io/component=control-plane --tail=2000 | grep 'handler failed for'
kubectl -n liftgate-system get pods -o wide
kubectl get --raw '/readyz?verbose' | tail -3
```

The consumer label names the work: `builder-build-requested`, `reconciler-release-requested`,
`reconciler-teardown-requested` and so on. Wait, unless deployments are failing for users.

## BuildQueueBacklog

More than 20 builds have been queued for 15 minutes: builders are down, the build node is full,
or plan limits keep builds waiting.

```sh
kubectl -n liftgate-build get jobs,pods -o wide
kubectl -n liftgate-system logs deployment/liftgate-control-plane --tail=200 | grep -i build
kubectl -n liftgate-system exec liftgate-postgres-1 -c postgres -- psql -U postgres -d liftgate -Atc 'select status, count(*) from builds group by status'
```

Wait, unless no build has started in 30 minutes; then page yourself.

## CertificateExpiring

A cert-manager Certificate expires in less than 14 days. cert-manager renews a 90-day
certificate 30 days before it expires, so renewal has been failing for over two weeks.

```sh
kubectl get certificates --all-namespaces
kubectl -n <namespace> describe certificate <name>
kubectl get orders,challenges --all-namespaces
```

Wait until 7 days are left, then page yourself. Only Certificates that cert-manager manages are
covered; see [Certificates](prometheus/README.md#certificates).

## OrgCpuSaturated

An organization's pods have used 90% or more of their CPU limits for 2 hours. This is the beta's
abuse signal: crypto mining and runaway loops look like this.

```sh
kubectl get namespaces -l liftgate.dev/org-id=<label_liftgate_dev_org_id>
kubectl top pods -n <namespace>
kubectl -n <namespace> logs <pod> --tail=50
```

Wait, and look at what the app does. If it breaks the acceptable use policy, suspend the
organization with the admin `suspend` command in the
[chart README](../charts/liftgate/README.md#sign-up-and-accounts).

## Canary

[`canary.yml`](../.github/workflows/canary.yml) runs on GitHub-hosted runners. Every 10 minutes
it requests `https://liftgate.dev/api/v1/auth/providers` and the canary app. Once a day it pushes
a commit to the canary repository and expects the new content to be served within 10 minutes.
A failure posts the run's link to the alert receiver.

```sh
gh run list --workflow canary.yml --limit 5
gh run view <run id> --log-failed
curl -sS -o /dev/null -w '%{http_code}\n' https://liftgate.dev/api/v1/auth/providers
```

A failed probe while no other alert fires means the path from the internet is broken
(Cloudflare, the host proxy, the gateway): page yourself. A failed daily deploy means webhooks,
builds or releases are broken: check [BuildQueueBacklog](#buildqueuebacklog) and the builder
logs, then page yourself.
