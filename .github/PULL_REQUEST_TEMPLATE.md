## What does this PR do?

One paragraph.

## Why is this change needed?

Link the issue (if there is one) or describe the user pain.

## How was it tested?

Describe the manual or automated tests you ran. Include the build output:
- `./gradlew :v1_21_1:test` → tests=N failures=0 errors=0 skipped=N
- `./gradlew :tracker-server:test` (if applicable) → tests=N failures=0 errors=0 skipped=N

If you ran a manual end-to-end test (e.g., WAN playtest), describe the setup.

## Design decisions

If your change touches architecture, explain the decision in the PR body and check it against the constraints in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Checklist

- [ ] `./gradlew :v1_21_1:test` passes locally.
- [ ] No telemetry added.
- [ ] No hardcoded URLs to third-party infra.
- [ ] New code has tests (if non-trivial).
- [ ] `gradle.properties` and `neoforge.mods.toml` version synced (if release-relevant).
- [ ] User-visible behavior changes are reflected in `README.md` / `README.es.md`.