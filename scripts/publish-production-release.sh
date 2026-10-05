#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 RELEASE_BUNDLE_DIRECTORY" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
bundle="$1"

required_environment=(
  MARSTV_SSH_HOST
  MARSTV_SSH_USER
  MARSTV_SSH_PORT
  MARSTV_SSH_PRIVATE_KEY_FILE
  MARSTV_SSH_KNOWN_HOSTS_FILE
  GITHUB_RUN_ID
)
for name in "${required_environment[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Missing required environment: $name" >&2; exit 2; }
done

[[ "$MARSTV_SSH_HOST" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "Invalid SSH host" >&2; exit 2; }
[[ "$MARSTV_SSH_USER" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Invalid SSH user" >&2; exit 2; }
[[ "$MARSTV_SSH_PORT" =~ ^[0-9]{1,5}$ ]] || { echo "Invalid SSH port" >&2; exit 2; }
[[ "$GITHUB_RUN_ID" =~ ^[0-9]+$ ]] || { echo "Invalid GitHub run id" >&2; exit 2; }
[[ -f "$MARSTV_SSH_PRIVATE_KEY_FILE" && -f "$MARSTV_SSH_KNOWN_HOSTS_FILE" ]] || {
  echo "SSH key or known-hosts file is unavailable" >&2
  exit 2
}

mapfile -t release_values < <(python tools/package_release.py verify \
  --bundle "$bundle" \
  --public-key app/update-public-key.txt \
  --key-id release-2026-01 \
  --lines)
[[ ${#release_values[@]} -eq 5 ]] || { echo "Could not read release bundle metadata" >&2; exit 1; }
apk_file="${release_values[0]}"
version_name="${release_values[1]}"
apk_url="${release_values[3]}"
apk_sha256="${release_values[4]}"

remote_root=/home2/hpgeqkld/marstvonline
downloads_dir="$remote_root/marstv-backend/public/downloads"
releases_dir="$remote_root/marstv-backend/storage/releases"
environment_file="$remote_root/marstv-backend/.env"
staging_dir="$releases_dir/.production-release-$GITHUB_RUN_ID"
temporary_apk="$staging_dir/$apk_file"
final_apk="$downloads_dir/$apk_file"
temporary_manifest="$staging_dir/direct-stable.jws"
final_manifest="$releases_dir/direct-stable.jws"
destination="$MARSTV_SSH_USER@$MARSTV_SSH_HOST"

ssh_options=(
  -i "$MARSTV_SSH_PRIVATE_KEY_FILE"
  -p "$MARSTV_SSH_PORT"
  -o BatchMode=yes
  -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$MARSTV_SSH_KNOWN_HOSTS_FILE"
)
scp_options=(
  -i "$MARSTV_SSH_PRIVATE_KEY_FILE"
  -P "$MARSTV_SSH_PORT"
  -o BatchMode=yes
  -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$MARSTV_SSH_KNOWN_HOSTS_FILE"
)

remote_action() {
  local mode="$1"
  ssh "${ssh_options[@]}" "$destination" bash -s -- \
    "$mode" "$GITHUB_RUN_ID" "$temporary_apk" "$final_apk" \
    "$temporary_manifest" "$final_manifest" "$environment_file" \
    "$apk_url" "$version_name" "$apk_sha256" \
    < scripts/remote-publish-release.sh
}

published=0
confirmed=0
verification_manifest=""
verification_apk=""
cleanup() {
  status=$?
  set +e
  if [[ -n "$verification_manifest" ]]; then
    rm -f "$verification_manifest"
  fi
  if [[ -n "$verification_apk" ]]; then
    rm -f "$verification_apk"
  fi
  if [[ $published -eq 1 && $confirmed -eq 0 ]]; then
    rollback_status=1
    for attempt in 1 2 3; do
      remote_action rollback
      rollback_status=$?
      [[ $rollback_status -eq 0 ]] && break
      echo "Rollback attempt $attempt of 3 failed" >&2
      [[ $attempt -lt 3 ]] && sleep 5
    done
    if [[ $rollback_status -ne 0 ]]; then
      echo "::error::AUTOMATIC ROLLBACK FAILED. Production may require manual recovery from the private release staging directory for run $GITHUB_RUN_ID." >&2
      status=70
    fi
  elif [[ $published -eq 0 ]]; then
    remote_action cleanup
  fi
  exit "$status"
}
trap cleanup EXIT

ssh "${ssh_options[@]}" "$destination" \
  test -d "$downloads_dir" -a -d "$releases_dir" -a -f "$environment_file"
remote_action stage
scp "${scp_options[@]}" "$bundle/$apk_file" "$destination:$temporary_apk"
scp "${scp_options[@]}" "$bundle/direct-stable.jws" "$destination:$temporary_manifest"
published=1
remote_action publish

verification_manifest="$(mktemp)"
verification_apk="$(mktemp)"
curl --fail --silent --show-error --max-time 180 "$apk_url" --output "$verification_apk"
published_sha256="$(sha256sum "$verification_apk" | awk '{print $1}')"
[[ "$published_sha256" == "$apk_sha256" ]] || { echo "Published APK checksum mismatch" >&2; exit 1; }
curl --fail --silent --show-error --max-time 30 \
  -H 'Cache-Control: no-cache' \
  https://marstv.online/api/v1/releases/direct-stable \
  --output "$verification_manifest"
cmp --silent "$bundle/direct-stable.jws" "$verification_manifest"
remote_action confirm
confirmed=1
rm -f "$verification_manifest"
rm -f "$verification_apk"
verification_manifest=""
verification_apk=""
trap - EXIT
echo "Published and verified MarsTV $version_name"
