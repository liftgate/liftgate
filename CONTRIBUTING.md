# Contributing to Liftgate

Liftgate is pre-alpha and moving quickly. Open an issue before a large change so the design can be agreed first; small fixes can go straight to a pull request. Read the [README](README.md) for the architecture and the local development setup, and the [code of conduct](CODE_OF_CONDUCT.md) before taking part.

## Setup

Java 21, Node 22, Docker with Compose v2, and Helm (CI uses 4.3.0). The README's local development section brings up PostgreSQL and NATS with Compose, the control plane with Gradle and the dashboard with npm.

## House rules

These are checked in review and are not negotiable.

### No comments

No comments in any file, in any language: no doc comments, no JSDoc, no explanations of clever code, no commented-out code, no TODO markers. Names and structure carry the meaning; if a block needs a comment to be understood, change the code.

The single exception is the block that opens every top-level Kotlin class or object, with nothing else in it:

```kotlin
/**
 * @author Dean
 * @date 9/17/2026
 */
object DomainNames {
```

The date is the day the file was created, written `M/D/YYYY` without leading zeros. The block never appears on nested classes, functions or properties, and never in TypeScript, SQL, YAML, shell or Markdown.

### Compact idiomatic Kotlin

- Expression bodies for single-expression functions.
- `when` instead of `if` / `else if` chains.
- At most two levels of nesting inside a function; extract or invert instead of indenting further.
- No redundant locals, no speculative parameters, no abstraction with a single user: no interface with one implementation, no factory for one product.
- Small related declarations may share a file; a substantial class gets its own file. Annotation classes live in a dedicated `Annotations.kt` per package, never beside the code they configure.
- Verify library APIs against the versions pinned in `control-plane/gradle/libs.versions.toml`. Several are newer than most examples online: Exposed 1.5 lives in `org.jetbrains.exposed.v1.*`, and Ktor 3.6, fabric8 7.9 and Hazelcast 5.7 differ from their predecessors.

Code is judged by how it reads, not only by whether it works.

### TypeScript

Strict mode, function components, no `any`, no barrel files. Read `dashboard/AGENTS.md` before touching the dashboard; Next.js 16 is not the version most examples describe.

### Tests

Every non-trivial branch, parser, state machine or security path leaves one runnable test. Unit tests use JUnit 5 with `kotlin.test` assertions and MockK. Repository and HTTP tests use Testcontainers PostgreSQL and skip when Docker is not available; Kubernetes code is tested against the fabric8 mock server.

## Commit messages

Entirely lowercase, except identifiers such as class names, and prefixed with `feat:`, `fix:` or `chore:`:

```
feat: rollback creates a deployment on the previous build
fix: Reconciler lower-cases HTTPRoute hostnames
chore: bump fabric8 to 7.9.0
```

One change per commit, described in the imperative.

## Pull requests

Before opening one:

- The relevant build is green locally: `./gradlew build` in `control-plane/`, `npm run lint && npm test && npm run build` in `dashboard/`, `helm lint charts/liftgate` for chart changes.
- New behaviour has a test; changed behaviour has an updated test.
- Configuration changes are mirrored in `control-plane/.env.example`, `charts/liftgate/values.yaml`, `values.schema.json` and the README.
- The diff contains no comments, no formatting-only noise and no unrelated refactors.
- The description says what changed, why, and how it was verified.

The pull request template repeats this list. CODEOWNERS assigns a maintainer to every pull request automatically.

## Licence

Liftgate is licensed under the GNU Affero General Public License v3.0. By contributing you agree that your contributions are licensed under the same terms.
