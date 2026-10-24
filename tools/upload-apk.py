"""Resumable relay upload; recognize a verified copy published over SSH."""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

path, url, version, build = sys.argv[1:]
build = int(build)
total = os.path.getsize(path)
digest = hashlib.sha256(open(path, 'rb').read()).hexdigest()
upload_token, read_token = os.environ['UP'], os.environ.get('READ_TOKEN', '')

def already_published():
    if not read_token:
        return False
    request = urllib.request.Request(url.rsplit('/', 1)[0] + '/latest',
        headers={'Authorization': 'Bearer ' + read_token})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            meta = json.load(response)
        return (meta.get('build') == build and meta.get('version') == version
                and meta.get('size') == total and meta.get('sha256') == digest)
    except Exception:
        return False

offset, started = 0, time.time()
print(f'Uploading {total / 1048576:.1f} MB to the private relay', flush=True)
with open(path, 'rb') as stream:
    while offset < total:
        for attempt in range(1, 9):
            if already_published():
                print('The identical verified build is already published', flush=True)
                sys.exit(0)
            stream.seek(offset)
            body = stream.read(256 << 10)
            query = f'?version={version}&build={build}&offset={offset}&total={total}'
            request = urllib.request.Request(url + query, data=body, method='PUT', headers={
                'Authorization': 'Bearer ' + upload_token, 'Content-Type': 'application/octet-stream'})
            try:
                with urllib.request.urlopen(request, timeout=180) as response:
                    received = json.load(response)['received']
                if received <= offset or received > total:
                    raise ValueError('Invalid upload progress')
                offset = received
                print(f'{offset / 1048576:.1f}/{total / 1048576:.1f} MB', flush=True)
                break
            except urllib.error.HTTPError as error:
                if error.code == 409:
                    offset = json.load(error)['received']
                    break
                print(f'Upload retry {attempt}: HTTP {error.code}', flush=True)
            except Exception as error:
                print(f'Upload retry {attempt}: {type(error).__name__}', flush=True)
            time.sleep(min(30, attempt * 3))
        else:
            sys.exit('Upload failed; encrypted artifact is available for private recovery')
print(f'Published in {time.time()-started:.0f}s', flush=True)
