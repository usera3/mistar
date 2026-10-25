"""Root-only filesystem publication; this is never an HTTP endpoint.

Usage: python3 install_release.py manifest.json package.apk /var/www/xingyu/releases
Use --feed manifest.json /etc/qingliao/release.json for the Japan notification feed.
Publication order: immutable APK, latest APK alias, public manifest, Japan feed, SIGUSR1.
"""
import hashlib
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from releases import validate


def atomic(path, data):
    fd, temporary=tempfile.mkstemp(prefix='.publish-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
        os.chmod(temporary,0o644);os.replace(temporary,path)
    finally:
        if os.path.exists(temporary):os.unlink(temporary)


def main(args):
    if os.geteuid()!=0:raise SystemExit('Use the host administrator')
    feed=args[0]=='--feed'
    raw=Path(args[1] if feed else args[0]).read_bytes()
    if len(raw)>8192:raise SystemExit('Manifest too large')
    release=validate(json.loads(raw))
    dest=Path(args[2]) if feed else Path(args[2])/'latest.json'
    if dest.exists():
        old=validate(json.loads(dest.read_bytes()))
        if release['version_code']<old['version_code'] or release['version_code']==old['version_code'] and release!=old:raise SystemExit('Refusing release rollback or replacement')
    if not feed:
        source=Path(args[1]);digest=hashlib.sha256()
        with source.open('rb') as stream:
            for chunk in iter(lambda:stream.read(65536),b''):digest.update(chunk)
        if source.stat().st_size!=release['size'] or digest.hexdigest()!=release['sha256']:raise SystemExit('APK size/digest mismatch')
        dest.parent.mkdir(parents=True,exist_ok=True)
        apk=dest.parent/release['url'].rsplit('/',1)[1]
        if apk.exists():
            if hashlib.sha256(apk.read_bytes()).hexdigest()!=release['sha256']:raise SystemExit('Immutable APK already exists with different content')
        else:
            temporary=dest.parent/'.package.part'
            try:shutil.copyfile(source,temporary);os.chmod(temporary,0o644);os.replace(temporary,apk)
            finally:temporary.unlink(missing_ok=True)
        alias=dest.parent/'.latest.next';alias.unlink(missing_ok=True);alias.symlink_to(apk.name);os.replace(alias,dest.parent/'latest.apk')
    atomic(dest,(json.dumps(release,ensure_ascii=False,indent=2)+'\n').encode())
    print('PASS published code',release['version_code'],'feed' if feed else 'public APK + manifest')


if __name__=='__main__':main(sys.argv[1:])
