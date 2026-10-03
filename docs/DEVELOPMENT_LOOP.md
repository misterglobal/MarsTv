# Local MarsTV development loop

Submit a feature in Codex with `$marstv-development`, or start it from PowerShell:

```powershell
./scripts/start-development.ps1 -Feature 'Add a live clock to the bottom-right of the home screen for Free and Pro users'
```

The launcher starts an interactive local Codex run using your existing sign-in and permission settings. The reusable skill guides requirements/PRD, architecture, GitHub issue, isolated feature branch, implementation, tests, up to three repair iterations, independent review when available, and PR handoff. Clarifications and real permission blocks can still require input. It does not watch issues in the background or require an API key in GitHub Actions.

Prerequisites: Codex and Git installed, GitHub access to create issues/push branches/open PRs, JDK 17, Android SDK 36, and Gradle dependencies available. Connect GitHub in Codex or authenticate GitHub CLI locally; never paste tokens into a feature request. For device tests start an emulator without customer credentials.

## Checks and approval

The PR workflow compiles the debug app and instrumentation tests, runs unit tests and Android lint, and requests GitHub dependency review for known high/critical vulnerabilities. Build reports and a debug APK are retained for 14 days. Dependency-review coverage depends on GitHub having dependency graph snapshots; Gradle dependency submission is not enabled by this read-only workflow. Confirm repository-level snapshot availability before relying on this security check. It is not a complete security audit. The emulator job runs the clock and remote-selection tests. These checks need no production signing secrets.

Marcel checks the APK on TV/mobile, verifies acceptance criteria, and reviews the diff. A debug APK may not install over a production-signed app; use a test device/profile and do not uninstall a customer's app. Missing checks remain visible and keep the PR draft.

Repository protection is a separate administrator setting: require PR review and the workflow checks for main if desired. This change does not configure branch protection or enforce Marcel's approval server-side. It never enables auto-merge.

## Later release stages

Merge, staging deployment, staging smoke tests and production release are not enabled here. They need explicit follow-up authorization, a defined staging target, release credentials held outside the development agent, and approval gates. This first loop ends at the reviewed PR.
