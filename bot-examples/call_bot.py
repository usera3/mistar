"""Webhook + REST WebRTC bot example. Media runs on the developer's host.

Install call-requirements.txt, configure the same bot token/Webhook secret and
HTTPS reverse proxy as webhook_bot.py. Opt into calls in the app. This example
auto-accepts calls addressed to the BOT, receives media without saving it, and
sends a soft test tone plus a moving test picture. It never answers for a human.
Replace Tone/Pattern and consume() with your own realtime media application.
"""
import argparse
import asyncio
import hashlib
import hmac
import json
import math
import os
import re
import signal as os_signal
import ssl
import time
import uuid
from fractions import Fraction
from pathlib import Path

import numpy as np
from aiohttp import web,ClientSession,ClientTimeout
from aiortc import RTCPeerConnection,RTCConfiguration,RTCIceServer,RTCSessionDescription,RTCBundlePolicy,AudioStreamTrack,VideoStreamTrack
from aiortc.sdp import candidate_from_sdp
from av import AudioFrame,VideoFrame


def relay_sdp(sdp):
    # This deployment's relay is intentionally reachable via TURN/TCP 443.
    # Do not ask it to open channels to the peer's private host candidates.
    return '\r\n'.join(line for line in sdp.splitlines() if line!='a=end-of-candidates' and
        (not line.startswith('a=candidate:') or ' typ relay' in line))+'\r\n'


class Tone(AudioStreamTrack):
    def __init__(self):super().__init__();self.pts=0;self.origin=None
    async def recv(self):
        if self.origin is None:self.origin=time.monotonic()
        await asyncio.sleep(max(0,self.origin+self.pts/48000-time.monotonic()))
        samples=(np.sin((np.arange(960)+self.pts)*math.tau*440/48000)*1200).astype(np.int16)
        frame=AudioFrame(format='s16',layout='mono',samples=960);frame.planes[0].update(samples.tobytes())
        frame.sample_rate=48000;frame.pts=self.pts;frame.time_base=Fraction(1,48000);self.pts+=960;return frame


