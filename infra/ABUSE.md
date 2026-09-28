# Abuse reports

How the operator of a Liftgate installation handles a report that an app on it is used for
abuse: phishing, malware, spam, cryptocurrency mining, an open proxy, scanning or attacks on
other hosts, or illegal content. Reports arrive at the installation's abuse mailbox, the
address its Acceptable Use Policy names.

The commands assume release `liftgate` in `liftgate-system` with the chart's managed
PostgreSQL. With the `ha` profile, the admin commands run in `deploy/liftgate-api` instead of
`deploy/liftgate-control-plane`.

```sh
admin() { kubectl -n liftgate-system exec deploy/liftgate-control-plane -- /opt/liftgate/bin/liftgate-control-plane admin "$@"; }
PRIMARY=$(kubectl -n liftgate-system get cluster liftgate-postgres -o jsonpath='{.status.currentPrimary}')
sql() { kubectl -n liftgate-system exec -i "$PRIMARY" -c postgres -- psql -U postgres -d liftgate -v ON_ERROR_STOP=1 "$@"; }
```

## 1. Acknowledge within 24 hours

Answer every report within 24 hours, including incomplete ones. Give it a reference such as
`2026-09-28-1` and use it in every reply and every note below. Ask for what is missing: the URL
or IP address, when it was seen with a time zone, and for network abuse the destination, ports
and log lines.

## 2. Find the organization

For a hostname, platform or custom:

```sh
sql -v host=login.example.liftgate.app <<'SQL'
select o.slug as org, o.id as org_id, o.suspended_at, p.repo_full_name, p.imported_by_login, e.namespace, s.slug as service
from domains d
join services s on s.id = d.service_id
join environments e on e.id = s.environment_id
join projects p on p.id = e.project_id
join organizations o on o.id = p.org_id
where d.hostname = :'host' and d.verified_at is not null;
SQL
```

For scanning, proxying or floods reported by IP address, the address does not name an app:
Cilium masquerades traffic that leaves the cluster to the node's address, which every app on the
node shares. With the Cilium that `infra/install.sh` installs, Hubble keeps the last 4095 flows
on each node. List the ones to the reported destination while the traffic goes on; each names
the sending pod as `<namespace>/<pod>`:

```sh
for pod in $(kubectl -n kube-system get pods -l k8s-app=cilium -o name); do
  kubectl -n kube-system exec "$pod" -c cilium-agent -- hubble observe --to-ip <destination> --last 1000
done
```

For mining, list the busiest tenant pods. This only names candidates: CPU does not show what a
pod runs, so confirm it in step 3 before suspending anyone.

```sh
kubectl top pods -A -l liftgate.dev/managed=true --sort-by=cpu | head
```

Either way, read the organization off the namespace:

```sh
kubectl get namespace <namespace> -L liftgate.dev/org
```

The owners, who get the notice in step 7:

```sh
sql -v org=acme <<'SQL'
select u.id, u.login, u.email, u.status
from memberships m join users u on u.id = m.user_id join organizations o on o.id = m.org_id
where o.slug = :'org' and m.role = 'owner';
SQL
```

## 3. Verify

Check the report yourself before acting on it, and save what you see in the report's mail
thread:

- Web content: `curl -sS -D - -o page.html https://<host>/`, with the time in UTC. Do not enter
  credentials, run downloads or open attachments.
- Scanning, proxying or floods: Hubble lines from the pod to the reported destination and ports.
  A tool in the repository without matching traffic is not enough. If the traffic has stopped,
  ask the reporter to write again when it recurs.
- Mining: find the miner in `kubectl -n <namespace> logs deploy/<service> --tail=200` or in the
  repository at the running commit. High CPU alone is not enough.
- Illegal content, such as child sexual abuse material: do not download, copy or keep it.
  Record only the URL and the time, suspend at once, and report it to the authority your
  jurisdiction requires.

If the report does not hold up, tell the reporter what you checked and close it.

## 4. Keep the evidence in audit_log

Write a note before suspending. It stores your text with a snapshot of the owners, repositories,
hostnames and running commits. Its `org_id` stays empty, so deleting the organization later does
not delete it, and Liftgate never prunes `audit_log`.

