# Liftgate dashboard

The web UI for the Liftgate control plane: Next.js 16 (App Router), React 19, TypeScript and Tailwind 4.

## Routes

| Route | Content |
|---|---|
| `/login` | GitHub sign-in |
| `/` | Redirects to the first organization, or creates one |
| `/[org]` | Projects |
| `/[org]/[project]` | Environments and their services |
| `/[org]/[project]/[service]` | Deployments, builds with live logs, environment variables, domains, settings |

## Development

```sh
npm install
npm run dev
```

The dashboard runs on `http://localhost:3000` and expects the control plane on `http://localhost:8080`. In development `next.config.ts` rewrites `/api/*` (including the log WebSockets) to that address, so the `liftgate_session` cookie is same-origin and no CORS setup is needed.

`npm run lint` and `npm run build` must both pass before a change is merged.

## Configuration

| Variable | Meaning |
|---|---|
| `NEXT_PUBLIC_API_URL` | Origin of the control plane as seen from the browser. Leave empty when the API is served under `/api` on the same host as the dashboard, as in local development. |

`NEXT_PUBLIC_*` variables are inlined at build time. Set them when running `npm run build` or as a Docker build argument; changing them on a running container has no effect. Copy `.env.example` to `.env.local` for local overrides.

## Container image

```sh
docker build -t ghcr.io/liftgate/dashboard .
docker run -p 3000:3000 ghcr.io/liftgate/dashboard
```

The image uses the standalone Next.js output and runs `node server.js` as an unprivileged user on port 3000. Pass `--build-arg NEXT_PUBLIC_API_URL=https://api.example.com` when the API lives on another origin.
