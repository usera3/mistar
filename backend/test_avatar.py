"""Isolated avatar API regression: images, visibility, live updates and persistence."""
import asyncio
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
from io import BytesIO
from pathlib import Path

import aiohttp
from PIL import Image


def png(size=(640, 480), color=(30, 130, 110, 180)):
    output = BytesIO()
    Image.new('RGBA', size, color).save(output, format='PNG')
    return output.getvalue()


async def event(ws, kind):
    async def wait():
        while True:
            p = await ws.receive_json()
            if p.get('event') == kind: return p
    return await asyncio.wait_for(wait(), 5)


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-avatar-') as directory:
        database = Path(directory, 'qingliao.sqlite3')
        with sqlite3.connect(database) as legacy:
            legacy.execute('CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,nickname TEXT NOT NULL,salt TEXT NOT NULL,password TEXT NOT NULL,created INTEGER NOT NULL,last_seen INTEGER NOT NULL DEFAULT 0)')
            legacy.execute('INSERT INTO users VALUES(?,?,?,?,?,?,?)', ('10000001', 'legacy_qa', 'Legacy', '00' * 16, '00', 1, 12345))
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
        env = dict(os.environ, QINGLIAO_REQUIRE_EMAIL='0', QINGLIAO_REQUIRE_E2EE='0', QINGLIAO_DATA=directory, PORT=str(port), TURN_SECRET='test-only-secret')
        process = None; sockets = []
        async with aiohttp.ClientSession() as http:
            base = f'http://127.0.0.1:{port}'
            async def start():
                nonlocal process
                process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('server.py'))], env=env, stdout=subprocess.DEVNULL)
                for _ in range(100):
                    try:
                        async with http.get(base + '/api/health') as response:
                            if response.status == 200: return
                    except aiohttp.ClientError: pass
                    await asyncio.sleep(.1)
                raise AssertionError('Server did not start')

            async def api(method, path, token='', data=None, raw=None, status=200):
                async with http.request(method, base + '/api' + path, json=data, data=raw, headers={'Authorization': 'Bearer ' + token}) as r:
                    assert r.status == status, (r.status, await r.text())
                    if path.startswith('/avatars/') and r.status == 200: return await r.read()
                    return await r.json()

            async def ws(token):
                connection = await http.ws_connect(base + '/ws'); sockets.append(connection)
                await connection.send_json({'token': token}); await event(connection, 'ready')
                return connection

            try:
                await start()
                a, b, c = [await api('POST', '/register', data={'username': name, 'password': 'avatar-test-password', 'nickname': name}) for name in ('avatar_a', 'avatar_b', 'avatar_c')]
                aid, bid = a['user']['id'], b['user']['id']; at, bt, ct = a['token'], b['token'], c['token']
                assert a['user']['avatar_id'] == ''
                await api('PUT', '/me/avatar', raw=png(), status=401)
                first = await api('PUT', '/me/avatar', at, raw=png())
                fid = first['avatar_id']; assert len(fid) == 32
                content = await api('GET', '/avatars/' + fid, at)
                with Image.open(BytesIO(content)) as image:
                    assert image.format == 'JPEG' and image.size == (512, 512) and image.mode == 'RGB'
                    assert not image.getexif()
                await api('GET', '/avatars/' + fid, ct, status=404)
                assert (await api('GET', '/users?q=' + aid, ct))['user']['avatar_id'] == ''
                await api('POST', '/friends/request', at, data={'peer': bid})
                assert (await api('GET', '/contacts', bt))['contacts'][0]['avatar_id'] == fid
                await api('GET', '/avatars/' + fid, bt)
                await api('POST', '/friends/respond', bt, data={'peer': aid, 'accept': True})
                wa, wb = await ws(at), await ws(bt)
                second = await api('PUT', '/me/avatar', at, raw=png(color=(200, 80, 70, 255)))
                second_id = second['avatar_id']; assert second_id != fid
                assert (await event(wa, 'me.updated'))['user']['avatar_id'] == second_id
                assert (await event(wb, 'profile.updated'))['user']['avatar_id'] == second_id
                await api('GET', '/avatars/' + fid, bt, status=404)
                assert not Path(directory, 'avatars', fid).exists()
                print('PASS normalized private JPEG avatar, pending/accepted friend visibility and live revision updates')

                for invalid in (b'not an image', b'<svg xmlns="http://www.w3.org/2000/svg"/>', png(size=(2049, 2))):
                    await api('PUT', '/me/avatar', at, raw=invalid, status=400)
                await api('PUT', '/me/avatar', at, raw=b'x' * (2 * 1024 * 1024 + 1), status=413)
                assert (await api('GET', '/me', at))['avatar_id'] == second_id
                await api('PATCH', '/me', at, data={'nickname': 'New nickname'})
                updated = (await event(wb, 'profile.updated'))['user']
                assert updated['nickname'] == 'New nickname' and updated['avatar_id'] == second_id
                await api('DELETE', '/me/avatar', ct)
                assert (await api('GET', '/me', at))['avatar_id'] == second_id
                restored = await api('DELETE', '/me/avatar', at)
                assert restored['avatar_id'] == ''
                assert (await event(wb, 'profile.updated'))['user']['avatar_id'] == ''
                await api('GET', '/avatars/' + second_id, bt, status=404)
                assert not list(Path(directory, 'avatars').iterdir())
                print('PASS malformed/oversized images rejected, nickname sync and restoring default removes old files')

                final = await api('PUT', '/me/avatar', at, raw=png())
                for connection in sockets: await connection.close()
                process.terminate(); process.wait(timeout=10); await start()
                assert (await api('GET', '/me', at))['avatar_id'] == final['avatar_id']
                await api('GET', '/avatars/' + final['avatar_id'], bt)
                with sqlite3.connect(database) as db:
                    assert db.execute('SELECT nickname,last_seen,avatar_id FROM users WHERE id=?', ('10000001',)).fetchone() == ('Legacy', 12345, '')
                print('PASS legacy accounts preserved and uploaded avatar survives backend restart')
            finally:
                for connection in sockets: await connection.close()
                if process and process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__': asyncio.run(main())
