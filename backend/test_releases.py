"""Synthetic isolated release publication and authenticated WebSocket delivery."""
import asyncio
import copy
import hashlib
import json
import os
import signal
import tempfile
from pathlib import Path
from aiohttp.test_utils import TestClient, TestServer
from releases import validate, CERTIFICATE, ORIGIN


def manifest(code=26):
    return dict(schema=1, package='cn.qingliao.app', version_code=code, version='2.8.0', size=39276019,
                sha256='a'*64, published_at=1792800000000, min_sdk=26, certificate_sha256=CERTIFICATE,
                url=ORIGIN+f'xingyu-2.8.0-{code}.apk', notes=['合成更新内容'])


async def main():
    with tempfile.TemporaryDirectory(prefix='xingyu-release-') as tmp:
        path=Path(tmp)/'release.json'
        os.environ.update(QINGLIAO_DATA=str(Path(tmp)/'data'), QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0',QINGLIAO_PUBLIC_PLAZA='0',QINGLIAO_RELEASE_FILE=str(path))
        import server as s
        async with TestClient(TestServer(s.app)) as client:
            assert (await (await client.get('/api/app-release')).json())=={'release':None}
            assert (await client.post('/api/app-release',json=manifest())).status==405
            assert (await client.get('/api/me')).status==401
            sockets=[]
            for index in range(2):
                uid=str(70000000+index);token='synthetic-release-session-'+str(index)
                s.write('INSERT INTO users(id,username,nickname,salt,password,created) VALUES(?,?,?,?,?,?)',(uid,'qa_release_'+str(index),'合成用户','x','x',s.now()))
                s.write('INSERT INTO sessions(hash,user_id,expires) VALUES(?,?,?)',(hashlib.sha256(token.encode()).hexdigest(),uid,s.now()+60000))
                ws=await client.ws_connect('/ws');await ws.send_json({'token':token})
                assert (await ws.receive_json(timeout=3))['event']=='ready';sockets.append(ws)
            path.write_text(json.dumps(manifest()),encoding='utf-8')
            await s.releases.refresh()
            for ws in sockets: assert await ws.receive_json(timeout=3)=={'event':'app.update','version_code':26}
            assert (await (await client.get('/api/app-release')).json())['release']==manifest()
            await s.releases.refresh() # no repeated push for an unchanged release
            for ws in sockets:await ws.close()
            ws=await client.ws_connect('/ws');await ws.send_json({'token':'synthetic-release-session-0'})
            assert (await ws.receive_json(timeout=3))['event']=='ready'
            assert (await ws.receive_json(timeout=3))['version_code']==26 # offline catch-up
            invalid=[('version_code',True),('version_code',26.0),('version_code',0),('size',134217729),('size',False),('version','../../bad'),('url','https://evil.invalid/a.apk'),('url',ORIGIN+'xingyu-2.8.0-26.apk?token=x'),('certificate_sha256','0'*64),('sha256','z'*64),('package','other.app'),('notes',['x\nprivate']),('notes',['x'*181]),('notes',[]),('min_sdk',25)]
            for key,value in invalid:
                bad=copy.deepcopy(manifest());bad[key]=value
                try:validate(bad)
                except (ValueError,TypeError):pass
                else:raise AssertionError(key)
                path.write_text(json.dumps(bad),encoding='utf-8');assert not s.releases.load() and s.releases.current['version_code']==26
            path.write_text('x'*8193);assert not s.releases.load()
            path.write_text(json.dumps(manifest(25)));assert not s.releases.load()
            path.write_text(json.dumps(manifest(27)))
            if hasattr(signal,'SIGUSR1'):os.kill(os.getpid(),signal.SIGUSR1)
            else:s.releases.request_refresh()
            assert (await ws.receive_json(timeout=3))['version_code']==27
            await ws.close()
            assert not (await (await client.get('/api/health')).json())['moments_public']
        print('PASS release schema/bounds/URL, read-only public endpoint, 2 live sockets, reconnect catch-up, once-only push, monotonic reload, invalid-file retention, no polling, plaza closed')


if __name__=='__main__':asyncio.run(main())
