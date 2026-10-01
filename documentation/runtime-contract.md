# Runtime contract

This page lists what Liftgate gives a container and what it expects back. The control plane renders
every workload in [`Resources.kt`](../control-plane/src/main/kotlin/dev/liftgate/k8s/Resources.kt), and
[`ResourcesTest`](../control-plane/src/test/kotlin/dev/liftgate/k8s/ResourcesTest.kt) checks the
statements below about ports and probes.

## Service kinds

| Kind | Runs as | Port | Reachable at |
|---|---|---|---|
| `web` | A Deployment with `replicas` pods | The port setting, 8080 when empty | Its platform hostname, its custom domains and its private address |
| `static` | The same as `web` | The same as `web` | The same as `web` |
| `worker` | A Deployment with `replicas` pods | None unless you set one | Its private address, when it has a port |
| `cron` | A CronJob on the cron schedule | None | Nowhere |

## Platform hostname

A `web` or `static` service gets `<service>-<project>-<org>.<deploy domain>`. Outside `production` the
environment's slug follows the service's. A service named like its project leaves its own name out, so
service `shop` in project `shop` of org `acme` gets `shop-acme.<deploy domain>`. A name longer than 63
characters, or one another service already holds, gets a shorter form ending in a suffix derived from the
service. Once issued, a platform hostname does not change.

## PORT

A service that has a port gets the environment variable `PORT` with that port: every `web` and `static`
service, 8080 unless you set another, and a `worker` whose port is set. Listen on that port on all
addresses, `0.0.0.0` rather than `127.0.0.1`, because the probes and the gateway connect to the pod's own
address. Liftgate sets `PORT` on the container itself, and Kubernetes gives that precedence over a
variable of the same name, so a `PORT` variable you add has no effect on a service with a port. A
service without a port gets no `PORT`.

## Health checks

Liftgate probes a service only when it has a port.

Without a health check path, a readiness probe opens a TCP connection to the port every 2 seconds. After
3 failures in a row the pod stops receiving traffic until the port accepts connections again. Nothing
restarts a process that hangs while its port stays open.

With a health check path, such as `/healthz`, three probes send `GET` requests to that path on the port.
A response with a status from 200 to 399 passes.

| Probe | Every | Fails after | Then |
|---|---|---|---|
| Startup | 5 seconds | 60 failures, so the app has 5 minutes to start | Kubernetes restarts the container |
| Readiness | 2 seconds | 3 failures | The pod receives no traffic until it passes again |
| Liveness | 10 seconds | 6 failures | Kubernetes restarts the container |

Readiness and liveness start once the startup probe has passed. The path must start with `/` and have at
most 256 characters. Set it under Settings, Build and runtime.

## Rollouts and shutdown

A new release starts one new pod at a time next to the running ones. A new pod has to stay ready for 10
seconds before an old one is stopped, so the old release keeps serving until its replacement works.

When a pod stops, it keeps running for 5 more seconds, which gives the gateway time to stop sending it
requests, and then its process gets `SIGTERM`. Kubernetes kills it 30 seconds after the stop began, so exit within 25 seconds of
`SIGTERM`.

Liftgate marks a release failed as soon as a pod of it reaches `CrashLoopBackOff`, `ImagePullBackOff`,
`ErrImagePull` or `CreateContainerConfigError`, or restarts 3 times. The error on the Deployments tab
names the reason, the exit code and the container's last message. The previous release keeps serving,
because none of its pods stops before a new one is ready. When the failed rollout reaches its 10-minute
progress deadline, Liftgate puts the previous release back in place, if that release was deployed with
stored settings, which every deployment made since 0.2.0-alpha.3 is.

## Start and build commands

With an empty start command, the container runs the image's `ENTRYPOINT` and `CMD`. A start command
replaces both and runs as `/bin/sh -c "<start command>"`. An image without `/bin/sh`, such as a
distroless or `scratch` image, needs an empty start command.

A Railpack build also receives the start command as `RAILPACK_START_CMD`, so the image starts the same
way, and a build command replaces Railpack's build step as `RAILPACK_BUILD_CMD`. Both take precedence over
variables of the same name. With a start command, Railpack does not serve the app as a static site with
Caddy. A Dockerfile build ignores both, and the start command still replaces its image's command.

