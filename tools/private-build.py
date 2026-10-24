"""Authenticated APK transfer: public encryption key in Git, private key local."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

LABEL = b'pebble-private-build-v1'

def oaep():
    return padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=LABEL)

def encrypt(source, public, destination, build, version):
    data = Path(source).read_bytes()
    meta = {'build': build, 'version': version, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    aad = json.dumps(meta, sort_keys=True, separators=(',', ':')).encode()
    key, nonce = AESGCM.generate_key(256), os.urandom(12)
    recipient = serialization.load_pem_public_key(Path(public).read_bytes())
    envelope = {'metadata': meta, 'key': base64.b64encode(recipient.encrypt(key, oaep())).decode(),
                'nonce': base64.b64encode(nonce).decode()}
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    (destination / 'apk.encrypted').write_bytes(AESGCM(key).encrypt(nonce, data, aad))
    (destination / 'envelope.json').write_text(json.dumps(envelope, indent=2) + '\n')

def decrypt(source, private, destination):
    source = Path(source)
    envelope = json.loads((source / 'envelope.json').read_text())
    meta = envelope['metadata']
    aad = json.dumps(meta, sort_keys=True, separators=(',', ':')).encode()
    recipient = serialization.load_pem_private_key(Path(private).read_bytes(), password=None)
    key = recipient.decrypt(base64.b64decode(envelope['key']), oaep())
    data = AESGCM(key).decrypt(base64.b64decode(envelope['nonce']), (source / 'apk.encrypted').read_bytes(), aad)
    if len(data) != meta['size'] or hashlib.sha256(data).hexdigest() != meta['sha256']:
        raise ValueError('APK integrity mismatch')
    Path(destination).write_bytes(data)
    return meta

def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    keygen = commands.add_parser('keygen')
    keygen.add_argument('private', type=Path)
    keygen.add_argument('public', type=Path)
    enc = commands.add_parser('encrypt')
    enc.add_argument('source'); enc.add_argument('public'); enc.add_argument('destination')
    enc.add_argument('build', type=int); enc.add_argument('version')
    dec = commands.add_parser('decrypt')
    dec.add_argument('source'); dec.add_argument('private'); dec.add_argument('destination')
    args = parser.parse_args()
    if args.command == 'keygen':
        if args.private.exists():
            key = serialization.load_pem_private_key(args.private.read_bytes(), password=None)
        else:
            key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            args.private.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        args.public.write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo))
        print('Transfer key ready; private key is not part of the repository')
    elif args.command == 'encrypt':
        encrypt(args.source, args.public, args.destination, args.build, args.version)
        print('Encrypted APK artifact ready')
    else:
        print(json.dumps(decrypt(args.source, args.private, args.destination)))

if __name__ == '__main__':
    main()
