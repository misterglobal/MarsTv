"""Create a separate P-256 release key or sign a verified Android APK's update manifest.

Requires Python cryptography and the Android SDK build tools. Never uploads private keys.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature


def create_key(private: Path, public: Path):
    if private.resolve() == public.resolve() or private.exists() or public.exists():
        raise ValueError('Refusing to overwrite an existing signing key or public key')
    key = ec.generate_private_key(ec.SECP256R1())
    private.parent.mkdir(parents=True, exist_ok=True)
    public.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(private, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    public.write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))


def sign(payload: dict, private: Path, kid: str) -> str:
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', kid):
        raise ValueError('Invalid signing key ID')
    key = serialization.load_pem_private_key(private.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('Manifest key must be P-256')
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode()).rstrip(b'=').decode()
    content = encode({'alg': 'ES256', 'kid': kid, 'typ': 'marstv-update+jws'}) + '.' + encode(payload)
    r, s = decode_dss_signature(key.sign(content.encode('ascii'), ec.ECDSA(hashes.SHA256())))
    signature = base64.urlsafe_b64encode(r.to_bytes(32, 'big') + s.to_bytes(32, 'big')).rstrip(b'=').decode()
    token = content + '.' + signature
    if len(token) > 32768:
        raise ValueError('Manifest exceeds client size limit')
    return token


def inspect_apk(apk: Path, build_tools: Path, java: str):
    signer = build_tools / 'lib/apksigner.jar'
    result = subprocess.run([java, '-jar', str(signer), 'verify', '--print-certs', str(apk)], check=True, capture_output=True, text=True)
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', result.stdout)
    if len(certificates) != 1:
        raise ValueError('Expected exactly one APK signing certificate')
    aapt = build_tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
    metadata = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
    if not match or match[1] != 'tv.mars.app':
        raise ValueError('APK must be the MarsTV package')
    if 'application-debuggable' in metadata:
        raise ValueError('Refusing to publish a debuggable APK')
    return int(match[2]), match[3], certificates[0].lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    init = commands.add_parser('init-key')
    init.add_argument('--private-key', type=Path, required=True)
    init.add_argument('--public-key', type=Path, required=True)
    release = commands.add_parser('sign')
    release.add_argument('--apk', type=Path, required=True)
    release.add_argument('--build-tools', type=Path, required=True)
    release.add_argument('--java', default='java')
    release.add_argument('--private-key', type=Path, required=True)
    release.add_argument('--kid', default='release-2026-01')
    release.add_argument('--url', required=True)
    release.add_argument('--notes', type=Path, required=True, help='UTF-8 text, one release note per line')
    release.add_argument('--previous-version-code', type=int, required=True)
    release.add_argument('--minimum-supported-version-code', type=int, default=1)
    release.add_argument('--priority', choices=['optional', 'recommended', 'critical'], default='recommended')
    release.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'init-key':
        create_key(args.private_key, args.public_key)
        print('Created separate release key pair. Back up the private key securely; never upload it to the website.')
        return
    inputs = {path.resolve() for path in (args.apk, args.private_key, args.notes)}
    outputs = {path.resolve() for path in (args.output, args.output.with_suffix('.json'), args.output.with_suffix(args.output.suffix + '.tmp'))}
    if inputs & outputs or len(outputs) != 3:
        raise ValueError('Output paths must not overwrite the APK, signing key or release notes; use a .jws output filename')
    if not re.fullmatch(r'https://marstv\.online/downloads/[A-Za-z0-9][A-Za-z0-9._-]*\.apk', args.url):
        raise ValueError('APK URL must be an HTTPS file under marstv.online/downloads/')
    version, name, certificate = inspect_apk(args.apk, args.build_tools, args.java)
    if not 0 <= args.previous_version_code < version <= 2147483647:
        raise ValueError('versionCode must exceed the previous published version')
    if not 1 <= args.minimum_supported_version_code <= version:
        raise ValueError('Invalid minimum supported version')
    size = args.apk.stat().st_size
    if not 0 < size <= 250 * 1024 * 1024:
        raise ValueError('APK exceeds supported size')
    notes = [line.strip() for line in args.notes.read_text(encoding='utf-8').splitlines() if line.strip()]
    if len(notes) > 30 or any(len(note) > 500 for note in notes) or not 0 < len(name) <= 80:
        raise ValueError('Release notes or version name exceed client limits')
    with args.apk.open('rb') as apk_file:
        checksum = hashlib.file_digest(apk_file, 'sha256').hexdigest()
    payload = dict(iss='https://marstv.online', aud='tv.mars.app:direct-update', packageId='tv.mars.app',
                   signingCertificateSha256=certificate, channel='direct-stable', versionCode=version, versionName=name,
                   minimumSupportedVersionCode=args.minimum_supported_version_code, priority=args.priority,
                   apkUrl=args.url, sha256=checksum, sizeBytes=size, releaseNotes=notes,
                   publishedAt=datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z'))
    token = sign(payload, args.private_key, args.kid)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + '.tmp')
    temporary.write_text(token, encoding='ascii')
    temporary.replace(args.output)
    args.output.with_suffix('.json').write_text(json.dumps(payload, indent=2), encoding='utf-8')
    print(f'Verified APK and signed manifest: {args.output}\nVersion: {name} ({version})\nSHA-256: {checksum}\nCertificate: {certificate}')


if __name__ == '__main__':
    main()
