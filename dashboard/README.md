# Liftgate dashboard

The web UI for the Liftgate control plane: Next.js 16 (App Router), React 19, TypeScript and Tailwind 4.

## Routes

| Route | Content |
|---|---|
| `/login` | Sign in with GitHub, Google, GitLab, Bitbucket, a passkey or an emailed code |
| `/login/sso` | SAML sign-in: finds the organization by email domain |
| `/account` | Profile, sign-in methods, passkeys, git connections |
| `/` | The landing page with `LIFTGATE_LANDING=true`, otherwise a redirect to `/dashboard` |
| `/dashboard` | Redirects to the first organization, or creates one; `?installed=1`, the GitHub App's setup URL, opens the new project dialog |
| `/dashboard/operator` | The operator console, for the accounts in `operators` only: pending accounts, all accounts and organizations, with approve, suspend, unsuspend and plan changes; `?view=` picks `pending`, `users` or `orgs` |
| `/[org]` | Projects; `?new=project` opens the new project dialog with the repository picker |
| `/[org]/settings/sso` | SAML connection, owners only |
| `/[org]/settings/members` | Members and roles: owners change roles and remove, admins invite, anyone leaves |
| `/[org]/settings/audit` | The organization's audit log, admins only |
| `/account/invitations/[token]` | Accept an invitation to an organization |
| `/[org]/[project]` | Environments and their services; `?new=service` opens the configure and deploy step |
| `/[org]/[project]/[environment]/[service]` | Deployments, runtime logs, builds with live logs, environment variables, domains, settings; `?tab=` picks the tab and `?tab=builds&build=<id>` opens that build's log |
| `/[org]/[project]/[service]` | Redirects to the service in the production environment |

## Development

```sh
npm install
npm run dev
```

The dashboard runs on `http://localhost:3000` and expects the control plane on `http://localhost:8080`. In development `next.config.ts` rewrites `/api/*` (including the log WebSockets) to that address, so the `liftgate_session` cookie is same-origin and no CORS setup is needed.

`npm run lint` and `npm run build` must both pass before a change is merged.

`npm run test:e2e` runs the Playwright browser tests in `e2e/` against the production build with the API mocked, at 1440, 768 and 375 px wide, including axe accessibility checks. Run `npm run build` first, and `npx playwright install chromium` once.

## Configuration

| Variable | Meaning |
|---|---|
| `NEXT_PUBLIC_API_URL` | Origin of the control plane as seen from the browser. Leave empty when the API is served under `/api` on the same host as the dashboard, as in local development. |
| `LIFTGATE_LANDING` | `true` serves the Liftgate Cloud landing page at `/`, with a Dashboard link instead of Sign in and Request access when a session cookie is present. Read by the server at request time rather than inlined at build time, so one image serves both settings. Try it locally with `LIFTGATE_LANDING=true npm run dev`. |
| `LIFTGATE_DASHBOARD_URL` | The dashboard's public URL, used for absolute links in page metadata such as `og:image`. The chart sets it. |
| `LIFTGATE_API_URL` | Where the dashboard's server reaches the control plane, for example `http://liftgate-control-plane:8080`. With it set, a path whose organization does not exist returns 404. The chart sets it. |

`NEXT_PUBLIC_*` variables are inlined at build time. Set them when running `npm run build` or as a Docker build argument; changing them on a running container has no effect. Copy `.env.example` to `.env.local` for local overrides.

## Container image

```sh
docker build -t ghcr.io/liftgate/dashboard .
docker run -p 3000:3000 ghcr.io/liftgate/dashboard
```

The image uses the standalone Next.js output and runs `node server.js` as an unprivileged user on port 3000. Pass `--build-arg NEXT_PUBLIC_API_URL=https://api.example.com` when the API lives on another origin.