class Pattern(VideoStreamTrack):
    def __init__(self):super().__init__();self.index=0;self.origin=None
    async def recv(self):
        if self.origin is None:self.origin=time.monotonic()
        await asyncio.sleep(max(0,self.origin+self.index/15-time.monotonic()))
        y,x=np.indices((360,640));p=self.index
        rgb=np.empty((360,640,3),np.uint8);rgb[:,:,0]=(x//3+p*2)%220;rgb[:,:,1]=(y//2+50)%220;rgb[:,:,2]=185
        rgb[80:280,(p*5)%520:(p*5)%520+100]=[255,218,120]
        frame=VideoFrame.from_ndarray(rgb,format='rgb24');frame.pts=self.index*6000;frame.time_base=Fraction(1,90000);self.index+=1;return frame


class MediaAgent:
    def __init__(self,http,token,*,report=None):
        self.http=http;self.token=token;self.session=str(uuid.uuid4());self.calls={};self.report=report or (lambda x:print(json.dumps(x),flush=True))

    async def api(self,path,data=None):
        async with self.http.request('POST' if data is not None else 'GET','https://47.245.2.254/api'+path,
            json=data,headers={'Authorization':'Bearer '+self.token},allow_redirects=False) as response:
            result=await response.json()
            if response.status>=300:raise RuntimeError('Call API failed with HTTP '+str(response.status))
            return result

    async def control(self,cid,action,data=None):
        return await self.api('/bot/calls/'+cid+'/'+action,dict(session_id=self.session,**(data or {})))

    async def dial(self,peer,video=False,client_id=None):
        result=await self.api('/bot/calls',{'recipient':peer,'video':video,'client_id':client_id or str(uuid.uuid4()),'session_id':self.session})
        if result.get('call'):self.calls.setdefault(result['call_id'],{'call':result['call'],'candidates':[],'counts':{'audio':0,'video':0}})
        return result

    async def consume(self,cid,track):
        try:
            while True:
                await track.recv()
                if cid not in self.calls:return
                self.calls[cid]['counts'][track.kind]+=1
        except Exception:pass

    async def heartbeat(self,cid):
        try:
            while cid in self.calls:
                await self.control(cid,'heartbeat');await asyncio.sleep(10)
        except asyncio.CancelledError:raise
        except Exception:await self.close(cid,notify=False)

    async def report_stats(self,cid):
        try:
            while cid in self.calls:
                await asyncio.sleep(3)
                state=self.calls.get(cid)
                if not state:break
                stats=await state['pc'].getStats()
                sent=sum(getattr(s,'bytesSent',0) for s in stats.values() if s.type=='outbound-rtp')
                self.report({'event':'media.stats','call_id':cid,'audio_frames_received':state['counts']['audio'],
                    'video_frames_received':state['counts']['video'],'bytes_sent':sent,'state':state['pc'].connectionState})
        except asyncio.CancelledError:raise
        except Exception:pass

    async def ensure(self,call):
        cid=call['id'];state=self.calls.setdefault(cid,{'call':call,'candidates':[],'counts':{'audio':0,'video':0}})
        state['call']=call
        if 'pc' in state:return state
        ice=await self.api('/bot/ice')
        servers=[]
        for item in ice['iceServers']:
            urls=[url for url in item['urls'] if url.startswith('turn:') and 'transport=tcp' in url]
            if urls:servers.append(RTCIceServer(urls,username=item['username'],credential=item['credential']))
        if not servers:raise RuntimeError('No authenticated TURN/TCP relay available')
        pc=RTCPeerConnection(RTCConfiguration(iceServers=servers,bundlePolicy=RTCBundlePolicy.MAX_BUNDLE));state['pc']=pc
        pc.addTrack(Tone())
        if call['video']:pc.addTrack(Pattern())
        @pc.on('track')
        def track_received(track):
            state.setdefault('receivers',[]).append(asyncio.create_task(self.consume(cid,track)))
        @pc.on('connectionstatechange')
        async def connection_changed():
            if pc.connectionState=='connected':
                try:await self.control(cid,'connected')
                except Exception:await self.close(cid,notify=False);return
                if 'stats' not in state:state['stats']=asyncio.create_task(self.report_stats(cid))
            elif pc.connectionState=='failed':await self.close(cid,notify=True)
        state['heartbeat']=asyncio.create_task(self.heartbeat(cid))
        return state

    async def description(self,cid,description):
        state=self.calls[cid];pc=state['pc'];await pc.setLocalDescription(description)
        # aiortc gathers candidates into localDescription (non-trickle); no ICE polling.
        await self.control(cid,'signal',{'signal':{'type':pc.localDescription.type,'sdp':relay_sdp(pc.localDescription.sdp)}})

    async def event(self,p):
        kind=p['event'];call=p.get('call');cid=call['id'] if call else p.get('call_id')
        if kind=='call.ringing':
            # This program answers only its own bot endpoint, never the user's endpoint.
            await self.control(cid,'accept')
            await self.ensure(call)
        elif kind=='call.accepted':
            state=await self.ensure(call)
            if call['caller']==p['bot_id'] and not state.get('offered'):
                state['offered']=True;await self.description(cid,await state['pc'].createOffer())
        elif kind=='call.signal':
            state=self.calls.get(cid)
            if state is None:return
            pc=state['pc'];s=p['signal'];t=s['type']
            if t in ('offer','answer'):
                await pc.setRemoteDescription(RTCSessionDescription(sdp=relay_sdp(s['sdp']),type=t))
                for candidate in state['candidates']:await pc.addIceCandidate(candidate)
                state['candidates'].clear()
                if t=='offer':await self.description(cid,await pc.createAnswer())
            elif t=='candidate':
                candidate=candidate_from_sdp(s['candidate'].removeprefix('candidate:'));candidate.sdpMid=s['sdpMid'];candidate.sdpMLineIndex=s['sdpMLineIndex']
                if candidate.type!='relay':return
                if pc.remoteDescription:await pc.addIceCandidate(candidate)
                elif len(state['candidates'])<128:state['candidates'].append(candidate)
            elif t=='restart':
                # aiortc 1.15 has no public createOffer(iceRestart) API. Fail cleanly;
                # production engines can implement a full ICE restart via this protocol.
                await self.close(cid,notify=True)
        elif kind=='call.ended':await self.close(cid,notify=False)

    async def close(self,cid,notify=False):
        state=self.calls.pop(cid,None)
        if notify:
            try:await self.control(cid,'end')
            except Exception:pass
        if not state:return
        for name in ('heartbeat','stats'):
            task=state.get(name)
            if task and task is not asyncio.current_task():task.cancel()
        for task in state.get('receivers',[]):task.cancel()
        if 'pc' in state:await state['pc'].close()
        self.report({'event':'media.closed','call_id':cid,**state['counts']})


class Webhook:
    def __init__(self,agent,secret):self.agent=agent;self.secret=secret.encode();self.queue=asyncio.Queue(256);self.seen={}
    async def receive(self,request):
        ts=request.headers.get('X-Qingliao-Timestamp','');eid=request.headers.get('X-Qingliao-Event-Id','')
        if not re.fullmatch(r'[0-9]{10,11}',ts) or abs(time.time()-int(ts))>300 or not re.fullmatch(r'[a-f0-9-]{36}',eid):return web.json_response({},status=401)
        if request.headers.get('Content-Encoding','identity').lower()!='identity':return web.json_response({},status=415)
        try:raw=await asyncio.wait_for(request.read(),5)
        except asyncio.TimeoutError:return web.json_response({},status=408)
        expected='sha256='+hmac.new(self.secret,ts.encode()+b'.'+eid.encode()+b'.'+raw,hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected,request.headers.get('X-Qingliao-Signature','')):return web.json_response({},status=401)
        try:p=json.loads(raw)
        except ValueError:return web.json_response({},status=400)
        if not isinstance(p,dict):return web.json_response({},status=400)
        if p.get('id')!=eid:return web.json_response({},status=400)
        if p.get('event')=='webhook.verify':return web.json_response({'challenge':p.get('challenge')})
        if not str(p.get('event','')).startswith('call.'):
            return web.json_response({'ok':True})  # Route message.created to your existing message handler.
        self.seen={k:v for k,v in self.seen.items() if time.monotonic()-v<300}
        if eid in self.seen:return web.json_response({'ok':True})
        if self.queue.full() or len(self.seen)>=2048:return web.json_response({},status=503)
        self.queue.put_nowait(p);self.seen[eid]=time.monotonic();return web.json_response({'ok':True})
    async def worker(self):
        while True:
            p=await self.queue.get()
            try:await self.agent.event(p)
            except Exception:
                cid=p.get('call_id') or p.get('call',{}).get('id')
                if cid:await self.agent.close(cid,notify=True)
                self.agent.report({'event':'media.error'})
            finally:self.queue.task_done()


async def main():
    parser=argparse.ArgumentParser();parser.add_argument('--call');parser.add_argument('--video',action='store_true');args=parser.parse_args()
    token=os.environ.get('QINGLIAO_BOT_TOKEN','');secret=os.environ.get('QINGLIAO_WEBHOOK_SECRET','')
    if not re.fullmatch(r'qlb_[A-Za-z0-9_-]{43}',token) or len(secret)<32:raise SystemExit('Set bot token and Webhook secret in the environment first.')
    tls=ssl.create_default_context(cafile=Path(__file__).with_name('server_ca.crt'))
    from aiohttp import TCPConnector
    async with ClientSession(connector=TCPConnector(ssl=tls),timeout=ClientTimeout(total=20),trust_env=False) as http:
        agent=MediaAgent(http,token);hook=Webhook(agent,secret)
        # An explicit restart ends abandoned media; a new process never steals an old session.
        active=(await agent.api('/bot/call')).get('call')
        if active:await agent.api('/bot/calls/'+active['id']+'/end',{})
        app=web.Application(client_max_size=900000,handler_args={'auto_decompress':False});app.router.add_post('/qingliao/webhook',hook.receive)
        runner=web.AppRunner(app,access_log=None);await runner.setup();await web.TCPSite(runner,'127.0.0.1',int(os.environ.get('PORT','9080'))).start()
        task=asyncio.create_task(hook.worker());stop=asyncio.Event();loop=asyncio.get_running_loop()
        for sig in (os_signal.SIGINT,os_signal.SIGTERM):loop.add_signal_handler(sig,stop.set)
        print('Call bot ready behind your HTTPS Webhook reverse proxy. No message polling.',flush=True)
        if args.call:await agent.dial(args.call,args.video)
        try:await stop.wait()
        finally:
            for cid in list(agent.calls):await agent.close(cid,notify=True)
            task.cancel();await runner.cleanup()


if __name__=='__main__':asyncio.run(main())
