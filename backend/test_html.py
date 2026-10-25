"""Isolated API checks for HTML transport, source preservation and permissions."""
import asyncio
import os
import socket
import subprocess
import sys
import tempfile
from pathlib import Path
import aiohttp


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-html-') as directory:
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
        env = dict(os.environ, QINGLIAO_REQUIRE_EMAIL='0', QINGLIAO_REQUIRE_E2EE='0', QINGLIAO_DATA=directory, PORT=str(port), TURN_SECRET='test-only-secret')
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('server.py'))], env=env, stdout=subprocess.DEVNULL)
        try:
            async with aiohttp.ClientSession() as http:
                base = f'http://127.0.0.1:{port}/api'
                for _ in range(100):
                    try:
                        async with http.get(base + '/health') as r:
                            if r.status == 200: break
                    except aiohttp.ClientError: pass
                    await asyncio.sleep(.1)
                else: raise AssertionError('Server did not start')

                async def api(method, path, data=None, token='', status=200):
                    async with http.request(method, base + path, json=data, headers={'Authorization': 'Bearer ' + token}) as r:
                        result = await r.json(); assert r.status == status, result
                        return result

                a, b, c = [await api('POST', '/register', {'username': name, 'nickname': name, 'password': 'html-testing-password'}) for name in ('html_alice', 'html_bob', 'html_carol')]
                aid, bid, cid = a['user']['id'], b['user']['id'], c['user']['id']
                at, bt, ct = a['token'], b['token'], c['token']
                await api('POST', '/friends/request', {'peer': bid}, at)
                await api('POST', '/friends/respond', {'peer': aid, 'accept': True}, bt)
                source = '<style>.card{background:#d6eee3;padding:20px}</style><div class="card"><h1>你好，HTML</h1><p>A &amp; B</p></div><script>untrusted()</script>'
                async with http.ws_connect(f'http://127.0.0.1:{port}/ws') as ws:
                    await ws.send_json({'token': bt}); assert (await ws.receive_json())['event'] == 'ready'
                    sent = await api('POST', '/messages', {'recipient': bid, 'type': 'html', 'text': source, 'client_id': 'html-1'}, at)
                    incoming = (await ws.receive_json())['message']
                    assert incoming['type'] == 'html' and incoming['text'] == source and incoming['id'] == sent['id']
                duplicate = await api('POST', '/messages', {'recipient': bid, 'type': 'html', 'text': source, 'client_id': 'html-1'}, at)
                assert duplicate['id'] == sent['id']
                history = await api('GET', '/messages/' + aid, token=bt)
                assert history['messages'][0]['text'] == source
                await api('POST', '/messages', {'recipient': cid, 'type': 'html', 'text': source}, at, 403)
                await api('GET', '/messages/' + bid, token=ct, status=403)
                await api('POST', '/messages', {'recipient': bid, 'type': 'html', 'text': ' '}, at, 400)
                await api('POST', '/messages', {'recipient': bid, 'type': 'html', 'text': 'x' * 50001}, at, 400)
                await api('POST', '/messages', {'recipient': bid, 'type': 'html', 'text': 'x' * 50000}, at)
                await api('POST', '/messages', {'recipient': bid, 'type': 'text', 'text': 'x' * 5001}, at, 400)
                print('PASS HTML realtime delivery, source preservation, history, deduplication, friendship isolation and size limits')
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__': asyncio.run(main())
