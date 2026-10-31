"""Run each suite in a separate process; all suites use synthetic data/temp DBs."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

root=Path(__file__).resolve().parents[1]
tests=['password_reset','membership','releases','moderation','moderation_media','plaza_closed','backend','security','abuse','avatar','presence','html','moments','momentmedia','boundaries','recovery','social','storage','syncstate','callstate','bots','botcalls']
failed=[]
for name in tests:
    # Suites that exercise public/friend ACLs need an explicitly enabled isolated
    # test plaza. test_plaza_closed sets its own closed configuration.
    with tempfile.TemporaryDirectory(prefix='mistar-test-environment-') as isolated:
        env={k:v for k,v in os.environ.items() if not k.startswith(('MISTAR_PAY_','SMTP_')) and k not in ('OTP_SECRET','TURN_SECRET','DATABASE_URL')}
        env.update(QINGLIAO_PUBLIC_PLAZA='1',QINGLIAO_RELEASE_FILE=str(Path(isolated)/'release.json'),MISTAR_PAY_ENABLED='0')
        p=subprocess.run([sys.executable,'-u',f'test_{name}.py'],cwd=root/'backend',env=env,capture_output=True,text=True,timeout=180)
    print(('PASS ' if p.returncode==0 else 'FAIL ')+name,flush=True)
    if p.returncode:
        failed.append(name);print((p.stdout+p.stderr)[-6000:])
if failed:raise SystemExit('Failed suites: '+', '.join(failed))
subprocess.run([sys.executable,'test_example.py'],cwd=root/'bot-examples',check=True)
print('PASS all 22 backend suites and isolated bot example')
