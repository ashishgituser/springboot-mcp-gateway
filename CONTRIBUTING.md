# Contributing

Thanks for considering a contribution.

## Getting started

1. Fork the repo and clone your fork.
2. Make sure `mvn -B verify` passes before you start (Java 17+ required).
3. Create a branch off `main` for your change.

## Making changes

- Keep the module boundaries intact: `mcp-gateway-core` has no Spring Boot dependency, `mcp-gateway-autoconfigure` holds all the wiring, `mcp-gateway-spring-boot-starter` stays a thin POM.
- Add or update tests for any behavior change. `mvn -B verify` runs the full build including tests and formatting checks.
- Run `mvn spotless:apply` before committing to auto-format Java sources.
- Update `CHANGELOG.md` under the `Unreleased` section for any user-facing change.

## Submitting a PR

- Keep PRs focused — one logical change per PR is easier to review than a bundle of unrelated ones.
- Describe what changed and why in the PR description.
- CI must be green before review.

## Reporting issues

Use the issue templates. For bugs, include Spring Boot version, Java version, and a minimal reproduction if possible.
