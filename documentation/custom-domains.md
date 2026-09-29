# Custom domains

Every `web` and `static` service gets a platform hostname under the installation's deploy domain, such as
`web-shop-acme.liftgate.app` on Liftgate Cloud. A custom domain serves the same service on a hostname you
own, such as `app.example.com`, with a certificate Liftgate obtains for you.

## Add a domain

You need the admin or owner role in the organization.

1. Open the service, then the Domains tab, and enter the hostname under Add a custom domain.
2. Create the two records the tab lists at your DNS provider:
   - a `TXT` record at `_liftgate.<hostname>` holding the domain's verification token, which proves you
     control the name;
   - a `CNAME` record from the hostname to the target the tab shows, which sends the traffic to Liftgate.
3. Keep the Domains tab open. The dashboard checks the TXT record every 15 seconds and marks the domain
   verified once it finds the token. The Verify button checks at once, and so does
   `POST /api/v1/domains/<id>/verify` with an API token.
4. After verification, Liftgate requests a certificate. The Certificate column moves from `pending` to
   `ready`, or to `failed` with the reason under the hostname. Keep the CNAME record in place: the
   certificate is issued and renewed through it.

DNS changes can take a few minutes to reach Liftgate's resolver, so a fresh record may need several
checks. Verification is a one-time check. Removing the TXT record later does not unverify the domain.

An apex domain such as `example.com` cannot have a CNAME record at most DNS providers. Use the provider's
CNAME flattening or `ALIAS` record with the same target. On a self-hosted installation in gateway mode,
described below, an `A` record at the gateway's address works as well.

## Rules

- A hostname under the deploy domain is not accepted; those names are assigned automatically.
- A hostname that another service has verified is not accepted until that service removes it.
- Only `web` and `static` services receive traffic for their domains.
- Each custom domain counts towards the organization's plan. The operator can also cap the number of
  custom domains across the whole installation. Both limits answer `409 plan_limit`, see
  [Plans and limits](plans-and-limits.md).
- Remove a domain with Remove on the Domains tab. Liftgate stops routing it and removes its certificate.
  Platform hostnames cannot be removed.

## How an installation serves custom domains

The dashboard always lists the right CNAME target for its installation. Operators pick one of two modes.

### Gateway mode

Gateway mode is the default for self-hosted installations. The CNAME points at the service's platform
hostname. For every verified domain the reconciler adds an HTTPS listener to the installation's Gateway
and a cert-manager `Certificate` from the ClusterIssuer in the chart's `gateway.issuer`, `letsencrypt`
unless changed. That issuer solves the HTTP-01 challenge through the Gateway, so the domain must resolve
to the gateway's public address and port 80 must be open. The certificate status in the dashboard follows
the `Certificate`'s `Ready` condition.

A Gateway holds at most 64 listeners, so keep `customDomains.max` below 64 minus the listeners the chart
creates itself: one for HTTP, one for the deploy domain and one per distinct host in `publicUrl` and
`dashboardUrl`. To see why a certificate is not ready, look at its `Certificate` and ACME challenge in the
release namespace:

```sh
kubectl -n liftgate-system get certificates
kubectl -n liftgate-system describe certificate app.example.com
kubectl get challenges --all-namespaces
```

### Edge mode

Edge mode serves custom domains through Cloudflare for SaaS custom hostnames, for installations that sit
behind Cloudflare. The CNAME points at `cname.<deploy domain>`, and Cloudflare issues the certificate.
The operator setup is under Custom domains in the [chart README](../charts/liftgate/README.md#custom-domains).

### Turning custom domains off

With `customDomains.enabled: false` the Domains tab replaces the add form with a notice, for installations
whose edge cannot route customer hostnames.
