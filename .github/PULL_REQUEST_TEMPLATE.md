## What

## Why

## How it was verified

## Checklist

- [ ] Commit messages are lowercase and start with `feat:`, `fix:` or `chore:`
- [ ] No comments were added; every new top-level Kotlin class or object opens with the author block
- [ ] The relevant build is green locally: `./gradlew build`, `npm run lint && npm test && npm run build` or `helm lint charts/liftgate`
- [ ] Non-trivial branches, parsers, state machines and security paths have a test
- [ ] Configuration changes are mirrored in `control-plane/.env.example`, `charts/liftgate/values.yaml`, `values.schema.json` and the README
