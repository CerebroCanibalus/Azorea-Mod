# Contributing to Azorea

Thank you for your interest in contributing. Azorea is a free, GPL-3.0-licensed Minecraft mod; contributions are welcome under the same license.

## Reporting bugs

File a [bug report](https://github.com/CerebroCanibalus/azorea/issues/new?template=bug_report.md). Use the **Place** if you can reproduce on a vanilla 1.21.1 server with only Azorea installed.

**Security issues**: do NOT file public issues. Use GitHub Security Advisories (see [SECURITY.md](SECURITY.md)).

## Proposing features

Open a [feature request](https://github.com/CerebroCanibalus/azorea/issues/new?template=feature_request.md) describing the use case, not the implementation. The maintainers will discuss whether it aligns with the project's design decisions (DA-1..DA-14 in `AGENTS.md`).

## Submitting changes

1. Fork the repo, create a topic branch (`fix/<thing>`, `feat/<thing>`, etc).
3. Keep the build green: `./gradlew :v1_21_1:test` (249 tests, 0 failures expected).
4. Run the bundled linter / formatter if one is configured.
5. Open a [pull request](https://github.com/CerebroCanibalus/azorea/compare). Use the PR template.

## Design constraints to know before contributing

Azorea is opinionated. Some decisions are non-negotiable:

- **No central infra.** Azorea does not operate a relay, rendezvous, or tracker. Code that hardcodes URLs to our infra is rejected.
- **No telemetry.** No analytics, no phone-home.
- **GPL-3.0.** All contributions are under GPL-3.0. By submitting a PR, you agree to license your contribution under the same.
- **Java 21.** Required for NeoForge 21.1.x.
- **ModDevGradle 2.x**, **Gradle 8.x**, **NeoForge 21.1.250+**.
- **Single source of truth** for shared crypto: `shared/src/main/java/...` (DA-13). Don't duplicate it.

The full decision log lives in `AGENTS.md` (sections **Decisiones arquitectónicas** and **Descubrimientos**). Read it before opening a feature PR.

## Code style

- Java 21 features are encouraged (records, sealed types, text blocks).
- 4-space indent, no tabs.
- Lines under 120 cols.
- Imports: no wildcard imports.
- Comments: in Spanish or English, consistent within the file.
- Tests for anything non-trivial. `AzoreaWorldAccessTest`, `AzoreaAccessGateTest` and `AzoreaInviteBundleTest` are good examples of how the project tests security-sensitive code.

## Running tests locally

```bash
./gradlew :v1_21_1:test
./gradlew :tracker-server:test
RUN_NETWORK_TESTS=1 ./gradlew :v1_21_1:test   # enables real STUN probes
```

## Release process

The maintainer (General Beria) cuts releases manually. Tags follow semver (`MAJOR.MINOR.PATCH`); the current line is `1.4.x`.