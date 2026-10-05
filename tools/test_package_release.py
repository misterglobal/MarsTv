import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

from package_release import create_bundle, verify_bundle


def encode(value: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()


class PackageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.private_key = ec.generate_private_key(ec.SECP256R1())

    def write_public_key(self, root: Path) -> Path:
        path = root / "update-public-key.pem"
        path.write_bytes(
            self.private_key.public_key().public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        )
        return path

    def sign(self, metadata: dict) -> str:
        header = {"alg": "ES256", "kid": "release-2026-01", "typ": "marstv-update+jws"}
        content = f"{encode(header)}.{encode(metadata)}"
        der = self.private_key.sign(content.encode("ascii"), ec.ECDSA(hashes.SHA256()))
        r, s = decode_dss_signature(der)
        signature = base64.urlsafe_b64encode(r.to_bytes(32, "big") + s.to_bytes(32, "big")).rstrip(b"=").decode()
        return f"{content}.{signature}"

    def release_files(self, root: Path):
        apk = root / "app-release.apk"
        apk.write_bytes(b"signed-apk-fixture")
        metadata = {
            "iss": "https://marstv.online",
            "aud": "tv.mars.app:direct-update",
            "packageId": "tv.mars.app",
            "channel": "direct-stable",
            "versionCode": 6,
            "versionName": "0.3.3",
            "minimumSupportedVersionCode": 1,
            "priority": "recommended",
            "apkUrl": "https://marstv.online/downloads/MarsTV-0.3.3.apk",
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "sizeBytes": apk.stat().st_size,
            "signingCertificateSha256": "a" * 64,
            "releaseNotes": ["Safe production release"],
            "publishedAt": "2026-09-10T00:00:00Z",
        }
        metadata_path = root / "direct-stable.json"
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
        manifest = root / "direct-stable.jws"
        manifest.write_text(self.sign(metadata), encoding="ascii")
        return apk, manifest, metadata_path, self.write_public_key(root)

    def test_create_and_verify_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, manifest, metadata, public_key = self.release_files(root)
            output = root / "bundle"
            summary = create_bundle(apk, manifest, metadata, output, public_key, "release-2026-01", "a" * 64)
            self.assertEqual("MarsTV-0.3.3.apk", summary["artifactFile"])
            self.assertEqual(summary, verify_bundle(output, public_key, "release-2026-01", "a" * 64))

    def test_rejects_tampered_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, manifest, metadata, public_key = self.release_files(root)
            output = root / "bundle"
            create_bundle(apk, manifest, metadata, output, public_key, "release-2026-01", "a" * 64)
            (output / "MarsTV-0.3.3.apk").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "size does not match"):
                verify_bundle(output, public_key, "release-2026-01", "a" * 64)

    def test_rejects_unsafe_version_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, manifest, metadata_path, public_key = self.release_files(root)
            metadata = json.loads(metadata_path.read_text())
            metadata["versionName"] = "../escape"
            metadata_path.write_text(json.dumps(metadata))
            manifest.write_text(self.sign(metadata), encoding="ascii")
            with self.assertRaisesRegex(ValueError, "safe"):
                create_bundle(apk, manifest, metadata_path, root / "bundle", public_key, "release-2026-01", "a" * 64)

    def test_rejects_wrong_manifest_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, manifest, metadata, _ = self.release_files(root)
            other_key = ec.generate_private_key(ec.SECP256R1())
            public_key = root / "wrong-public-key.pem"
            public_key.write_bytes(
                other_key.public_key().public_bytes(
                    serialization.Encoding.PEM,
                    serialization.PublicFormat.SubjectPublicKeyInfo,
                )
            )
            with self.assertRaisesRegex(ValueError, "signature verification failed"):
                create_bundle(apk, manifest, metadata, root / "bundle", public_key, "release-2026-01", "a" * 64)

    def test_rejects_release_note_over_client_utf16_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, manifest, metadata_path, public_key = self.release_files(root)
            metadata = json.loads(metadata_path.read_text())
            metadata["releaseNotes"] = ["😀" * 251]
            metadata_path.write_text(json.dumps(metadata))
            manifest.write_text(self.sign(metadata), encoding="ascii")
            with self.assertRaisesRegex(ValueError, "client limits"):
                create_bundle(apk, manifest, metadata_path, root / "bundle", public_key, "release-2026-01", "a" * 64)


if __name__ == "__main__":
    unittest.main()
