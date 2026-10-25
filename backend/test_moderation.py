"""Isolated moderation security and revocation tests. Never accesses production."""
import asyncio
import hashlib
import json
import os
import tempfile
from pathlib import Path
from io import BytesIO
from aiohttp.test_utils import TestClient,TestServer
from PIL import Image
from deployment.create_admin import create_admin


async def main():
    with tempfile.TemporaryDirectory(prefix='xingyu-moderation-') as temp:
        os.environ.update(QINGLIAO_DATA=temp,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0',QINGLIAO_PUBLIC_PLAZA='0')
        import server as s
        created=create_admin(Path(temp)/'qingliao.sqlite3','xingyu_admin',Path(temp)/'admin.json')
        initial=json.loads((Path(temp)/'admin.json').read_text())['initial_password'];adminid=created['id']
        async with TestClient(TestServer(s.app)) as client:
            async def api(method,path,body=None,token='',grant='',status=200):
                r=await client.request(method,'/api'+path,json=body,headers={'Authorization':'Bearer '+token,'X-Admin-Grant':grant})
                p=await r.json();assert r.status==status,(path,r.status,p);return p
            async def login(user,password):return await api('POST','/login',{'username':user,'password':password})
            admin=await login('xingyu_admin',initial);at=admin['token']
            assert admin['user']['administrator'] and admin['user']['admin_password_change_required']
            await api('GET','/admin/status',token=at)
            await api('POST','/admin/unlock',{'password':initial},at,status=403)
            await api('POST','/password/change',{'old_password':initial,'new_password':'shortpass'},at,status=400)
            strong='synthetic-admin-password-123'
            await api('POST','/password/change',{'old_password':initial,'new_password':strong},at)
            await api('GET','/me',token=at,status=401)
            at=(await login('xingyu_admin',strong))['token']
            a,b=[await api('POST','/register',{'username':'moderation_'+n,'nickname':n,'password':'synthetic-user-password','administrator':True,'role':'admin'}) for n in ('a','b')]
            aid,bid=a['user']['id'],b['user']['id'];ta,tb=a['token'],b['token']
            assert not a['user']['administrator'] and not s.moderation.staff(aid)
            for path in ('/admin/status','/admin/users','/admin/posts','/admin/audit'):
                await api('GET',path,token=ta,status=403)
            await api('GET','/admin/posts',token=at,status=403)
            grant=(await api('POST','/admin/unlock',{'password':strong},at))['grant']
            await api('GET','/admin/users',token=ta,grant=grant,status=403)
            await api('POST','/friends/request',{'peer':bid},ta);await api('POST','/friends/respond',{'peer':aid,'accept':True},tb)
            assert (await api('GET','/users?q=xingyu_admin',token=ta))['user'] is None
            await api('POST','/friends/request',{'peer':adminid},ta,status=404)
            image=BytesIO();Image.new('RGB',(48,48),'purple').save(image,'PNG')
            r=await client.post('/api/post-photos',data=image.getvalue(),headers={'Authorization':'Bearer '+ta});assert r.status==200
            fid=(await r.json())['id']
            post=await api('POST','/posts',{'text':'synthetic private moderation test','audience':'friends','photos':[fid],'client_id':'post'},ta);pid=post['id']
            comment=await api('POST','/posts/'+pid+'/comments',{'text':'synthetic comment','client_id':'comment'},tb);cid=comment['id']
            await api('GET','/posts/'+pid,token=at,status=404)
            posts=(await api('GET','/admin/posts',token=at,grant=grant))['posts'];assert posts[0]['id']==pid
            assert (await api('GET','/admin/posts?q=synthetic',token=at,grant=grant))['posts']
            assert not (await api('GET','/admin/posts?q=%25',token=at,grant=grant))['posts']
            assert (await client.get('/api/admin/media/'+fid,headers={'Authorization':'Bearer '+at,'X-Admin-Grant':grant})).status==200
            assert (await client.get('/api/admin/media/'+fid,headers={'Authorization':'Bearer '+ta,'X-Admin-Grant':grant})).status==403
            await api('POST','/admin/content/post/'+pid,{'hidden':True,'reason':'x'},at,grant,400)
            await api('POST','/admin/content/post/'+pid,{'hidden':True,'reason':'合成测试下架'},at,grant)
            assert not (await api('GET','/posts?scope=friends',token=tb))['posts']
            await api('GET','/posts/'+pid,token=tb,status=404)
            await api('POST','/posts/'+pid+'/like',{'liked':True},tb,status=404)
            assert (await client.get('/api/post-photos/'+fid,headers={'Authorization':'Bearer '+tb})).status==404
            assert s.row('SELECT audience FROM posts WHERE id=?',(pid,))['audience']=='friends'
            assert (Path(temp)/'moments'/fid).exists()
            await api('POST','/admin/content/post/'+pid,{'hidden':False,'reason':'合成测试恢复'},at,grant)
            await api('GET','/posts/'+pid,token=tb)
            await api('POST','/admin/content/comment/'+cid,{'hidden':True,'reason':'合成测试评论下架'},at,grant)
            detail=await api('GET','/posts/'+pid,token=ta);assert detail['comment_count']==0 and not detail['comments']
            assert not (await api('GET','/posts/'+pid+'/comments',token=ta))['comments']
            assert (await api('GET','/admin/posts/'+pid+'/comments',token=at,grant=grant))['comments'][0]['moderation']['hidden']
            await api('POST','/admin/content/comment/'+cid,{'hidden':False,'reason':'合成测试评论恢复'},at,grant)
            assert (await api('GET','/posts/'+pid,token=ta))['comment_count']==1
            await api('POST','/admin/users/'+aid+'/action',{'action':'restrict_posts','days':1,'reason':'合成测试限发'},at,grant)
            await api('POST','/posts',{'text':'forbidden','client_id':'restricted'},ta,status=403)
            await api('POST','/posts/'+pid+'/comments',{'text':'forbidden','client_id':'restricted'},ta,status=403)
            r=await client.post('/api/post-photos',data=image.getvalue(),headers={'Authorization':'Bearer '+ta});assert r.status==403
            # Limit publishing, preserve private chat and existing friend content.
            await api('POST','/messages',{'recipient':bid,'type':'text','text':'isolated normal chat','client_id':'allowed-chat'},ta)
            await api('POST','/admin/users/'+aid+'/action',{'action':'restore_posts','reason':'合成测试解除限发'},at,grant)
            await api('POST','/posts',{'text':'restored','client_id':'restored'},ta)
            # Every administrator, including self, is protected from all account actions.
            await api('POST','/admin/users/'+adminid+'/action',{'action':'ban','days':0,'reason':'forbidden self ban'},at,grant,403)
            ws=await client.ws_connect('/ws');await ws.send_json({'token':ta});assert (await ws.receive_json())['event']=='ready'
            await api('POST','/admin/users/'+aid+'/action',{'action':'ban','days':1,'reason':'合成测试封禁'},at,grant)
            msg=await ws.receive(timeout=3)
            assert msg.type in (__import__('aiohttp').WSMsgType.CLOSE,__import__('aiohttp').WSMsgType.CLOSED)
            await api('GET','/me',token=ta,status=401)
            await api('POST','/login',{'username':'moderation_a','password':'synthetic-user-password'},status=403)
            await api('POST','/admin/users/'+aid+'/action',{'action':'unban','reason':'合成测试解封'},at,grant)
            ta=(await login('moderation_a','synthetic-user-password'))['token']
            await api('POST','/admin/users/'+aid+'/action',{'action':'logout','reason':'合成测试强制下线'},at,grant)
            await api('GET','/me',token=ta,status=401)
            await api('POST','/admin/users/'+aid+'/action',{'action':'hide_posts','reason':'合成测试批量下架'},at,grant)
            hidden=await api('GET','/admin/posts?state=hidden&owner='+aid,token=at,grant=grant);assert len(hidden['posts'])==2
            # A grant cannot cross login sessions, survive its TTL or survive role removal.
            at2=(await login('xingyu_admin',strong))['token']
            await api('GET','/admin/posts',token=at2,grant=grant,status=403)
            logs=(await api('GET','/admin/audit',token=at,grant=grant))['logs']
            assert any(p['action']=='ban' for p in logs) and all('password' not in json.dumps(p).lower() for p in logs)
            s.write('UPDATE admin_grants SET expires=0')
            await api('GET','/admin/posts',token=at,grant=grant,status=403)
            grant2=(await api('POST','/admin/unlock',{'password':strong},at))['grant']
            s.write('DELETE FROM administrators WHERE uid=?',(adminid,))
            await api('GET','/admin/posts',token=at,grant=grant2,status=403)
            assert s.row('PRAGMA integrity_check')['integrity_check']=='ok' and not s.rows('PRAGMA foreign_key_check')
            print('PASS administrator bootstrap/password/grants/role isolation; private content/media moderation and restoration; comments/counts; publishing restrictions; ban/unban/logout/WS revocation; audit and SQL safety')
        s.db.close()

if __name__=='__main__':asyncio.run(main())
