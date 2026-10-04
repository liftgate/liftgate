# Getting started

This page takes you from signing in to an app with an HTTPS address, then covers what you do with a
service day to day. It works the same on [Liftgate Cloud](https://liftgate.dev) and on a self-hosted
installation; only the addresses differ. To run your own installation first, follow
[Self-hosting](self-hosting.md).

## Before you start

You need a GitHub account and a repository you can push to. Liftgate builds it with its Dockerfile when
there is one, and with [Railpack](https://railpack.com) otherwise. Your app has to listen on the port in
the `PORT` environment variable, 8080 unless you set another; the [runtime contract](runtime-contract.md)
lists everything else a container can rely on.

To try Liftgate with a known-good app, create a repository that holds these two files. It has no
Dockerfile, so Railpack detects Node, runs `npm install` and starts the server with `npm run start`.

`package.json`:

```json
{
  "scripts": {
    "start": "node server.js"
  }
}
```

`server.js`:

```js
require("node:http")
  .createServer((request, response) => response.end("hello from liftgate\n"))
  .listen(process.env.PORT);
```

For a Dockerfile build instead, fork [liftgate/hello](https://github.com/liftgate/hello), a small Node
server built from its Dockerfile.

## 1. Sign in

Open the dashboard, `https://liftgate.dev` for Liftgate Cloud or your installation's address, and choose
Continue with GitHub. Signing in with another method works too, but importing a repository needs a
GitHub connection, which the dashboard asks for when you import your first repository.

Installations that approve new accounts by hand show "Your account is waiting for approval" until an
operator approves yours. Sign in again once you are told it has been approved.

## 2. Create an organization

Projects and members belong to an organization. Its name is filled in from your GitHub profile, and the
line below it shows the addresses your apps will get. Choose Edit to change the URL name; you cannot
change it later. Choose Continue. You are the organization's owner.

## 3. Import a repository

The import screen lists the repositories where Liftgate's GitHub App is installed and your GitHub account
can push, most recently pushed first. If the list is empty, choose Install the GitHub App. GitHub asks
which account and which repositories the App may read, then sends you back to the import screen. If a
repository is missing, choose Install the GitHub App on another account; the list refreshes when you
come back to the tab.

Choose Import next to the repository.

## 4. Configure and deploy

Liftgate reads the repository's file list and manifests such as `package.json`, `requirements.txt`,
`go.mod` or a `Dockerfile`, and shows what it found:

- Project name: prefilled with the repository name, with the address the app will get. Edit URL changes
  the project's URL name.
- The app: its framework, how it runs, and the build and start commands it is expected to use. Edit opens
  its settings: service name, how it runs (web service, static site, worker or cron job), root directory,
  build and start commands, port, health check path, watch paths and resources. The defaults suit most
  apps.
- A repository with several apps, such as a pnpm workspace, lists each one with a checkbox. The apps that
  fit your plan are checked.
- Variables named in `.env.example`, `.env.sample` or `app.json`, each with an empty value. Fill in the
  ones you need, or choose Paste .env or Import file to fill them from a `.env` file, which is read in
  your browser. Empty variables are skipped. Liftgate reads the example values in memory only to mark a
  variable as required or as a likely secret, then discards them; they are never stored, cached, returned
  or logged. It never reads a committed `.env` file.

What Liftgate shows is its best reading of the repository; every field can be changed, and Railpack still
detects the stack during the build.

Choose Deploy. Liftgate creates the project with one environment, `production`, which builds the
repository's default branch, adds the service with its variables, starts a build of the latest commit
and opens the service's Deployments tab with the build log streaming. With several apps it opens the
project instead.

## 5. Open the app

When the build succeeds, Liftgate releases it. The Deployments tab moves from `pending` through
`releasing` to `running`, and the service header shows Visit. The address is

```
https://<service>-<project>-<org>.<deploy domain>
```

for example `https://web-hello-acme.liftgate.app` on Liftgate Cloud. Services in an environment other
than `production` add the environment's slug after the service's, and a service named like its project
leaves its own name out, as in `https://shop-acme.liftgate.app`. A name longer than 63 characters, or
one another service already holds, gets a shorter form ending in a suffix derived from the service.

From now on every push to the branch builds and deploys the service. If the release fails, the error on
the Deployments tab says why, and the previous release keeps serving.

## Deploys

- A push to the environment's branch builds every service of that environment, unless watch paths say
  otherwise.
- Watch paths, under the service's Settings, Build, limit which pushes build a service: one glob per
  line, relative to the repository root, where `*` stays inside one directory and `**` crosses
  directories. A service without watch paths builds on changes under its root directory. A push that
  creates the branch, a force push and a very large push build every service.
- Deploy in the service header builds the branch's latest commit. While a deployment runs, the button
  is Redeploy, which releases the running build again with the current settings and variables, without
  building; Deploy latest commit is then in its menu. The menu also deploys a branch or a full
  40-character commit SHA you name.
- Liftgate posts a commit status to GitHub for every build, named `liftgate/<service>` in `production`
  and `liftgate/<environment>/<service>` elsewhere.

## Environments

An environment tracks one branch and holds its own services, variables and addresses. New environment
in the project's Settings adds one with its branch, and Delete removes one with its services; the last
production environment stays. Add service on the project's Overview reads the
repository again and lists the apps that are not deployed in that environment yet. Services in different
environments cannot reach each other over the network.

## Variables

The Environment variables tab holds the service's variables. Tick Secret for a value nobody should read
back: the dashboard and the API never show it again, and members of the organization see only that it
is set. Paste .env and Import file add many variables at once, and Check repository lists the names in
the repository's example env files that the service does not have yet. Save keeps the change for the
next deployment. While the service runs, Save then offers Redeploy, which applies the change at once
without building, or Rebuild and deploy when a public build-time variable such as `NEXT_PUBLIC_` changed.
Rebuild and deploy builds the running commit again; while another build is in progress, it asks you to
try again once that build finishes.

Builds see the variables too:

- A Dockerfile build gets every variable that is not secret as a build argument. Declare it with
  `ARG NAME` in the Dockerfile to use it.
- Every variable, secret or not, is also a BuildKit secret. Read it in a step with
  `RUN --mount=type=secret,id=NAME`, which puts the value in `/run/secrets/NAME` for that step only, so
  it stays out of the image's history. Secret variables never become build arguments.
- A Railpack build sees every variable.

A value the build bakes into the image, such as a `NEXT_PUBLIC_` variable of a Next.js app, changes only
with a new build. Push, or use Rebuild and deploy or Deploy; Redeploy reuses the old image.

## Health checks

Set a health check path under the service's Settings, Runtime, and Liftgate checks it over HTTP before a
new release takes traffic and while it runs. Without one, it only checks that the port accepts
connections. The [runtime contract](runtime-contract.md#health-checks) has the timings.

## Rollback

The Deployments tab lists every deployment of the service, and every build that was not released. Roll
back to this, on a deployment that was
replaced, releases that build again with the settings and variables it ran with. Deployments made before
0.2.0-alpha.3 stored no settings, so a rollback to one of them uses the current settings. Rollback is not
offered once the build's image has been deleted; [Plans and limits](plans-and-limits.md) says which
images stay.

Every build has its own image, so a rollback or a redeploy runs exactly the image that build produced,
even after the same commit was built again. Builds made by Liftgate 0.3.0-alpha.3 or older are released
from their commit's tag instead, so they run the image of the last build of that commit those versions
made.

## Logs

The Logs tab follows the output of the service's running pods, starting with the last 500 lines of each.
Logs on a row of the Deployments tab shows that build's log, live while it runs and replayed afterwards
for 7 days. Both have Copy and Download.

To read what a container printed before it crashed, open the WebSocket the Logs tab uses,
`/api/v1/logs/services/<service id>`, with `?previous=true` and an API token in the `Authorization`
header.

## Metrics

The Metrics tab charts the service's CPU, memory and network over the last hour, 6 hours, 24 hours or 7
days, and counts restarts of its current pods. Charts appear a few minutes after a deployment starts
running. Self-hosted installations need the Prometheus that [`infra/install.sh`](../infra/install.sh)
installs.

## Teams

Settings, Members lists who belongs to the organization. There are three roles:

| Role | Can |
|---|---|
| `owner` | Everything, including members, roles, SAML single sign-on and deleting the organization |
| `admin` | Create and change projects, services, variables, domains and deploys, manage API tokens, invitations and notifications, and read the audit log |
| `member` | Read projects, services, deployments and logs, without secret values |

Admins invite people with Invite someone: pick the role and, optionally, an email address. The dialog
shows a link that works once and expires after 7 days; installations that can send mail email it too.
Admins cannot invite anyone above their own role. Owners change roles and remove members, and anyone can
leave an organization except its last owner.

Settings, Audit log shows every change made in the organization, who made it and whether an API token
was used. It records variable names, never their values.

## Notifications

Settings, Notifications posts events of every project in the organization to Slack, Discord or your own
HTTPS endpoint. Each channel picks its events: Deployment live, Deployment failed and Build failed.

A webhook channel receives a JSON body with `event`, `text`, `url`, `org` and `at`, plus `project`,
`environment`, `service`, `commitSha`, `buildId` and `deploymentId` where they apply, and the header
`X-Liftgate-Signature: sha256=<hex HMAC-SHA256 of the body>`, keyed with the signing secret the dashboard
shows once when you create the channel. Send test posts a test event. A delivery that fails is retried
with a growing delay for an hour.

## API tokens

Admins create tokens under Settings, API tokens, with an expiry of 30 days, 90 days, a year or never. The
token is shown once. Send it as a bearer token:

```sh
curl -X POST -H "Authorization: Bearer $LIFTGATE_TOKEN" -H "Content-Type: application/json" -d '{}' \
  https://liftgate.dev/api/v1/services/<service id>/deploy
```

`GET /api/v1/orgs/<org>/projects/<project>/tree` returns the project's environments and services with
their ids.

## Next

- [Custom domains](custom-domains.md) puts a service on your own hostname.
- [Plans and limits](plans-and-limits.md) lists what an organization may create and run.
- [Runtime contract](runtime-contract.md) lists what a container gets and must do.
