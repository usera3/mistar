"""Standard-library Webhook example, Python 3.10+. Run behind your HTTPS reverse proxy.

QINGLIAO_BOT_TOKEN, QINGLIAO_WEBHOOK_SECRET must be provided through your secret
manager/environment. This example reliably queues events in local SQLite before
acknowledgement; slow application logic runs in one worker, outside the callback.
"""
import hashlib
from contextlib import closing
import hmac
import json
import os
import queue
import re
import sqlite3
import ssl
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

API='https://47.245.2.254'
TOKEN=os.environ.get('QINGLIAO_BOT_TOKEN','')
SECRET=os.environ.get('QINGLIAO_WEBHOOK_SECRET','').encode()
CA=Path(__file__).with_name('server_ca.crt')
DB=Path(os.environ.get('QINGLIAO_EVENT_DB','bot-events.sqlite3'))
WAKE=threading.Event()
LOCK=threading.Lock()


def api(path,body):
    data=json.dumps(body,ensure_ascii=False).encode()
    request=urllib.request.Request(API+path,data=data,method='POST',headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
    # No proxy env, no redirects that could forward the developer credential.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=CA)))
    with opener.open(request,timeout=15) as response:return json.load(response)


def handle_message(message):
    peer=message['sender'];reply='reply-'+message['id']
    text=message.get('text','')
    if message['type']=='text' and text.strip()=='/card':
        api('/api/bot/messages',{'recipient':peer,'client_id':reply,'type':'html','text':"<div style='padding:18px;background:#eff2ff;border-radius:16px'><h3>弥星机器人</h3><p>这是一张直接通过 API 发送的 HTML 卡片。</p></div>"})
    else:
        answer=('收到：'+text[:1000]) if message['type']=='text' else '已收到你的'+{'image':'图片','voice':'语音','file':'文件','html':'HTML 卡片'}.get(message['type'],'消息')
        api('/api/bot/messages',{'recipient':peer,'client_id':reply,'type':'text','text':answer})


def worker():
    while True:
        with LOCK,closing(sqlite3.connect(DB)) as db,db:
            item=db.execute('SELECT id,body,attempts FROM events WHERE done=0 AND due<=? ORDER BY created LIMIT 1',(time.time(),)).fetchone()
        if not item:
            WAKE.wait(2);WAKE.clear();continue
        try:
            handle_message(json.loads(item[1])['message'])
            with LOCK,closing(sqlite3.connect(DB)) as db,db:db.execute('UPDATE events SET done=1,body=? WHERE id=?',('{}',item[0]))
        except Exception:
            # Avoid logging decrypted bodies, credentials, URLs or private exception content.
            attempts=item[2]+1
            with LOCK,closing(sqlite3.connect(DB)) as db,db:db.execute('UPDATE events SET attempts=?,due=?,done=? WHERE id=?',(attempts,time.time()+min(300,2**min(attempts,8)),2 if attempts>=8 else 0,item[0]))
            print('Message handler failed; event retained for inspection.',flush=True)


class Callback(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.0'
    def log_message(self,*args):pass
    def reply(self,status,payload):
        data=json.dumps(payload).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def do_POST(self):
        self.connection.settimeout(5)
        if self.path!='/qingliao/webhook':self.reply(404,{});return
        try:
            size=int(self.headers.get('Content-Length','0'))
            if not 1<=size<=900000 or self.headers.get('Transfer-Encoding'):self.reply(413,{});return
            raw=self.rfile.read(size)
            if len(raw)!=size:self.reply(400,{});return
            ts=self.headers.get('X-Qingliao-Timestamp','');eid=self.headers.get('X-Qingliao-Event-Id','')
            if not re.fullmatch(r'[0-9]{10,11}',ts) or abs(time.time()-int(ts))>300 or not re.fullmatch(r'[a-f0-9-]{36}',eid):self.reply(401,{});return
            expected='sha256='+hmac.new(SECRET,ts.encode()+b'.'+eid.encode()+b'.'+raw,hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected,self.headers.get('X-Qingliao-Signature','')):self.reply(401,{});return
            p=json.loads(raw)
            if p['id']!=eid:self.reply(400,{});return
            if p['event']=='webhook.verify':self.reply(200,{'challenge':p['challenge']});return
            if p['event']!='message.created':self.reply(400,{});return
            with LOCK,closing(sqlite3.connect(DB)) as db,db:
                db.execute('DELETE FROM events WHERE done=1 AND created<?',(time.time()-7*86400,))
                if db.execute('SELECT 1 FROM events WHERE id=?',(eid,)).fetchone():self.reply(200,{'ok':True});return
                if db.execute('SELECT count(*) FROM events').fetchone()[0]>=1000:self.reply(503,{});return
                db.execute('INSERT OR IGNORE INTO events VALUES(?,?,?,0,0,0)',(eid,raw.decode(),time.time()))
            WAKE.set();self.reply(200,{'ok':True})
        except (ValueError,KeyError,TypeError,TimeoutError,sqlite3.Error):self.reply(400,{})


def main():
    if not re.fullmatch(r'qlb_[A-Za-z0-9_-]{43}',TOKEN) or len(SECRET)<32:raise SystemExit('Configure bot token and Webhook secret in the environment first.')
    os.umask(0o077);DB.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    with closing(sqlite3.connect(DB)) as db,db:db.execute('CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,body TEXT NOT NULL,created REAL NOT NULL,attempts INTEGER NOT NULL,due REAL NOT NULL,done INTEGER NOT NULL)')
    threading.Thread(target=worker,daemon=True).start()
    server=ThreadingHTTPServer(('127.0.0.1',int(os.environ.get('PORT','9080'))),Callback)
    print('Webhook receiver ready on localhost; expose /qingliao/webhook through your HTTPS reverse proxy.',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()


if __name__=='__main__':main()
