"""Publish a locally verified APK through the existing SSH administration path."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import zipfile

source, metadata_file, destination = map(Path, sys.argv[1:])
metadata = json.loads(metadata_file.read_text())
if not re.fullmatch(r'[A-Za-z0-9._-]{1,32}', metadata['version']):
    raise SystemExit('Invalid version')
if not isinstance(metadata['build'], int) or metadata['build'] <= 0:
    raise SystemExit('Invalid build')
if not 1024 * 1024 <= source.stat().st_size <= 400 * 1024 * 1024:
    raise SystemExit('Invalid APK size')
digest = hashlib.sha256(source.read_bytes()).hexdigest()
if digest != metadata['sha256'] or source.stat().st_size != metadata['size']:
    raise SystemExit('Staged APK differs from authenticated metadata')
with zipfile.ZipFile(source) as archive:
    if archive.testzip() or 'AndroidManifest.xml' not in archive.namelist():
        raise SystemExit('Invalid APK archive')
destination = destination.resolve(strict=True)
current_file = destination / 'latest.json'
current = json.loads(current_file.read_text()) if current_file.exists() else {}
if current.get('build', 0) > metadata['build']:
    raise SystemExit('Refusing to replace a newer build')
apk = destination / 'pebble-claw.apk'
if current.get('build') == metadata['build'] and apk.exists() and hashlib.sha256(apk.read_bytes()).hexdigest() == digest:
    print('Identical build already published')
    sys.exit(0)
owner = apk.stat() if apk.exists() else destination.stat()
with tempfile.NamedTemporaryFile(dir=destination, prefix='.verified-apk-', delete=False) as file:
    temporary = Path(file.name)
    with source.open('rb') as incoming:
        while chunk := incoming.read(1024 * 1024):
            file.write(chunk)
    file.flush(); os.fsync(file.fileno())
os.chmod(temporary, 0o644); os.chown(temporary, owner.st_uid, owner.st_gid)
os.replace(temporary, apk)
metadata.update(received=metadata['size'], uploaded=int(time.time()))
with tempfile.NamedTemporaryFile(dir=destination, prefix='.verified-metadata-', mode='w', delete=False) as file:
    temporary = Path(file.name)
    json.dump(metadata, file)
    file.flush(); os.fsync(file.fileno())
os.chmod(temporary, 0o644); os.chown(temporary, owner.st_uid, owner.st_gid)
os.replace(temporary, current_file)
print(f"Published build {metadata['build']}, {metadata['size']} bytes, SHA-256 verified")
