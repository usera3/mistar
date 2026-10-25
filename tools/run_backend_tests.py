"""Run each suite in a separate process; all suites use synthetic data/temp DBs."""
import os
from pathlib import Path
import subprocess
import sys

root=Path(__file__).resolve().parents[1]
tests=['releases','moderation','moderation_media','plaza_closed','backend','security','abuse','avatar','presence','html','moments','momentmedia','boundaries','recovery','social','storage','syncstate','callstate','bots','botcalls']
failed=[]
for name in tests:
    # Suites that exercise public/friend ACLs need an explicitly enabled isolated
    # test plaza. test_plaza_closed sets its own closed configuration.
    p=subprocess.run([sys.executable,'-u',f'test_{name}.py'],cwd=root/'backend',env=dict(os.environ,QINGLIAO_PUBLIC_PLAZA='1'),capture_output=True,text=True,timeout=180)
    print(('PASS ' if p.returncode==0 else 'FAIL ')+name,flush=True)
    if p.returncode:
        failed.append(name);print((p.stdout+p.stderr)[-6000:])
if failed:raise SystemExit('Failed suites: '+', '.join(failed))
subprocess.run([sys.executable,'test_example.py'],cwd=root/'bot-examples',check=True)
print('PASS all 20 backend suites and isolated bot example')
