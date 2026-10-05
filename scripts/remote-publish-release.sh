#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 10 ]]; then
  echo "Expected mode, run id, temporary/final paths, environment file and release metadata" >&2
  exit 2
fi

mode="$1"
run_id="$2"
temporary_apk="$3"
final_apk="$4"
temporary_manifest="$5"
final_manifest="$6"
environment_file="$7"
apk_url="$8"
version_name="$9"
apk_sha256="${10}"

remote_root=/home2/hpgeqkld/marstvonline
downloads_dir="$remote_root/marstv-backend/public/downloads"
releases_dir="$remote_root/marstv-backend/storage/releases"
expected_environment_file="$remote_root/marstv-backend/.env"
lock_dir="$releases_dir/.production-release-lock"
staging_dir="$releases_dir/.production-release-$run_id"
environment_backup="$environment_file.release-$run_id.bak"
manifest_backup="$final_manifest.release-$run_id.bak"
manifest_missing_marker="$manifest_backup.missing"

[[ "$run_id" =~ ^[0-9]+$ ]] || { echo "Invalid run id" >&2; exit 2; }
[[ "$version_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] || { echo "Invalid version name" >&2; exit 2; }
[[ "$apk_sha256" =~ ^[a-f0-9]{64}$ ]] || { echo "Invalid APK checksum" >&2; exit 2; }
[[ "$final_apk" == "$downloads_dir/MarsTV-$version_name.apk" ]] || { echo "Unexpected APK path" >&2; exit 2; }
[[ "$temporary_apk" == "$staging_dir/MarsTV-$version_name.apk" ]] || { echo "Unexpected temporary APK path" >&2; exit 2; }
[[ "$final_manifest" == "$releases_dir/direct-stable.jws" ]] || { echo "Unexpected manifest path" >&2; exit 2; }
[[ "$temporary_manifest" == "$staging_dir/direct-stable.jws" ]] || { echo "Unexpected temporary manifest path" >&2; exit 2; }
[[ "$environment_file" == "$expected_environment_file" ]] || { echo "Unexpected environment path" >&2; exit 2; }
[[ "$apk_url" == "https://marstv.online/downloads/MarsTV-$version_name.apk" ]] || { echo "Unexpected APK URL" >&2; exit 2; }
[[ -d "$downloads_dir" && ! -L "$downloads_dir" ]] || { echo "Downloads directory is unavailable" >&2; exit 1; }
[[ -d "$releases_dir" && ! -L "$releases_dir" ]] || { echo "Releases directory is unavailable" >&2; exit 1; }

if ! mkdir "$lock_dir" 2>/dev/null; then
  echo "Another production publication is already active" >&2
  exit 1
fi
trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT

set_environment_value() {
  local key="$1"
  local value="$2"
  local file="$3"
  local next="$file.next"
  awk -v key="$key" -v value="$value" '
    BEGIN { replaced = 0 }
    $0 ~ "^" key "=" { if (!replaced) print key "=" value; replaced = 1; next }
    { print }
    END { if (!replaced) print key "=" value }
  ' "$file" > "$next"
  mv -f -- "$next" "$file"
}

restore_release() {
  local release_state=0
  if [[ -f "$environment_backup" ]]; then
    mv -f -- "$environment_backup" "$environment_file"
    release_state=1
  fi
  if [[ -f "$manifest_backup" ]]; then
    mv -f -- "$manifest_backup" "$final_manifest"
    release_state=1
  elif [[ -f "$manifest_missing_marker" ]]; then
    rm -f -- "$final_manifest" "$manifest_missing_marker"
    release_state=1
  fi
  if [[ $release_state -eq 1 ]]; then
    rm -f -- "$final_apk"
  fi
  rm -f -- "$temporary_apk" "$temporary_manifest"
  rmdir "$staging_dir" 2>/dev/null || true
}

case "$mode" in
  stage)
    [[ ! -e "$staging_dir" && ! -L "$staging_dir" ]] || { echo "Release staging directory already exists" >&2; exit 1; }
    mkdir -m 700 -- "$staging_dir"
    ;;
  publish)
    [[ -d "$staging_dir" && ! -L "$staging_dir" ]] || { echo "Private release staging directory is unavailable" >&2; exit 1; }
    [[ -f "$temporary_apk" && ! -L "$temporary_apk" ]] || { echo "Temporary APK is missing" >&2; exit 1; }
    [[ -f "$temporary_manifest" && ! -L "$temporary_manifest" ]] || { echo "Temporary manifest is missing" >&2; exit 1; }
    [[ -f "$environment_file" && ! -L "$environment_file" ]] || { echo "Backend environment file is unavailable" >&2; exit 1; }
    [[ ! -L "$final_manifest" ]] || { echo "Manifest path must not be a symbolic link" >&2; exit 1; }
    [[ ! -e "$final_apk" && ! -L "$final_apk" ]] || { echo "Release APK already exists; refusing to overwrite it" >&2; exit 1; }
    [[ ! -e "$environment_backup" && ! -e "$manifest_backup" && ! -e "$manifest_missing_marker" ]] || {
      echo "Release backup paths already exist" >&2
      exit 1
    }
    remote_sha256="$(sha256sum "$temporary_apk" | awk '{print $1}')"
    [[ "$remote_sha256" == "$apk_sha256" ]] || { echo "Remote APK checksum mismatch" >&2; exit 1; }
    cp -p -- "$environment_file" "$environment_backup"
    if [[ -f "$final_manifest" && ! -L "$final_manifest" ]]; then
      cp -p -- "$final_manifest" "$manifest_backup"
    else
      : > "$manifest_missing_marker"
    fi
    environment_temporary="$environment_file.release-$run_id.tmp"
    cp -p -- "$environment_file" "$environment_temporary"
    set_environment_value MARSTV_APK_URL "$apk_url" "$environment_temporary"
    set_environment_value MARSTV_APK_VERSION "$version_name" "$environment_temporary"
    set_environment_value MARSTV_APK_SHA256 "$apk_sha256" "$environment_temporary"
    rollback_required=1
    trap '
      status=$?
      if [[ ${rollback_required:-0} -eq 1 ]]; then restore_release; fi
      rmdir "$lock_dir" 2>/dev/null || true
      exit $status
    ' EXIT
    mv -f -- "$temporary_apk" "$final_apk"
    mv -f -- "$environment_temporary" "$environment_file"
    mv -f -- "$temporary_manifest" "$final_manifest"
    rollback_required=0
    ;;
  confirm)
    rm -f -- "$environment_backup" "$manifest_backup" "$manifest_missing_marker" "$temporary_apk" "$temporary_manifest"
    rmdir "$staging_dir" 2>/dev/null || true
    ;;
  rollback)
    restore_release
    ;;
  cleanup)
    rm -f -- "$temporary_apk" "$temporary_manifest"
    rmdir "$staging_dir" 2>/dev/null || true
    ;;
  *)
    echo "Unknown publication mode" >&2
    exit 2
    ;;
esac
