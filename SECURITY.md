# Security policy

## Supported versions

Liftgate is pre-alpha. Security fixes land on `main` and in the next tagged release; earlier tags are not patched.

## Reporting a vulnerability

Do not open a public issue or pull request for a vulnerability.

Report it privately through GitHub at https://github.com/liftgate/liftgate/security/advisories/new. Include the affected component (control plane, dashboard, Helm chart, build image or infra), the version or commit, the impact, and the steps or proof of concept that reproduce it.

You will get an acknowledgement within three business days. We keep you informed while the report is triaged and fixed, agree a disclosure date with you, and credit you in the advisory unless you prefer not to be named.

## Scope

Everything in this repository is in scope. The reports we care most about, in rough order:

- Tenant isolation failures: reaching another environment's namespace, secrets, network or the cluster itself from a tenant workload or a build job.
- Authentication and authorization bypasses in the API, the dashboard or the GitHub webhook.
- Exposure of environment variable values, API tokens, session ids, registry credentials or GitHub App keys in responses, logs, images or the database.
- Injection through repository contents, build settings, domains or webhook payloads.

Vulnerabilities in dependencies belong upstream; if Liftgate needs a change to mitigate one, report that here too. Findings without a demonstrated impact are still welcome as ordinary issues.
