"""Root-only bootstrap. No HTTP endpoint can create or grant an administrator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time


def create_admin(database, username, credential_path):
    if not re.fullmatch(r'[a-z0-9_]{4,24}',username): raise ValueError('Invalid administrator username')
    target=Path(credential_path)
    if target.exists(): raise ValueError('Credential output already exists; refusing to overwrite')
    with sqlite3.connect(database) as db:
        db.row_factory=sqlite3.Row
        if db.execute('SELECT 1 FROM users WHERE username=?',(username,)).fetchone():
            raise ValueError('Username already exists; no existing account was promoted or reset')
        uid=str(secrets.randbelow(90000000)+10000000)
        while db.execute('SELECT 1 FROM users WHERE id=?',(uid,)).fetchone(): uid=str(secrets.randbelow(90000000)+10000000)
        password=secrets.token_urlsafe(24)
        salt=secrets.token_bytes(16)
        hashed=hashlib.scrypt(password.encode(),salt=salt,n=16384,r=8,p=5).hex()
        created=int(time.time()*1000)
        # Create the reviewable credential file before committing a newly usable account.
        fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w',encoding='utf-8') as f:
            f.write(json.dumps({'username':username,'id':uid,'initial_password':password,'must_change_password':True},ensure_ascii=False,indent=2)+'\n')
            f.flush();os.fsync(f.fileno())
        try:
            db.execute('INSERT INTO users(id,username,nickname,salt,password,created,password_kdf,email,email_key,created_ip) VALUES(?,?,?,?,?,?,2,?,?,?)',
                (uid,username,'弥星管理员',salt.hex(),hashed,created,'','','local-admin-bootstrap'))
            db.execute('INSERT INTO administrators(uid,must_change,created) VALUES(?,1,?)',(uid,created))
            db.execute('INSERT INTO admin_audit(actor,action,kind,target,reason,created,changed) VALUES(?,?,?,?,?,?,1)',
                ('root','bootstrap','user',uid,'本机服务器管理命令创建专用管理员',created))
            db.commit()
        except BaseException:
            target.unlink(missing_ok=True)
            raise
    return {'id':uid,'username':username,'must_change_password':True}


if __name__=='__main__':
    if not hasattr(os,'geteuid') or os.geteuid()!=0: raise SystemExit('Run this bootstrap as root on the server')
    parser=argparse.ArgumentParser()
    parser.add_argument('--database',default='/var/lib/qingliao/qingliao.sqlite3')
    parser.add_argument('--username',default='xingyu_admin')
    parser.add_argument('--credentials',required=True)
    args=parser.parse_args()
    print(json.dumps(create_admin(args.database,args.username,args.credentials),ensure_ascii=False))
