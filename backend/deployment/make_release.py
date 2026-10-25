"""Generate public metadata only after inspecting the actual signed Android APK."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from releases import validate, ORIGIN, CERTIFICATE


def main():
    p=argparse.ArgumentParser()
    for flag in ('apk','version','code','notes','out','aapt','java','apksigner'):p.add_argument('--'+flag,required=True)
    a=p.parse_args();apk=Path(a.apk);code=int(a.code)
    badging=subprocess.run([a.aapt,'dump','badging',str(apk)],check=True,capture_output=True).stdout.decode('utf-8',errors='replace')
    match=re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging,re.M)
    if not match or match.groups()!=('cn.qingliao.app',str(code),a.version):raise SystemExit('Actual APK identity/version differs')
    verification=subprocess.run([a.java,'-jar',a.apksigner,'verify','--verbose','--print-certs',str(apk)],check=True,capture_output=True).stdout.decode()
    if re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]+)',verification)!=[CERTIFICATE]:raise SystemExit('APK signer differs')
    digest=hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda:stream.read(65536),b''):digest.update(chunk)
    release=validate(dict(schema=1,package='cn.qingliao.app',version_code=code,version=a.version,size=apk.stat().st_size,
        sha256=digest.hexdigest(),published_at=int(time.time()*1000),min_sdk=26,certificate_sha256=CERTIFICATE,
        url=ORIGIN+f'xingyu-{a.version}-{code}.apk',notes=json.loads(Path(a.notes).read_text(encoding='utf-8-sig'))))
    Path(a.out).write_text(json.dumps(release,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('PASS actual package/version/signature; public release manifest generated:',a.version,code,release['size'])


if __name__=='__main__':main()
