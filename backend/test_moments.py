"""Real isolated HTTP/WS tests; never connects to production or uses real user data."""
import asyncio
import gc
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
from contextlib import closing
from io import BytesIO
from pathlib import Path

import aiohttp
from PIL import Image


def image():
    output = BytesIO()
    Image.new('RGB', (1800, 1200), (55, 118, 175)).save(output, 'JPEG')
    return output.getvalue()


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-posts-') as directory:
        database = Path(directory, 'qingliao.sqlite3')
        with closing(sqlite3.connect(database)) as legacy:
            legacy.execute('CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,nickname TEXT NOT NULL,salt TEXT NOT NULL,password TEXT NOT NULL,created INTEGER NOT NULL)')
            legacy.execute('INSERT INTO users VALUES(?,?,?,?,?,?)', ('10000001', 'legacy', 'Legacy', '00' * 16, '00', 1))
            legacy.commit()
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
        env = dict(os.environ, QINGLIAO_REQUIRE_EMAIL='0', QINGLIAO_REQUIRE_E2EE='0', QINGLIAO_DATA=directory, PORT=str(port), TURN_SECRET='test-only-secret')
        process = None
        sockets = []
        async with aiohttp.ClientSession() as http:
            base = f'http://127.0.0.1:{port}'
            async def start():
                nonlocal process
                process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('server.py'))], env=env, stdout=subprocess.DEVNULL)
                for _ in range(100):
                    try:
                        async with http.get(base + '/api/health') as r:
                            if r.status == 200: return
                    except aiohttp.ClientError: pass
                    await asyncio.sleep(.1)
                raise AssertionError('Server failed to start')

            async def api(method, path, token='', data=None, raw=None, status=200):
                async with http.request(method, base + '/api' + path, json=data, data=raw, headers={'Authorization': 'Bearer ' + token}) as r:
                    assert r.status == status, (method, path, r.status, await r.text())
                    if path.startswith('/post-photos/') and status == 200: return await r.read()
                    return await r.json()

            async def event(ws, kind):
                async def wait():
                    while True:
                        p = await ws.receive_json()
                        if p.get('event') == kind: return p
                return await asyncio.wait_for(wait(), 3)

            try:
                await start()
                accounts = [await api('POST', '/register', data={'username': 'posts_' + key, 'nickname': key, 'password': 'test-posts-password'}) for key in ('a', 'b', 'c')]
                a, b, c = accounts
                at, bt, ct = [u['token'] for u in accounts]
                aid, bid, cid = [u['user']['id'] for u in accounts]
                await api('GET', '/posts', status=401)
                await api('POST', '/friends/request', at, {'peer': bid})
                await api('POST', '/friends/respond', bt, {'peer': aid, 'accept': True})
                await api('POST', '/friends/request', ct, {'peer': aid})
                for token in (at, bt, ct):
                    ws = await http.ws_connect(base + '/ws'); sockets.append(ws)
                    await ws.send_json({'token': token}); await event(ws, 'ready')

                photo = await api('POST', '/post-photos', at, raw=image())
                fid = photo['id']
                content = await api('GET', '/post-photos/' + fid, at)
                with Image.open(BytesIO(content)) as normalized:
                    assert normalized.format == 'JPEG' and normalized.size == (1600, 1067) and not normalized.getexif()
                await api('GET', '/post-photos/' + fid, bt, status=404)
                await api('POST', '/posts', bt, {'text': 'steal', 'photos': [fid], 'client_id': 'stolen'}, status=400)
                payload = {'text': '<b>Plain text, not HTML</b> 🌤', 'photos': [fid], 'client_id': 'first-post'}
                post = await api('POST', '/posts', at, payload)
                pid = post['id']
                assert (await api('POST', '/posts', at, payload))['id'] == pid
                assert (await event(sockets[1], 'post.changed'))['id'] == pid
                try:
                    await event(sockets[2], 'post.changed')
                    raise AssertionError('Pending friend received a private event')
                except asyncio.TimeoutError: pass
                assert (await api('GET', '/posts', bt))['posts'][0]['id'] == pid
                assert not (await api('GET', '/posts?mine=1', bt))['posts']
                assert not (await api('GET', '/posts', ct))['posts']
                for path in ('/posts/' + pid, '/post-photos/' + fid, '/posts/' + pid + '/comments'):
                    await api('GET', path, ct, status=404)
                await api('GET', '/post-photos/' + fid, bt)
                await api('DELETE', '/posts/' + pid, bt, status=403)
                await api('POST', '/posts', at, {'photos': [fid], 'client_id': 'reuse'}, status=400)
                print('PASS legacy migration, normalized private photos, friend feed, pending/stranger isolation, publish retry and live events')

                for _ in range(2):
                    liked = await api('POST', '/posts/' + pid + '/like', bt, {'liked': True})
                    assert liked['liked'] and liked['like_count'] == 1
                unliked = await api('POST', '/posts/' + pid + '/like', bt, {'liked': False})
                assert not unliked['liked'] and unliked['like_count'] == 0
                await api('POST', '/posts/' + pid + '/like', ct, {'liked': True}, status=404)
                await api('POST', '/posts/' + pid + '/comments', ct, {'text': 'leak', 'client_id': 'c1'}, status=404)
                comment = await api('POST', '/posts/' + pid + '/comments', bt, {'text': 'Nice!', 'client_id': 'comment1'})
                assert (await api('POST', '/posts/' + pid + '/comments', bt, {'text': 'Nice!', 'client_id': 'comment1'}))['id'] == comment['id']
                assert len((await api('GET', '/posts/' + pid + '/comments', at))['comments']) == 1
                assert (await api('GET', '/posts/' + pid, at))['comment_count'] == 1
                await api('DELETE', '/posts/' + pid + '/comments/' + comment['id'], at)
                for bad in ({'text': ''}, {'text': 'x' * 5001}, {'photos': [fid] * 10}, {'text': 1}, {'photos': 'bad'}):
                    await api('POST', '/posts', at, dict(bad, client_id='invalid'), status=400)
                await api('POST', '/post-photos', at, raw=b'<svg/>', status=400)
                await api('POST', '/post-photos', at, raw=b'x' * (2 * 1024 * 1024 + 1), status=413)
                for bad in ('', 'x' * 501):
                    await api('POST', '/posts/' + pid + '/comments', at, {'text': bad, 'client_id': 'bad-comment'}, status=400)
                print('PASS idempotent likes/comments, comment ownership, malformed and oversized content')

                # Same timestamp rows must cross pages without omissions or duplicates.
                for n in range(21):
                    await api('POST', '/posts', at, {'text': 'Page ' + str(n), 'client_id': 'page' + str(n)})
                for n in range(32):
                    await api('POST', '/posts/' + pid + '/comments', bt, {'text': 'Comment ' + str(n), 'client_id': 'page-comment' + str(n)})
                with closing(sqlite3.connect(database)) as db:
                    db.execute('UPDATE posts SET created=123456789')
                    db.execute('UPDATE post_comments SET created=123456789')
                    db.commit()
                one = await api('GET', '/posts', bt)
                two = await api('GET', '/posts?before=' + one['next'], bt)
                assert len(one['posts']) == 20 and len(two['posts']) == 2 and not two['next']
                assert len({p['id'] for p in one['posts'] + two['posts']}) == 22
                one_c = await api('GET', '/posts/' + pid + '/comments', at)
                two_c = await api('GET', '/posts/' + pid + '/comments?before=' + one_c['next'], at)
                assert len(one_c['comments']) == 30 and len(two_c['comments']) == 2
                assert len({p['id'] for p in one_c['comments'] + two_c['comments']}) == 32
                await api('GET', '/posts?before=bad', at, status=400)
                print('PASS stable post and comment pagination with equal timestamps')

                nine = [(await api('POST', '/post-photos', at, raw=image()))['id'] for _ in range(9)]
                photo_only = await api('POST', '/posts', at, {'photos': nine, 'client_id': 'nine-photos'})
                assert [p['id'] for p in photo_only['photos']] == nine and photo_only['text'] == ''
                await api('DELETE', '/posts/' + photo_only['id'], at)
                assert all(not Path(directory, 'moments', fid).exists() for fid in nine)
                print('PASS nine-photo publication without text preserves order and deletes all images')

                for ws in sockets: await ws.close()
                process.terminate();process.wait(timeout=10);await start()
                assert (await api('GET', '/posts/' + pid, bt))['comment_count'] == 32
                await api('GET', '/post-photos/' + fid, bt)
                await api('DELETE', '/posts/' + pid, at)
                await api('GET', '/post-photos/' + fid, at, status=404)
                assert not Path(directory, 'moments', fid).exists()
                with closing(sqlite3.connect(database)) as db:
                    for table, column in [('post_likes', 'post_id'), ('post_comments', 'post_id'), ('post_photos', 'post_id')]:
                        assert db.execute(f'SELECT count(*) FROM {table} WHERE {column}=?', (pid,)).fetchone()[0] == 0
                    assert db.execute('SELECT nickname FROM users WHERE id=?', ('10000001',)).fetchone()[0] == 'Legacy'
                print('PASS restart persistence, author deletion cascades to comments/likes/photos and removes files')
            finally:
                for ws in sockets: await ws.close()
                if process and process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill();process.wait()
                gc.collect()


if __name__ == '__main__': asyncio.run(main())
