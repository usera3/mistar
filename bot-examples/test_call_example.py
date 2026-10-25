"""Local-only validation of the WebRTC example; no external APIs or hardware."""
import asyncio,hashlib,hmac,json,time,types,uuid
from aiohttp import web
from aiohttp.test_utils import TestClient,TestServer
from call_bot import Webhook,Tone,Pattern,relay_sdp


async def main():
    hook=Webhook(types.SimpleNamespace(), 'owned-local-example-fixture-secret')
    app=web.Application(client_max_size=900000,handler_args={'auto_decompress':False});app.router.add_post('/hook',hook.receive)
    async with TestClient(TestServer(app)) as client:
        async def post(p,expected=200,age=0,bad=False,encoding=None):
            raw=json.dumps(p,ensure_ascii=False).encode();ts=str(int(time.time())-age);eid=p.get('id',str(uuid.uuid4())) if isinstance(p,dict) else str(uuid.uuid4())
            signature='sha256='+hmac.new(hook.secret,ts.encode()+b'.'+eid.encode()+b'.'+raw,hashlib.sha256).hexdigest()
            headers={'X-Qingliao-Timestamp':ts,'X-Qingliao-Event-Id':eid,'X-Qingliao-Signature':'wrong' if bad else signature}
            if encoding:headers['Content-Encoding']=encoding
            r=await client.post('/hook',data=raw,headers=headers);assert r.status==expected;return await r.json()
        challenge={'id':str(uuid.uuid4()),'event':'webhook.verify','challenge':'本地测试'}
        assert (await post(challenge))['challenge']=='本地测试'
        await post(challenge,401,bad=True);await post(challenge,401,age=601);await post([],400)
        event={'id':str(uuid.uuid4()),'event':'call.ended','call_id':str(uuid.uuid4())}
        await post(event);await post(event);assert hook.queue.qsize()==1
        await post(event,415,encoding='br')
        raw=b'x'*900001;ts=str(int(time.time()));eid=str(uuid.uuid4())
        signature='sha256='+hmac.new(hook.secret,ts.encode()+b'.'+eid.encode()+b'.'+raw,hashlib.sha256).hexdigest()
        oversized=await client.post('/hook',data=raw,headers={'X-Qingliao-Timestamp':ts,'X-Qingliao-Event-Id':eid,'X-Qingliao-Signature':signature})
        assert oversized.status==413;await oversized.read()
    sdp='v=0\r\na=candidate:1 1 udp 1 10.0.0.1 1234 typ host\r\na=candidate:2 1 udp 1 172.18.0.77 2345 typ relay\r\na=end-of-candidates\r\n'
    cleaned=relay_sdp(sdp);assert 'typ host' not in cleaned and 'typ relay' in cleaned and 'end-of-candidates' not in cleaned
    tone=Tone();first=await tone.recv();second=await tone.recv();assert first.sample_rate==48000 and first.samples==960 and second.pts-first.pts==960;tone.stop()
    pattern=Pattern();frame=await pattern.recv();assert frame.width==640 and frame.height==360;pattern.stop()
    print('PASS call example: HMAC/challenge/stale/encoding/schema/dedup bounds, relay SDP filtering and valid audio/video frames')


if __name__=='__main__':asyncio.run(main())
