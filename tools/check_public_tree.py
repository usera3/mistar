"""Check tracked source paths; never reads a production configuration or secret."""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
p=subprocess.run(['git','ls-files','-z'],cwd=root,capture_output=True)
files=[root/n for n in p.stdout.decode().split('\0') if n] if p.returncode==0 and p.stdout else [p for p in root.rglob('*') if p.is_file() and not any(part in ('.git','.gradle','build','__pycache__') for part in p.relative_to(root).parts)]
for p in files:
    relative=p.relative_to(root).as_posix()
    assert not re.search(r'(^|/)(work|ios|data|uploads|logs|\.venv|venv)/|local\.properties$|\.(p12|jks|keystore|key|pem|qingkey|sqlite3|db|clixml|apk|aab)$|(^|/)\.env$',relative,re.I),relative
    data=p.read_bytes()
    assert not re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',data),relative
    assert not re.search(rb'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16})',data),relative
print('PASS source-only public tree;',len(files),'files; no private keys, tokens or runtime files')
