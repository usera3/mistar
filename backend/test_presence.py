"""Black-box tests for presence migration, durable last-seen and transient typing.
Uses an isolated temporary database and loopback port; never production accounts.
Run: python3 test_presence.py
"""
import asyncio
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import aiohttp


async def event(ws, kind, **matches):
    async def wait():
        while True:
            p = await ws.receive_json()
            if p.get('event') == kind and all(p.get(k) == v for k, v in matches.items()): return p
    return await asyncio.wait_for(wait(), 8)


async def no_event(ws, kind, **matches):
    try: await asyncio.wait_for(event(ws, kind, **matches), .35)
    except asyncio.TimeoutError: return
    raise AssertionError(f'Unexpected {kind}: {matches}')


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-presence-') as directory:
        # Simulate the deployed v1.0 users schema to verify an in-place upgrade.
        database = Path(directory, 'qingliao.sqlite3')
        legacy = sqlite3.connect(database)
        legacy.executescript('CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,nickname TEXT NOT NULL,salt TEXT NOT NULL,password TEXT NOT NULL,created INTEGER NOT NULL);')
        legacy.execute('INSERT INTO users VALUES(?,?,?,?,?,?)', ('10000001', 'legacy_seed', 'Legacy', '00' * 16, '00', 1))
        legacy.commit(); legacy.close()
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
        env = dict(os.environ, QINGLIAO_REQUIRE_EMAIL='0', QINGLIAO_REQUIRE_E2EE='0', QINGLIAO_DATA=directory, PORT=str(port), TURN_SECRET='test-only-secret')
        process = None
        sockets = []
        async with aiohttp.ClientSession() as http:
            url = f'http://127.0.0.1:{port}'

            async def start():
                nonlocal process
                process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('server.py'))], env=env, stdout=subprocess.DEVNULL)
                for _ in range(100):
                    try:
                        async with http.get(url + '/api/health') as r:
                            if r.status == 200: return
                    except aiohttp.ClientError: pass
                    await asyncio.sleep(.1)
                raise AssertionError('Server did not start')

            async def api(method, path, token='', data=None):
                async with http.request(method, url + '/api' + path, json=data, headers={'Authorization': 'Bearer ' + token}) as r:
                    p = await r.json(); assert r.status == 200, p
                    return p

            async def ws(token):
                connection = await http.ws_connect(url + '/ws')
                sockets.append(connection)
                await connection.send_json({'token': token})
                await event(connection, 'ready')
                return connection

            try:
                await start()
                a = await api('POST', '/register', data={'username': 'typing_a', 'password': 'test-password-a', 'nickname': 'A'})
                b = await api('POST', '/register', data={'username': 'typing_b', 'password': 'test-password-b', 'nickname': 'B'})
                c = await api('POST', '/register', data={'username': 'typing_c', 'password': 'test-password-c', 'nickname': 'C'})
                aid, bid = a['user']['id'], b['user']['id']
                at, bt, ct = a['token'], b['token'], c['token']
                await api('POST', '/friends/request', at, {'peer': bid})
                await api('POST', '/friends/respond', bt, {'peer': aid, 'accept': True})
                wa, wb, wc = await ws(at), await ws(bt), await ws(ct)
                state = (await api('GET', '/contacts', bt))['contacts'][0]
                assert state['online'] is True and state['last_seen'] > 0
                print('PASS legacy users schema upgraded; connected friend is online')

                async def typing(connection, active):
                    await connection.send_json({'event': 'typing', 'peer': bid, 'active': active})

                await typing(wa, True); await event(wb, 'typing', peer=aid, active=True)
                await typing(wa, False); await event(wb, 'typing', peer=aid, active=False)
                await typing(wc, True); await event(wc, 'error')
                await no_event(wb, 'typing', peer=c['user']['id'])
                print('PASS start/stop typing delivered only to an accepted friend')

                await typing(wa, True); await event(wb, 'typing', active=True)
                await event(wb, 'typing', active=False)
                assert (await api('GET', '/messages/' + aid, bt))['messages'] == []
                print('PASS abandoned typing expires; no typing text/history persisted')

                wa2 = await ws(at)
                await typing(wa, True); await event(wb, 'typing', active=True)
                await typing(wa2, True); await event(wb, 'typing', active=True)
                await wa.close()
                await no_event(wb, 'typing', active=False)
                assert (await api('GET', '/contacts', bt))['contacts'][0]['online'] is True
                await typing(wa2, False); await event(wb, 'typing', active=False)
                print('PASS one device disconnect does not clear another device or mark user offline')

                await typing(wa2, True); await event(wb, 'typing', active=True)
                await api('POST', '/messages', at, {'recipient': bid, 'text': 'Typing has been sent'})
                await event(wb, 'typing', active=False); await event(wb, 'message')
                await typing(wa2, True); await event(wb, 'typing', active=True)
                before = int(time.time() * 1000)
                await wa2.close()
                await event(wb, 'typing', active=False)
                offline = (await event(wb, 'presence'))['user']
                assert offline['online'] is False and before <= offline['last_seen'] <= int(time.time() * 1000)
                last_seen = offline['last_seen']
                stranger = await api('GET', '/users?q=' + aid, ct)
                assert stranger['user']['last_seen'] == 0
                print('PASS disconnect clears typing and persists last-seen; strangers cannot read timestamp')

                for connection in sockets: await connection.close()
                process.terminate(); process.wait(timeout=10)
                await start()
                restored = (await api('GET', '/contacts', bt))['contacts'][0]
                assert restored['last_seen'] == last_seen and restored['online'] is False
                legacy_user = await api('GET', '/users?q=legacy_seed', bt)
                assert legacy_user['user']['last_seen'] == 0
                assert (await api('GET', '/messages/' + aid, bt))['messages'][0]['text'] == 'Typing has been sent'
                print('PASS last-seen and original messages survive backend restart; unknown history stays unknown')
            finally:
                for connection in sockets: await connection.close()
                if process and process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__': asyncio.run(main())
