# Local MarsTV development loop

Submit a feature in Codex with `$marstv-development`, or start it from PowerShell:

```powershell
./scripts/start-development.ps1 -Feature 'Add a live clock to the bottom-right of the home screen for Free and Pro users'
```

The launcher starts an interactive local Codex run using your existing sign-in and permission settings. The reusable skill guides requirements/PRD, architecture, GitHub issue, isolated feature branch, implementation, tests, up to three repair iterations, independent review when available, and PR handoff. Clarifications and real permission blocks can still require input. It does not watch issues in the background or require an API key in GitHub Actions.

Prerequisites: Codex and Git installed, GitHub access to create issues/push branches/open PRs, JDK 17, Android SDK 36, and Gradle dependencies available. Connect GitHub in Codex or authenticate GitHub CLI locally; never paste tokens into a feature request. For device tests start an emulator without customer credentials.

## Checks and approval

The PR workflow compiles the debug app and instrumentation tests, runs unit tests and Android lint, and requests GitHub dependency review for known high/critical vulnerabilities. Build reports and a debug APK are retained for 14 days. Dependency-review coverage depends on GitHub having dependency graph snapshots; Gradle dependency submission is not enabled by this read-only workflow. Confirm repository-level snapshot availability before relying on this security check. It is not a complete security audit. The emulator job runs the clock and remote-selection tests. These checks need no production signing secrets.

The team checks the APK on TV/mobile, verifies acceptance criteria, and reviews the diff. A debug APK may not install over a production-signed app; use a test device/profile and do not uninstall a customer's app. Missing checks remain visible and keep the PR draft.

Repository protection is a separate administrator setting: require PR review and the workflow checks for main if desired. This change does not configure branch protection or enforce the team's approval server-side. It never enables auto-merge.

## Staging delivery

After an approved pull request is merged, the Android staging workflow builds the debug app, runs unit tests and lint, and installs the exact generated APKs on a clean emulator for smoke testing. Successful runs retain the staging APKs and reports as GitHub Actions artifacts for 14 days. The same workflow can be started manually by an authorized maintainer.

This staging step uses read-only repository permissions, debug signing and a reserved non-production backend hostname. It does not upload to MarsTV hosting, contact the production API during automated tests, update the backend, sign a production release or merge changes automatically. The team still decides whether a tested staging build advances toward production.

## Later release stages

Production signing and release publication are available only through the manually dispatched Android production release workflow. Signing and SSH publication use separate protected GitHub environments and approvals; no push, merge or development automation can start a production release. Production credentials stay in protected environment secrets and are not available to pull-request checks.

The workflow prepares a signed candidate first, then pauses for a second approval before atomically publishing through the allowlisted cPanel paths. The team must still complete staging and device acceptance checks, explicitly start the production workflow, approve both environments and verify the resulting update. Backend source deployment, database changes and billing/licensing changes remain outside this pipeline.
