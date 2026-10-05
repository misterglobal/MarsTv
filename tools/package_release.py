"""Validate and package signed MarsTV production release artifacts."""

import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature


VERSION_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
SHA256_PATTERN = re.compile(r"[a-f0-9]{64}")
BASE64URL_PATTERN = re.compile(r"[A-Za-z0-9_-]+")
MAX_MANIFEST_BYTES = 32768
MAX_APK_BYTES = 250 * 1024 * 1024
MANIFEST_FIELDS = {
    "iss",
    "aud",
    "packageId",
    "signingCertificateSha256",
    "channel",
    "versionCode",
    "versionName",
    "minimumSupportedVersionCode",
    "priority",
    "apkUrl",
    "sha256",
    "sizeBytes",
    "releaseNotes",
    "publishedAt",
}


def file_sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def decode_json_segment(segment: str, description: str) -> dict:
    padding = "=" * (-len(segment) % 4)
    try:
        value = json.loads(base64.urlsafe_b64decode(segment + padding))
    except (ValueError, json.JSONDecodeError) as error:
        raise ValueError(f"Manifest {description} is not valid JSON") from error
    if not isinstance(value, dict):
        raise ValueError(f"Manifest {description} must be an object")
    return value


def verify_manifest(token: str, public_key_path: Path, key_id: str) -> dict:
    if not 1 <= len(token.encode("ascii")) <= MAX_MANIFEST_BYTES:
        raise ValueError("Manifest exceeds the client size limit")
    parts = token.split(".")
    if len(parts) != 3 or not all(BASE64URL_PATTERN.fullmatch(part) for part in parts):
        raise ValueError("Manifest must be a compact three-part JWS")
    header = decode_json_segment(parts[0], "header")
    if header != {"alg": "ES256", "kid": key_id, "typ": "marstv-update+jws"}:
        raise ValueError("Manifest protected header is invalid")
    payload = decode_json_segment(parts[1], "payload")
    signature_padding = "=" * (-len(parts[2]) % 4)
    try:
        signature = base64.urlsafe_b64decode(parts[2] + signature_padding)
    except ValueError as error:
        raise ValueError("Manifest signature is not valid base64url") from error
    if len(signature) != 64:
        raise ValueError("Manifest signature must be a raw P-256 signature")
    public_key = serialization.load_pem_public_key(public_key_path.read_bytes())
    if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
        raise ValueError("Manifest public key must be P-256")
    der_signature = encode_dss_signature(
        int.from_bytes(signature[:32], "big"),
        int.from_bytes(signature[32:], "big"),
    )
    try:
        public_key.verify(
            der_signature,
            f"{parts[0]}.{parts[1]}".encode("ascii"),
            ec.ECDSA(hashes.SHA256()),
        )
    except InvalidSignature as error:
        raise ValueError("Manifest signature verification failed") from error
    return payload


def validate_manifest_identity(payload: dict) -> None:
    if (
        payload.get("iss") != "https://marstv.online"
        or payload.get("aud") != "tv.mars.app:direct-update"
        or payload.get("packageId") != "tv.mars.app"
        or payload.get("channel") != "direct-stable"
    ):
        raise ValueError("Release metadata targets the wrong issuer, audience, package or channel")


def utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def validate_client_schema(metadata: dict) -> None:
    if set(metadata) != MANIFEST_FIELDS:
        raise ValueError("Release metadata fields do not match the Android client schema")
    version_code = metadata["versionCode"]
    minimum_supported = metadata["minimumSupportedVersionCode"]
    if not isinstance(version_code, int) or not 1 <= version_code <= 2147483647:
        raise ValueError("versionCode is outside the Android client range")
    if not isinstance(minimum_supported, int) or not 1 <= minimum_supported <= version_code:
        raise ValueError("minimumSupportedVersionCode is invalid")
    if metadata["priority"] not in {"optional", "recommended", "critical"}:
        raise ValueError("Release priority is invalid")
    notes = metadata["releaseNotes"]
    if (
        not isinstance(notes, list)
        or len(notes) > 30
        or any(not isinstance(note, str) or utf16_length(note) > 500 for note in notes)
    ):
        raise ValueError("Release notes exceed the Android client limits")
    published_at = metadata["publishedAt"]
    if not isinstance(published_at, str):
        raise ValueError("publishedAt must be an ISO-8601 string")
    try:
        published = datetime.fromisoformat(published_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("publishedAt is not valid ISO-8601") from error
    if published.tzinfo is None or published > datetime.now(timezone.utc) + timedelta(seconds=300):
        raise ValueError("publishedAt is outside the Android client clock window")


def validate_release(
    apk: Path,
    manifest: Path,
    metadata: dict,
    public_key: Path,
    key_id: str,
    expected_certificate_sha256: str | None = None,
) -> dict:
    validate_client_schema(metadata)
    validate_manifest_identity(metadata)
    version_name = metadata["versionName"]
    if not isinstance(version_name, str) or not VERSION_PATTERN.fullmatch(version_name):
        raise ValueError("versionName is not safe for a release filename")
    artifact_file = f"MarsTV-{version_name}.apk"
    expected_url = f"https://marstv.online/downloads/{artifact_file}"
    if metadata["apkUrl"] != expected_url:
        raise ValueError("APK URL does not match the release version")
    actual_size = apk.stat().st_size
    actual_sha256 = file_sha256(apk)
    if not 1 <= actual_size <= MAX_APK_BYTES:
        raise ValueError("APK size is outside the Android client limits")
    if metadata["sizeBytes"] != actual_size:
        raise ValueError("APK size does not match signed release metadata")
    if not isinstance(metadata["sha256"], str) or not SHA256_PATTERN.fullmatch(metadata["sha256"]):
        raise ValueError("Release metadata contains an invalid SHA-256")
    if metadata["sha256"] != actual_sha256:
        raise ValueError("APK checksum does not match signed release metadata")
    certificate = metadata["signingCertificateSha256"]
    if not isinstance(certificate, str) or not SHA256_PATTERN.fullmatch(certificate):
        raise ValueError("Release metadata contains an invalid signing certificate digest")
    if expected_certificate_sha256 is not None:
        expected_certificate_sha256 = expected_certificate_sha256.lower()
        if not SHA256_PATTERN.fullmatch(expected_certificate_sha256):
            raise ValueError("Expected signing certificate digest is invalid")
        if certificate != expected_certificate_sha256:
            raise ValueError("APK signer does not match the pinned production certificate")
    token = manifest.read_text(encoding="ascii").strip()
    if verify_manifest(token, public_key, key_id) != metadata:
        raise ValueError("JWS payload does not match companion release metadata")
    return {
        "artifactFile": artifact_file,
        "versionName": version_name,
        "versionCode": metadata["versionCode"],
        "apkUrl": expected_url,
        "sha256": actual_sha256,
        "sizeBytes": actual_size,
    }


def create_bundle(
    apk: Path,
    manifest: Path,
    metadata_path: Path,
    output: Path,
    public_key: Path,
    key_id: str,
    expected_certificate_sha256: str,
    mapping: Path | None = None,
    native_symbols: Path | None = None,
) -> dict:
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if not isinstance(metadata, dict):
        raise ValueError("Release metadata must be an object")
    summary = validate_release(
        apk,
        manifest,
        metadata,
        public_key,
        key_id,
        expected_certificate_sha256,
    )
    if output.exists() and any(output.iterdir()):
        raise ValueError("Release bundle output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, output / summary["artifactFile"])
    shutil.copy2(manifest, output / "direct-stable.jws")
    bundle_metadata = {"artifactFile": summary["artifactFile"], "signedManifest": metadata}
    (output / "release-metadata.json").write_text(
        json.dumps(bundle_metadata, indent=2) + "\n",
        encoding="utf-8",
    )
    if mapping and mapping.is_file():
        shutil.copy2(mapping, output / "mapping.txt")
    if native_symbols and native_symbols.is_file():
        shutil.copy2(native_symbols, output / "native-debug-symbols.zip")
    return summary


def verify_bundle(
    bundle: Path,
    public_key: Path,
    key_id: str,
    expected_certificate_sha256: str | None = None,
) -> dict:
    wrapper = json.loads((bundle / "release-metadata.json").read_text(encoding="utf-8"))
    if not isinstance(wrapper, dict) or not isinstance(wrapper.get("signedManifest"), dict):
        raise ValueError("Release bundle metadata is invalid")
    artifact_file = wrapper.get("artifactFile")
    if not isinstance(artifact_file, str) or Path(artifact_file).name != artifact_file:
        raise ValueError("Release bundle APK filename is invalid")
    summary = validate_release(
        bundle / artifact_file,
        bundle / "direct-stable.jws",
        wrapper["signedManifest"],
        public_key,
        key_id,
        expected_certificate_sha256,
    )
    if summary["artifactFile"] != artifact_file:
        raise ValueError("Release bundle APK filename does not match its metadata")
    return summary


def print_summary(summary: dict, lines_only: bool) -> None:
    if lines_only:
        for key in ("artifactFile", "versionName", "versionCode", "apkUrl", "sha256"):
            print(summary[key])
        return
    print(
        f"Verified {summary['artifactFile']} version {summary['versionName']} "
        f"({summary['versionCode']}) SHA-256 {summary['sha256']}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("--apk", type=Path, required=True)
    create.add_argument("--manifest", type=Path, required=True)
    create.add_argument("--metadata", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--public-key", type=Path, required=True)
    create.add_argument("--key-id", required=True)
    create.add_argument("--expected-certificate-sha256", required=True)
    create.add_argument("--mapping", type=Path)
    create.add_argument("--native-symbols", type=Path)
    verify = commands.add_parser("verify")
    verify.add_argument("--bundle", type=Path, required=True)
    verify.add_argument("--public-key", type=Path, required=True)
    verify.add_argument("--key-id", required=True)
    verify.add_argument("--expected-certificate-sha256")
    verify.add_argument("--lines", action="store_true")
    inspect = commands.add_parser("inspect-manifest")
    inspect.add_argument("--manifest", type=Path, required=True)
    inspect.add_argument("--public-key", type=Path, required=True)
    inspect.add_argument("--key-id", required=True)
    args = parser.parse_args()
    if args.command == "create":
        summary = create_bundle(
            args.apk,
            args.manifest,
            args.metadata,
            args.output,
            args.public_key,
            args.key_id,
            args.expected_certificate_sha256,
            args.mapping,
            args.native_symbols,
        )
        print_summary(summary, False)
    elif args.command == "verify":
        print_summary(
            verify_bundle(
                args.bundle,
                args.public_key,
                args.key_id,
                args.expected_certificate_sha256,
            ),
            args.lines,
        )
    else:
        payload = verify_manifest(
            args.manifest.read_text(encoding="ascii").strip(),
            args.public_key,
            args.key_id,
        )
        validate_manifest_identity(payload)
        validate_client_schema(payload)
        required = ("versionCode", "signingCertificateSha256")
        if any(key not in payload for key in required):
            raise ValueError("Published manifest is missing release identity fields")
        print(payload["versionCode"])
        print(payload["signingCertificateSha256"])


if __name__ == "__main__":
    main()
