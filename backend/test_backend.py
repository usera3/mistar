"""Black-box integration test. Uses a fresh temporary database, never production data.
Run: python3 test_backend.py
"""
import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
from pathlib import Path

import aiohttp


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-test-') as directory:
        sock = socket.socket()
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
        sock.close()
        env = dict(os.environ, QINGLIAO_REQUIRE_EMAIL='0', QINGLIAO_REQUIRE_E2EE='0', QINGLIAO_DATA=directory, PORT=str(port), TURN_SECRET='test-only-secret')
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('server.py'))], env=env, stdout=subprocess.DEVNULL)
        try:
            async with aiohttp.ClientSession() as http:
                url = f'http://127.0.0.1:{port}'
                for _ in range(100):
                    try:
                        async with http.get(url + '/api/health') as r:
                            if r.status == 200: break
                    except aiohttp.ClientError: pass
                    await asyncio.sleep(.1)
                else: raise AssertionError('Server did not start')

                async def api(method, path, data=None, token='', status=200):
                    async with http.request(method, url + '/api' + path, json=data, headers={'Authorization': 'Bearer ' + token}) as r:
                        p = await r.json()
                        assert r.status == status, (path, r.status, p)
                        return p

                a = await api('POST', '/register', {'username': 'alice', 'password': 'testing-password-a', 'nickname': 'Alice'})
                b = await api('POST', '/register', {'username': 'bob_qa', 'password': 'testing-password-b', 'nickname': 'Bob'})
                c = await api('POST', '/register', {'username': 'carol', 'password': 'testing-password-c', 'nickname': 'Carol'})
                aid, bid = a['user']['id'], b['user']['id']
                at, bt, ct = a['token'], b['token'], c['token']
                await api('GET', '/me', status=401)
                await api('POST', '/login', {'username': 'alice', 'password': 'incorrect'}, status=401)
                await api('POST', '/messages', {'recipient': bid, 'text': 'blocked'}, at, 403)
                await api('POST', '/friends/request', {'peer': bid}, at)
                await api('POST', '/friends/respond', {'peer': aid, 'accept': True}, bt)
                async with http.ws_connect(url + '/ws') as ws:
                    await ws.send_json({'token': bt})
                    assert (await ws.receive_json())['event'] == 'ready'
                    sent = await api('POST', '/messages', {'recipient': bid, 'text': 'hello', 'client_id': 'test-1'}, at)
                    assert (await ws.receive_json())['message']['id'] == sent['id']
                    again = await api('POST', '/messages', {'recipient': bid, 'text': 'hello', 'client_id': 'test-1'}, at)
                    assert again['id'] == sent['id']
                await api('POST', '/read/' + aid, {}, bt)
                history = await api('GET', '/messages/' + bid, token=at)
                assert history['messages'][0]['read'] is True
                await api('GET', '/messages/' + aid, token=ct, status=403)
                async with http.post(url + '/api/uploads?recipient=' + bid + '&name=notes.txt', data=b'hello attachment', headers={'Authorization': 'Bearer ' + at, 'Content-Type': 'text/plain'}) as r:
                    assert r.status == 200
                    f = await r.json()
                await api('GET', '/files/' + f['id'], token=bt, status=404)
                await api('POST', '/messages', {'recipient': bid, 'type': 'file', 'file_id': f['id']}, at)
                async with http.get(url + '/api/files/' + f['id'], headers={'Authorization': 'Bearer ' + bt}) as r:
                    assert r.status == 200
                    assert await r.read() == b'hello attachment'
                await api('GET', '/files/' + f['id'], token=ct, status=404)
                assert (await api('GET', '/ice', token=at))['iceServers'][1]['credential']
                await api('POST', '/logout', {}, at)
                await api('GET', '/me', token=at, status=401)
                assert Path(directory, 'qingliao.sqlite3').is_file()
                print('PASS: auth, friendship, realtime delivery, deduplication, receipts, attachment isolation, TURN credentials, logout')
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__': asyncio.run(main())
