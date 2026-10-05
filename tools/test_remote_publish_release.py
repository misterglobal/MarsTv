import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipIf(os.name == "nt", "Remote publication helper is exercised on the Linux CI runner")
class RemotePublishReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "marstvonline"
        self.downloads = self.root / "marstv-backend/public/downloads"
        self.releases = self.root / "marstv-backend/storage/releases"
        self.environment = self.root / "marstv-backend/.env"
        self.downloads.mkdir(parents=True)
        self.releases.mkdir(parents=True)
        self.environment.write_text("UNCHANGED=value\nMARSTV_APK_VERSION=old\n")
        self.final_manifest = self.releases / "direct-stable.jws"
        self.final_manifest.write_text("old-manifest")
        source = Path(__file__).parents[1] / "scripts/remote-publish-release.sh"
        self.script = Path(self.temporary.name) / "remote-publish-release.sh"
        self.script.write_text(
            source.read_text().replace("/home2/hpgeqkld/marstvonline", str(self.root)),
        )
        self.run_id = "12345"
        self.version = "0.3.3"
        self.apk_url = f"https://marstv.online/downloads/MarsTV-{self.version}.apk"
        self.staging = self.releases / f".production-release-{self.run_id}"
        self.temporary_apk = self.staging / f"MarsTV-{self.version}.apk"
        self.final_apk = self.downloads / f"MarsTV-{self.version}.apk"
        self.temporary_manifest = self.staging / "direct-stable.jws"

    def tearDown(self):
        self.temporary.cleanup()

    def run_helper(self, mode: str, sha256: str):
        subprocess.run(
            [
                "bash",
                str(self.script),
                mode,
                self.run_id,
                str(self.temporary_apk),
                str(self.final_apk),
                str(self.temporary_manifest),
                str(self.final_manifest),
                str(self.environment),
                self.apk_url,
                self.version,
                sha256,
            ],
            check=True,
        )

    def stage_files(self):
        content = b"signed-apk"
        sha256 = hashlib.sha256(content).hexdigest()
        self.run_helper("stage", sha256)
        self.temporary_apk.write_bytes(content)
        self.temporary_manifest.write_text("new-manifest")
        return sha256

    def test_rollback_restores_previous_release(self):
        original_environment = self.environment.read_text()
        sha256 = self.stage_files()
        self.run_helper("publish", sha256)
        self.assertEqual("new-manifest", self.final_manifest.read_text())
        self.assertIn("MARSTV_APK_VERSION=0.3.3", self.environment.read_text())
        self.run_helper("rollback", sha256)
        self.assertEqual("old-manifest", self.final_manifest.read_text())
        self.assertEqual(original_environment, self.environment.read_text())
        self.assertFalse(self.final_apk.exists())

    def test_confirmation_keeps_release_and_removes_backups(self):
        sha256 = self.stage_files()
        self.run_helper("publish", sha256)
        self.run_helper("confirm", sha256)
        self.assertEqual(b"signed-apk", self.final_apk.read_bytes())
        self.assertEqual("new-manifest", self.final_manifest.read_text())
        self.assertFalse(Path(f"{self.environment}.release-{self.run_id}.bak").exists())
        self.assertFalse(Path(f"{self.final_manifest}.release-{self.run_id}.bak").exists())


if __name__ == "__main__":
    unittest.main()
