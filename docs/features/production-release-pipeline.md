# Approval-gated production release pipeline

## User outcome

Marcel can manually prepare and publish a MarsTV production release from `main`. APK signing and hosting publication are separate protected jobs, so reviewing a pull request or merging code can never release automatically.

## Acceptance criteria

- Pull requests run release-tool tests and shell syntax checks without receiving production secrets.
- A production run is manual, must use `main`, and requires the exact `PUBLISH MARSTV` confirmation.
- `production-signing` approval is required before the existing Android key and manifest key are exposed to the build job.
- `production-publish` approval is separately required before SSH credentials are exposed to the hosting job.
- The release build runs unit tests and release lint, verifies APK signing, generates the signed direct-update manifest, and packages crash diagnostics when available.
- The workflow verifies the current production manifest with the tracked public key, derives the previous version code from production, and rejects any APK not signed by the pinned production certificate.
- The workflow uploads the APK and manifest under temporary names, verifies the remote APK checksum, atomically updates the backend download metadata, and activates `direct-stable.jws` last.
- Publication is accepted only when the public APK responds and the production update endpoint returns the exact signed manifest.
- A failed public verification restores the previous manifest and backend download metadata and removes the new APK.
- Private keys and passwords never enter repository files, workflow artifacts or command output.

## Protected environments

Create two GitHub environments before the first release and configure Marcel as a required reviewer:

### `production-signing`

- `ANDROID_KEYSTORE_BASE64`: base64 encoding of the existing production Android keystore.
- `ANDROID_KEYSTORE_PASSWORD`: existing keystore password.
- `ANDROID_KEY_ALIAS`: existing signing-key alias.
- `ANDROID_KEY_PASSWORD`: existing key password.
- `MARSTV_ENTITLEMENT_PUBLIC_KEY_PEM`: production entitlement-verification public key in PEM form.
- `MARSTV_MANIFEST_PRIVATE_KEY_PEM`: existing P-256 update-manifest private key in PEM form.
- `MARSTV_PRIVATE_PORTAL_URL`: production username-only MarsTV portal URL.
- `MARSTV_PRODUCTION_CERT_SHA256`: lowercase SHA-256 digest of the existing production APK signing certificate.

### `production-publish`

- `MARSTV_SSH_HOST`: cPanel SSH hostname.
- `MARSTV_SSH_USER`: restricted cPanel SSH user.
- `MARSTV_SSH_PORT`: SSH port, normally `22`.
- `MARSTV_SSH_PRIVATE_KEY`: private key for the restricted publishing account.
- `MARSTV_SSH_KNOWN_HOSTS`: a pre-verified `known_hosts` entry for the cPanel SSH host.

Keep the two groups separate. The signing job does not receive SSH credentials, and the publishing job does not receive APK or manifest signing keys. Do not use passwords in the workflow and do not obtain `known_hosts` with an unauthenticated `ssh-keyscan` during a release.

## Hosting layout

- Repository root: `/home2/hpgeqkld/marstvonline`
- Public root: `/home2/hpgeqkld/marstvonline/marstv-backend/public`
- APK directory: `/home2/hpgeqkld/marstvonline/marstv-backend/public/downloads`
- Manifest directory: `/home2/hpgeqkld/marstvonline/marstv-backend/storage/releases`
- Backend environment: `/home2/hpgeqkld/marstvonline/marstv-backend/.env`

The remote helper rejects paths outside this allowlist. It refuses to overwrite an APK with the same version filename.

## Release procedure

1. Merge a reviewed version bump into `main`; `versionCode` must exceed the currently published code.
2. Confirm the `main` staging workflow is green and complete device acceptance testing.
3. Open **Actions → Android production release → Run workflow** on `main`.
4. Enter the minimum supported version code, priority and release notes, then type `PUBLISH MARSTV`. The workflow derives the previous version code from the cryptographically verified production manifest.
5. Review and approve `production-signing`. Inspect the resulting signed release candidate before continuing.
6. Review and approve `production-publish`. The workflow uploads temporary files, verifies them and activates the manifest last.
7. Confirm the workflow's public verification passed. Test update discovery and installation on the acceptance devices.

## Non-goals

- Automatic releases on push or merge.
- Silent Android installation or bypassing device confirmation.
- Deploying backend source, changing the production database, or changing billing/licensing behavior.
- Creating, rotating, downloading or displaying production keys.
