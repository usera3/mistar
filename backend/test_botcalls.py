"""Isolated REST/Webhook call authority, Tink interoperability and teardown checks."""
import asyncio,json,os,tempfile,uuid
from aiohttp.test_utils import TestClient,TestServer
from botcrypto import compact,b64,un64,signed


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-botcalls-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='1')
        import server as s
        async def idle():await asyncio.Event().wait()
        s.botcalls.worker=idle;s.bots.worker=idle
        delivered=[]
        async def callback(b,p):delivered.append(p);return 200,b'{}'
        s.bots.post=callback
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,body=None,token='',method='POST',status=200):
                r=await client.request(method,'/api'+path,json=body,headers={'Authorization':'Bearer '+token});value=await r.json()
                assert r.status==status,(path,r.status,value);return value
            users=[]
            for name in ('call_owner','call_friend','call_other'):
                u=await api('/register',{'username':name,'nickname':name,'password':'isolated-calls-password'});users.append((u['user']['id'],u['token']))
            (a,at),(b,bt),(c,ct)=users;s.write('UPDATE users SET email=? WHERE id=?',('calls@example.test',a))
            made=await api('/developer/bots',{'nickname':'Call bot'},at,status=201);bot=made['bot']['id'];token=made['token'];base='/developer/bots/'+bot
            s.write('UPDATE bots SET verified=1 WHERE user_id=?',(bot,))
            await api('/friends/request',{'peer':bot,'bot_consent':True},bt)
            sid=str(uuid.uuid4());other_sid=str(uuid.uuid4())
            await api('/bot/ice',token=token,method='GET',status=409)
            await api('/bot/calls',{'recipient':b,'client_id':'first','session_id':sid},token,status=409)
            await api(base,{'calls_enabled':True},at,method='PATCH')
            assert s.botcalls.permission(b,bot)
            assert (await api('/relationships/'+bot,token=bt,method='GET'))['bot_calls_allowed']
            await api('/relationships/'+bot+'/bot-calls',{'allowed':False},bt)
            await api('/bot/calls',{'recipient':b,'client_id':'first','session_id':sid},token,status=403)
            await api('/bot/calls',{'recipient':b,'client_id':'first','session_id':sid},at,status=403)
            await api('/bot/calls/not-a-call/end',{},token,status=403)
            private,public=s.bots.crypto.create();await api('/crypto/identity',public,bt)
            bot_public=s.security.identity_for(bot);crypto=s.bots.crypto
            ws=await client.ws_connect('/ws');await ws.send_json({'token':bt});assert (await ws.receive_json())['event']=='ready'
            async def receive(event):
                for _ in range(50):
                    p=await ws.receive_json(timeout=3)
                    if p['event']==event:return p
                raise AssertionError('Event not received')
            def human_signal(call,inner):
                context=f"qingliao:call:1:{call['id']}:{b}:{bot}";raw=compact(inner).encode()
                e=dict(v=1,context=context,sender_key=public['key_id'],recipient_key=bot_public['key_id'],
                    sender_cipher=b64(crypto.read(public['enc']).primitive(crypto.hybrid.HybridEncrypt).encrypt(raw,context.encode())),
                    recipient_cipher=b64(crypto.read(bot_public['enc']).primitive(crypto.hybrid.HybridEncrypt).encrypt(raw,context.encode())))
                e['signature']=b64(crypto.read(private['sig']).primitive(crypto.signature.PublicKeySign).sign(signed(e)));return {'envelope':e}
            def open_bot_signal(p):
                e=p['signal']['envelope'];crypto.read(bot_public['sig']).primitive(crypto.signature.PublicKeyVerify).verify(un64(e['signature']),signed(e))
                assert e['context']==f"qingliao:call:1:{p['call_id']}:{bot}:{b}"
                return json.loads(crypto.read(private['enc']).primitive(crypto.hybrid.HybridDecrypt).decrypt(un64(e['recipient_cipher']),e['context'].encode()))
            # Human initiates. Bot accepts once; a second bot process cannot claim the same call.
            call=await s.signaling(b,{'event':'call.invite','peer':bot,'video':True,'invite_id':str(uuid.uuid4())});cid=call['id'];path='/bot/calls/'+cid
            await api('/friends/request',{'peer':bot,'bot_consent':True},ct)
            wc=await client.ws_connect('/ws');await wc.send_json({'token':ct});assert (await wc.receive_json())['event']=='ready'
            iid=str(uuid.uuid4());await wc.send_json({'event':'call.invite','peer':bot,'video':True,'invite_id':iid})
            busy=await wc.receive_json();assert busy['event']=='error' and busy['code']=='call_busy' and busy['peer']==bot and busy['video'] and busy['invite_id']==iid
            assert s.call_for(b)['id']==cid and not s.call_for(c)
            await wc.send_json({'event':'ping'});assert (await wc.receive_json())['event']=='pong';await wc.close()
            ring=s.row("SELECT * FROM bot_call_events WHERE kind='call.ringing'");await s.botcalls.deliver(ring);assert delivered[-1]['call']['id']==cid
            await api(path+'/accept',{'session_id':sid},token);await api(path+'/accept',{'session_id':sid},token)
            await api(path+'/accept',{'session_id':other_sid},token,status=409)
            await receive('call.accepted')
            offer={'type':'offer','sdp':'v=0\r\ns=private-sdp-marker\r\n'}
            await s.signaling(b,{'event':'call.signal','call_id':cid,'signal':human_signal(call,offer)})
            queued=s.row("SELECT * FROM bot_call_events WHERE kind='call.signal'");assert 'private-sdp-marker' not in queued['body'];await s.botcalls.deliver(queued)
            assert delivered[-1]['signal']==offer and sid not in compact(delivered[-1])
            answer={'type':'answer','sdp':'v=0\r\ns=bot-answer\r\n'}
            await api(path+'/signal',{'session_id':other_sid,'signal':answer},token,status=409)
            await api(path+'/signal',{'session_id':sid,'signal':{'type':'offer','sdp':'x'*65537}},token,status=400)
            await api(path+'/signal',{'session_id':sid,'signal':answer},token)
            assert open_bot_signal(await receive('call.signal'))==answer
            assert (await api('/bot/ice',token=token,method='GET'))['iceServers'][-1]['urls']==['turn:'+s.PUBLIC_IP+':443?transport=tcp']
            await api(path+'/connected',{'session_id':sid},token);assert not call.get('connected')
            await s.signaling(b,{'event':'call.connected','call_id':cid});assert call['connected']
            await api(path+'/heartbeat',{'session_id':sid},token);call['bot_deadline']=s.now()-1;await s.botcalls.tick();assert not s.call_for(b)
            assert s.row('SELECT count(*) AS n FROM messages WHERE client_id=?',('call:'+cid,))['n']==1
            assert all(x['kind']=='call.ended' for x in s.rows('SELECT kind FROM bot_call_events WHERE call_id=?',(cid,)))
            await api(path+'/end',{},token);await api(path+'/accept',{'session_id':sid},token,status=409)
            # Explicit per-user permission, invite idempotency and process ownership.
            s.limits.clear();await api('/relationships/'+bot+'/bot-calls',{'allowed':True},bt)
            assert (await api('/relationships/'+bot,token=bt,method='GET'))['bot_calls_allowed']
            body={'recipient':b,'client_id':'outgoing-stable','session_id':sid,'video':False}
            invited=await api('/bot/calls',body,token);out=invited['call'];outpath='/bot/calls/'+out['id']
            assert (await api('/bot/calls',body,token))['call']['id']==out['id']
            busy=await api('/bot/calls',dict(body,client_id='busy-retry'),token,status=409);assert busy['code']=='self_busy'
            assert s.call_for(b)['id']==out['id']
            await api('/bot/calls',dict(body,recipient=c),token,status=409)
            await api('/bot/calls',dict(body,session_id=other_sid),token,status=409)
            assert 'bot_session' not in compact(await api('/bot/call',token=token,method='GET'))
            await s.signaling(b,{'event':'call.accept','call_id':out['id']})
            await api('/relationships/'+bot+'/bot-calls',{'allowed':False},bt);assert not s.call_for(bot)
            ended=await api('/bot/calls',body,token);assert ended['ended'] and ended['call'] is None
            # Token rotation and capability changes tear down existing media authority.
            s.limits.clear();await api('/relationships/'+bot+'/bot-calls',{'allowed':True},bt)
            body['client_id']='rotate-call';newcall=await api('/bot/calls',body,token)
            rotated=await api(base+'/rotate',{},at);assert not s.call_for(bot)
            await api('/bot/calls/'+newcall['call_id']+'/end',{},token,status=401);token=rotated['token']
            s.limits.clear();body['client_id']='capability-call';await api('/bot/calls',body,token)
            await api(base,{'calls_enabled':False},at,method='PATCH');assert not s.call_for(bot)
            await api(base,{'calls_enabled':True},at,method='PATCH')
            # Removal denies access; explicitly re-adding uses the new default.
            await api('/friends/remove',{'peer':bot},bt);assert not s.botcalls.permission(b,bot)
            await api('/friends/request',{'peer':bot,'bot_consent':True},bt)
            assert s.botcalls.permission(b,bot)
            s.limits.clear();body['client_id']='after-readd';again=await api('/bot/calls',body,token)
            await api('/bot/calls/'+again['call_id']+'/busy',{},token)
            assert not s.call_for(b)
            assert (await receive('call.ended'))['event']=='call.ended'
            assert s.row('SELECT text FROM messages WHERE client_id=?',('call:'+again['call_id'],))['text']=='对方忙线，请稍后重试'
            await api('/bot/calls/'+again['call_id']+'/busy',{},token)
            assert s.row('SELECT count(*) AS n FROM messages WHERE client_id=?',('call:'+again['call_id'],))['n']==1
            s.limits.clear();busycall=await s.signaling(b,{'event':'call.invite','peer':bot,'video':False})
            await api('/bot/calls/'+busycall['id']+'/busy',{},token);assert not s.call_for(bot)
            # A modified human signature closes the call, never reaches the developer as plaintext.
            s.limits.clear();badcall=await s.signaling(b,{'event':'call.invite','peer':bot,'video':False})
            await api('/bot/calls/'+badcall['id']+'/accept',{'session_id':sid},token)
            tampered=human_signal(badcall,offer);tampered['envelope']['signature']=b64(b'bad-signature')
            try:await s.signaling(b,{'event':'call.signal','call_id':badcall['id'],'signal':tampered})
            except s.ApiError as e:assert e.status==409
            else:raise AssertionError('Invalid signal accepted')
            assert not s.call_for(bot)
            assert s.db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not s.db.execute('PRAGMA foreign_key_check').fetchall()
            await ws.close()
            print('PASS bot calls: default-allow/explicit opt-out, structured busy keeps WebSocket and active calls, busy end/idempotency, bidirectional flow, real Tink SDP, encrypted event queue, session ownership, lease expiry, revoke/disable/permission teardown and invalid-signature failure')


if __name__=='__main__':asyncio.run(main())
