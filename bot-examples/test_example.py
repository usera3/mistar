"""Local signature/replay/dedup checks for webhook_bot.py; never calls Qingliao."""
import hashlib
from contextlib import closing
import hmac
import http.client
import json
import sqlite3
import tempfile
import threading
import time
import uuid
from pathlib import Path
import webhook_bot as example


def main():
    with tempfile.TemporaryDirectory() as folder:
        example.DB=Path(folder)/'events.sqlite3';example.SECRET=b'local-fixture-secret-not-a-real-credential'
        with closing(sqlite3.connect(example.DB)) as db,db:db.execute('CREATE TABLE events(id TEXT PRIMARY KEY,body TEXT,created REAL,attempts INTEGER,due REAL,done INTEGER)')
        server=example.ThreadingHTTPServer(('127.0.0.1',0),example.Callback)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        def post(p, *, expired=False,bad=False):
            raw=json.dumps(p).encode();ts=str(int(time.time())-(600 if expired else 0));eid=p['id']
            sig='sha256='+hmac.new(example.SECRET,ts.encode()+b'.'+eid.encode()+b'.'+raw,hashlib.sha256).hexdigest()
            conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
            conn.request('POST','/qingliao/webhook',body=raw,headers={'Content-Type':'application/json','X-Qingliao-Timestamp':ts,'X-Qingliao-Event-Id':eid,'X-Qingliao-Signature':'wrong' if bad else sig})
            response=conn.getresponse();body=response.read();status=response.status;conn.close();return status,json.loads(body)
        try:
            verify={'id':str(uuid.uuid4()),'event':'webhook.verify','challenge':'owned-fixture'}
            assert post(verify)==(200,{'challenge':'owned-fixture'})
            assert post(verify,bad=True)[0]==401 and post(verify,expired=True)[0]==401
            event={'id':str(uuid.uuid4()),'event':'message.created','message':{'id':str(uuid.uuid4()),'type':'text','text':'test','sender':'12345678'}}
            assert post(event)[0]==200 and post(event)[0]==200
            with closing(sqlite3.connect(example.DB)) as db,db:assert db.execute('SELECT count(*) FROM events').fetchone()[0]==1
            print('PASS example: raw-body HMAC, challenge, stale/invalid signature rejection and durable duplicate suppression')
        finally:server.shutdown();server.server_close();thread.join()


if __name__=='__main__':main()
