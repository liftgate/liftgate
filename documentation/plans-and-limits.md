# Plans and limits

Every organization is on a plan, and the plan sets how much the organization may create and run. The
operator of the installation defines the plans. The Usage card on the organization's projects page shows
the plan's name and what the organization uses against each limit, and so does
`GET /api/v1/orgs/<org>/usage`.

## Plan limits

A limit the plan leaves out is unlimited.

| Limit | Counts |
|---|---|
| `ownedOrgs` | Organizations one user owns. It comes from the installation's default plan |
| `projects` | Projects in the organization |
| `environmentsPerProject` | Environments in one project |
| `services` | Services in the organization, across every environment |
| `replicas` | Pods across the organization's services, where a cron service counts as one |
| `cpuMillis` | The sum of pods × CPU over the organization's services |
| `memoryMb` | The sum of pods × memory over the organization's services |
| `customDomains` | Custom domains in the organization |
| `previewEnvironments` | Pull request previews in the organization. They do not count toward `environmentsPerProject` |
| `concurrentBuilds` | Builds running at once. Further builds wait in the queue |
| `buildsPerHour` | Builds started in the last hour or waiting in the queue. Further builds fail |

A change that would go past a limit is refused with `409 plan_limit` and a message that names the limit,
and nothing is saved. A change that does not add to a limit the organization is already over still
passes, so an organization moved to a smaller plan can keep editing and shrinking what it has.

The plan also shapes how services run:

| Setting | Effect | Default |
|---|---|---|
| `cpuRequestRatio` | The CPU a container reserves, as a share of its CPU limit | 1 |
| `ephemeralMb` | Disk for each container's file system, in MiB | 2048 |
| `egressBandwidth` | The outgoing bandwidth of each pod, such as `20M` for 20 Mbit/s | Unlimited |
| `udp` | Whether pods may send UDP to the internet. DNS through the cluster works either way | Allowed |
| `cronTimeoutSeconds` | How long one cron run may take before it is stopped | 3600 |

Each environment also gets a Kubernetes ResourceQuota of twice the plan's `replicas`, `cpuMillis` and
`memoryMb`, so a rolling update has room to start new pods before the old ones stop.

## The chart's plans

The Helm chart ships two plans. `unlimited` is the default plan of a self-hosted installation and sets no
limits. `free` is meant for shared installations:

| Limit | `free` |
|---|---|
| `ownedOrgs` | 1 |
| `projects` | 3 |
| `environmentsPerProject` | 2 |
| `services` | 6 |
| `replicas` | 6 |
| `cpuMillis` | 1000 |
| `memoryMb` | 1024 |
| `customDomains` | 2 |
| `previewEnvironments` | 1 |
| `concurrentBuilds` | 1 |
| `buildsPerHour` | 10 |
| `cpuRequestRatio` | 0.25 |
| `ephemeralMb` | 1024 |
| `egressBandwidth` | `20M` |
| `udp` | `false` |

An installation may define other plans or change these values, so the Usage card is the source of truth
for your organization.

## Limits on every plan

These apply whatever the plan says.

| What | Limit |
|---|---|
| Replicas of one service | 0 to 10 |
| CPU of one service | 1 to 4000 millicores |
| Memory of one service | 1 to 8192 MB |
| Health check path | Starts with `/`, at most 256 characters |
| Watch paths | At most 20, each at most 100 characters |
| One build | 30 minutes, 2 CPUs, 4 GiB of memory and 20 GiB of disk |
| Build logs | Kept for 7 days, at most 50,000 lines per build |
| Build images | The newest 10 successful builds of each service stay, together with every image a deployment still uses. A daily job deletes the rest, and rolling back to a build whose image is gone answers `409 image_pruned`, so deploy its commit again instead |
| Mail | Pods and builds cannot connect to ports 25, 465, 587 and 2525 |
| API writes | 120 a minute per user |
| Deploys and redeploys | 10 a minute per service |
| Sign-in requests | 60 a minute per user or client address |
| Log streams | 20 open at once per user |
| Invitations | 10 a minute per organization |
| Request bodies | 1 MiB, or 25 MiB for GitHub webhooks |

A request over a rate limit gets `429 rate_limited` with a `Retry-After` header. A body over its cap gets
`413 payload_too_large`.

## For operators

Plans are set in the chart's `plans` map, and `defaultPlan` names the plan of every organization that has
not been given another one. Move an organization with the `admin plan` command:

```sh
kubectl -n liftgate-system exec deploy/liftgate-control-plane -- /opt/liftgate/bin/liftgate-control-plane admin plan acme free
```

`admin plan acme default` puts it back on `defaultPlan`. The installation-wide cap on custom domains is
`customDomains.max`. The chart README has the details under
[Plans](../charts/liftgate/README.md#plans) and [Sign-up and accounts](../charts/liftgate/README.md#sign-up-and-accounts).
