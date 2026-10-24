import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

spec = importlib.util.spec_from_file_location('private_build', Path(__file__).resolve().parents[1] / 'tools/private-build.py')
transfer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transfer)

class PrivateBuildTests(unittest.TestCase):
    def test_roundtrip_and_authenticated_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            private, public, source, output = [root / name for name in ('private.pem','public.pem','source.apk','output.apk')]
            private.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            public.write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
            source.write_bytes(b'private APK fixture' * 100)
            transfer.encrypt(source, public, root/'encrypted', 42, '1.0')
            transfer.decrypt(root/'encrypted', private, output)
            self.assertEqual(source.read_bytes(), output.read_bytes())
            envelope = root/'encrypted/envelope.json'
            meta = json.loads(envelope.read_text()); meta['metadata']['build'] = 43
            envelope.write_text(json.dumps(meta))
            with self.assertRaises(InvalidTag):
                transfer.decrypt(root/'encrypted', private, output)
