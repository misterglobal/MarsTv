# Staging continuous delivery

## User outcome

Every approved change merged to `main` produces a tested MarsTV staging APK that the team can download from the matching GitHub Actions run. This provides a repeatable handoff for testing without deploying to hosting or using production signing material.

## Acceptance criteria

- A push to `main` starts the staging workflow, and an authorized maintainer can also start it manually.
- The workflow builds the debug app and instrumentation APK with JDK 17 and Android SDK 36.
- Unit tests and Android lint pass before the staging artifact is accepted.
- A clean Android emulator installs the exact APKs uploaded by the build job and runs the existing home-clock, remote-selection and My TV tests.
- The staging APKs and reports are downloadable from the workflow run for 14 days.
- The workflow has read-only repository permissions, does not persist checkout credentials and requires no production secrets.
- The staging build uses a reserved non-production backend hostname and does not contact the production MarsTV API during automated tests.
- A failed build, test, lint or emulator check leaves the workflow failed rather than publishing a successful staging result.

## Affected components

- `.github/workflows/android-staging.yml`: main-branch staging build, artifact handoff and emulator verification.
- `scripts/run-staging-smoke.sh`: installs the delivered APKs and fails unless instrumentation reports a successful test run.
- `docs/DEVELOPMENT_LOOP.md`: documents the automated staging boundary and manual production gate.

## Non-goals

- Production APK or update-manifest signing.
- Uploading files to `marstv.online` or any other hosting service.
- Deploying the backend or changing billing, licensing or production data.
- Automatically merging pull requests or publishing a production release.

## Verification plan

- Run the existing debug build, unit tests, lint and instrumentation APK build locally.
- Validate the workflow YAML and review its permissions, triggers and artifact paths.
- Let the pull request workflow exercise the staging build and clean-emulator smoke test.
- After the team approves and merges the pull request, confirm the first `main` workflow run produces downloadable staging APKs.