```sh
sql -v org=acme -v note='2026-09-28-1: https://login.example.liftgate.app served a Microsoft 365 sign-in form at 10:03 UTC' <<'SQL'
insert into audit_log (action, target_type, target_id, details)
select 'abuse.note', 'org', o.id::text, jsonb_build_object(
    'note', :'note',
    'owners', (select jsonb_agg(jsonb_build_object('id', u.id, 'login', u.login, 'email', u.email))
        from memberships m join users u on u.id = m.user_id where m.org_id = o.id and m.role = 'owner'),
    'services', (select jsonb_agg(jsonb_build_object(
            'repository', p.repo_full_name, 'importedBy', p.imported_by_login, 'namespace', e.namespace, 'service', s.slug,
            'hostnames', (select jsonb_agg(d.hostname) from domains d where d.service_id = s.id),
            'commit', (select b.commit_sha from deployments dp join builds b on b.id = dp.build_id
                where dp.service_id = s.id and dp.status = 'running' order by dp.created_at desc limit 1)))
        from projects p join environments e on e.project_id = p.id join services s on s.environment_id = e.id
        where p.org_id = o.id))
from organizations o where o.slug = :'org'
returning id, created_at;
SQL
```

Everything recorded about an organization, including the rows the admin commands write:

```sh
sql -v id=<org_id> <<'SQL'
select id, created_at, action, details from audit_log where target_id = :'id' order by id;
SQL
```

## 5. Suspend

Suspend as soon as the report checks out. When the owners can fix the problem without harm
continuing in the meantime, such as a copyright complaint or an app over fair use, write to
them first and suspend only if it is not fixed.

```sh
admin suspend acme phishing
```

The rest of the line is the reason. Members see it in the dashboard as "<name> is suspended for
<reason>", so use a short category from the Acceptable Use Policy, such as `phishing` or
`cryptocurrency mining`, and nothing about the reporter. In one transaction the command sets
the suspension, cancels the organization's queued builds and writes an `org.suspend` row with
the reason to `audit_log`. Within a minute the reconciler scales every Deployment of the
organization to 0, suspends its CronJobs, deletes their running Jobs, and deletes its Services
and HTTPRoutes. Later releases and the 5-minute resync keep it stopped, and the builder fails
its new builds. A build that is already running keeps running until it ends or hits the build
job's 30-minute deadline. Members can still sign in and read, every change to the organization
answers `403 org_suspended`, and owners can delete neither the organization nor their account.

Check that nothing is served:

```sh
kubectl get deployments,cronjobs,services,httproutes -A -l liftgate.dev/org=acme
```

Suspending an organization signs nobody out, and its members can still create organizations up
to the default plan's `ownedOrgs`. When the person is the problem, suspend the account too:

```sh
admin suspend-user <user> phishing
```

`<user>` is a user id, login or email; use the id when a login matches several users. The
account's sessions and the API tokens it created are deleted, sign-in is refused with
`account_suspended`, and a `user.suspend` row with the reason goes to `audit_log`.

## 6. Tell the reporter

Reply with the reference and the outcome: the app is suspended, the owners were asked to fix
it, or nothing in breach was found and what you checked. Do not share the organization's
members, email addresses or any other account data.

## 7. Tell the organization

Liftgate sends no suspension email. Write to the owners from the abuse mailbox with the
reference, the rule in the Acceptable Use Policy, what you observed, that their apps are
stopped and their data kept, and how to reply. Leave out who reported it.

## 8. Lift a suspension

Write a note with the reason, as in step 4, then:

```sh
admin unsuspend acme
admin unsuspend-user <user>
```

`unsuspend` writes an `org.unsuspend` row and re-applies every service: Deployments get their
configured replicas back, CronJobs resume, and services that serve HTTP on a hostname get their
Service and HTTPRoute again. Check with the `kubectl get` above. Builds cancelled by the
suspension are not retried, so the owners push or redeploy. `unsuspend-user` makes the account
active again; the sessions and tokens removed by the suspension stay removed.
