import base64
import json
from pathlib import Path
import tempfile
import unittest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
from release_manifest import create_key, sign


class ReleaseManifestTest(unittest.TestCase):
    def test_jose_signature_verifies_exact_payload_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            private, public = Path(directory) / 'private.pem', Path(directory) / 'public.pem'
            create_key(private, public)
            token = sign({'releaseNotes': ['Télévision update'], 'versionCode': 3}, private, 'release-test')
            header, payload, signature = token.split('.')
            decode = lambda value: base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
            self.assertEqual({'alg': 'ES256', 'kid': 'release-test', 'typ': 'marstv-update+jws'}, json.loads(decode(header)))
            self.assertEqual('Télévision update', json.loads(decode(payload))['releaseNotes'][0])
            raw = decode(signature)
            self.assertEqual(64, len(raw))
            der = encode_dss_signature(int.from_bytes(raw[:32], 'big'), int.from_bytes(raw[32:], 'big'))
            key = serialization.load_pem_public_key(public.read_bytes())
            key.verify(der, (header + '.' + payload).encode(), ec.ECDSA(hashes.SHA256()))
            with self.assertRaises(Exception):
                key.verify(der, (header + '.' + payload + 'a').encode(), ec.ECDSA(hashes.SHA256()))

    def test_key_creation_never_overwrites_existing_key(self):
        with tempfile.TemporaryDirectory() as directory:
            private, public = Path(directory) / 'private.pem', Path(directory) / 'public.pem'
            create_key(private, public)
            original = private.read_bytes()
            with self.assertRaises(ValueError): create_key(private, public)
            self.assertEqual(original, private.read_bytes())


if __name__ == '__main__': unittest.main()