## Cron jobs

A cron service runs its container on the cron schedule, one run at a time: a run that is due while the
previous one still runs is skipped. A run that cannot start within 5 minutes of its time is skipped too.
Kubernetes retries a failing run up to 2 times. A run that takes longer than the plan's
`cronTimeoutSeconds`, one hour unless the operator changed it, is stopped and counted as failed.

## Environment

Every variable on the Environment variables tab reaches the container as an environment variable,
secret ones included. Liftgate adds only `PORT`. Pods get no Kubernetes service links and no service
account token. A variable change reaches running pods with the next deployment: Save and redeploy on the
tab, a push, or a deploy from the dashboard.

## User and file system

The container runs as user 1000 and group 1000, whatever `USER` the image sets. It cannot gain privileges
and has no Linux capabilities. On installations that use the chart's default runtime class, `gvisor`, it
runs in a gVisor sandbox.

In an image Liftgate builds with Railpack:

- User 1000 owns the working directory, `/app`, and everything in it except the files inside
  `node_modules` directories, which stay owned by root and read-only. The directories inside
  `node_modules` are writable, so files such as `node_modules/.cache` can be created.
- Files that a `deploy.inputs` entry of your own `railpack.json` copies from an image, from the
  repository, or with an include of `/` keep the owner they arrive with.
- `HOME` is `/home/liftgate`, owned by user 1000. The image has a user named `liftgate` for uid 1000,
  unless it already had a user with that uid.
- `/tmp` is writable.
- A `HOME` variable set on the service replaces `/home/liftgate`.

In an image Liftgate builds from a Dockerfile, Liftgate adds one last layer that gives user 1000 the final
stage's `WORKDIR` by the same rule, and sets `USER 1000:1000`. Files already owned by `1000:1000` are
skipped, so `COPY --chown=1000:1000` keeps that layer small. With `WORKDIR /` nothing changes. `HOME`,
`/tmp` and every other path stay as the image made them.

Everywhere else, the app can write only where the image lets user 1000 write. Images built by Liftgate
0.2.0 or older do not have this ownership; build the app again to get it.

The file system lasts as long as the container: a restart, a redeploy or a rollback starts from the image
again. Nothing you write survives, so keep state in a database or object store outside Liftgate.

## Resources

| Setting | Default | Effect |
|---|---|---|
| CPU | 500 millicores | The container is throttled above it. It reserves the same amount, or less when the plan's `cpuRequestRatio` is below 1 |
| Memory | 512 MB | A container that uses more is killed and restarted |
| Replicas | 1 | The number of pods; 0 stops the service |
| Disk | The plan's `ephemeralMb`, 2048 MiB unless the operator changed it | Writes to the container's file system count towards it, and a pod that goes over is evicted |

[Plans and limits](plans-and-limits.md) lists the limits an organization's plan puts on these settings.

## Network

Traffic into a pod comes only from pods in the same environment and from the namespace Liftgate itself
runs in, where the gateway is.

Traffic out of a pod may reach:

- other services in the same environment, on any port;
- the cluster DNS server, on port 53;
- the internet over TCP, on every port except the mail ports 25, 465, 587 and 2525;
- the internet over UDP, unless the plan turns UDP off, as the chart's `free` plan does.

It never reaches the private ranges `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10` and
`169.254.0.0/16`, other environments, the control plane, or public ranges the operator blocks. A plan with
an egress bandwidth limit caps the pod's outgoing traffic, for example to 20 Mbit/s on the chart's
`free` plan.

## Private services

A service with a port gets a private address, `<service>.<namespace>.svc.cluster.local`, shown under
Settings as Private address. Services in the same environment reach it on port 80 and on the service's
own port. Give a worker a port if other services need to reach it. Services in other environments and
the internet cannot reach it.

## Logs

Liftgate collects what the container writes to standard output and standard error. The Logs tab shows the
last 500 lines of each running pod and then follows them, with each line prefixed by the end of its pod's
name. A line longer than 16 KiB is split.
