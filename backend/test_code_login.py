"""Isolated email login proofs and atomic session creation; SMTP never leaves this process."""
import asyncio
import gc
import os
import sqlite3
import tempfile
from aiohttp.test_utils import TestClient, TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='mistar-code-login-') as directory:
        os.environ.update(QINGLIAO_DATA=directory, QINGLIAO_REQUIRE_EMAIL='1', QINGLIAO_REQUIRE_E2EE='1', OTP_SECRET='isolated-code-login-secret', SMTP_PASSWORD='fake-code-login-password')
        import server as s
        delivered = {}
        s.security.send_mail = lambda email, code, purpose: delivered.update({(email, purpose): code})
        checks = 0
        async with TestClient(TestServer(s.app)) as client:
            async def api(path, body=None, token='', expected=200, method='POST'):
                response = await client.request(method, '/api' + path, json=body, headers={'Authorization': 'Bearer ' + token})
                result = await response.json()
                assert response.status == expected, (path, response.status, result)
                return result

            async def proof(email, purpose='login'):
                s.write('UPDATE verifications SET created=created-61000 WHERE email=?', (email.strip().lower(),))
                result = await api('/auth/code', dict(email=email, purpose=purpose))
                while s.security.tasks:
                    await asyncio.gather(*tuple(s.security.tasks))
                return dict(email=email, verification_id=result['verification_id'], code=delivered[(email.strip().lower(), purpose)])

            async def register(name, email):
                return await api('/register', dict(username=name, nickname=name, password='initial-login-password', **await proof(email, 'register')))

            alice = await register('code_alice', 'alice@example.test')
            bob = await register('code_bob', 'bob@example.test')
            aid = alice['user']['id']
            unknown = await api('/auth/code', dict(purpose='login', email='unknown@example.test'))
            assert set(unknown) == {'verification_id', 'retry_after', 'message'}
            while s.security.tasks:
                await asyncio.gather(*tuple(s.security.tasks))
            assert ('unknown@example.test', 'login') not in delivered
            checks += 1
            login = await proof(' ALICE@EXAMPLE.TEST ')
            assert s.row('SELECT user_id FROM verifications WHERE id=?', (login['verification_id'],))['user_id'] == aid
            await api('/auth/code', dict(purpose='login', email='alice@example.test'), expected=429)
            checks += 1
            await api('/login/code', dict(login, email='bob@example.test'), expected=400)
            await api('/login/code', dict(login, code='invalid'), expected=400)
            checks += 1
            before = s.row('SELECT password FROM users WHERE id=?', (aid,))['password']
            session = await api('/login/code', login)
            assert session['user']['id'] == aid and session['token']
            assert s.row('SELECT password FROM users WHERE id=?', (aid,))['password'] == before
            await api('/me', token=session['token'], method='GET')
            await api('/me', token=alice['token'], method='GET')
            checks += 1
            await api('/login/code', login, expected=400)
            checks += 1
            reset = await proof('alice@example.test', 'reset')
            await api('/login/code', reset, expected=400)
            login = await proof('alice@example.test')
            await api('/password/reset', dict(login, new_password='must-not-change-password'), expected=400)
            checks += 1
            concurrent = await proof('alice@example.test')
            async def attempt():
                response = await client.post('/api/login/code', json=concurrent)
                await response.read()
                return response.status
            assert sorted(await asyncio.gather(attempt(), attempt())) == [200, 400]
            checks += 1
            s.limits.clear()  # Remaining independent fixtures do not consume the earlier send-IP budget.
            expired = await proof('alice@example.test')
            s.write('UPDATE verifications SET expires=0 WHERE id=?', (expired['verification_id'],))
            await api('/login/code', expired, expected=400)
            checks += 1
            exhausted = await proof('alice@example.test')
            for _ in range(5): await api('/login/code', dict(exhausted, code='invalid'), expected=400)
            await api('/login/code', exhausted, expected=400)
            checks += 1
            old = await proof('alice@example.test')
            new = await proof('alice@example.test')
            await api('/login/code', old, expected=400)
            await api('/login/code', new)
            checks += 1
            s.limits.clear()
            rollback = await proof('alice@example.test')
            total = s.row('SELECT count(*) AS n FROM sessions')['n']
            s.db.execute("CREATE TRIGGER code_login_fail BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT,'synthetic session failure'); END")
            s.db.commit()
            vid = s.security.verify(rollback, 'login', 'alice@example.test', aid)
            try:
                s.new_session(aid, verification_id=vid)
                raise AssertionError('Session failure must roll back')
            except sqlite3.IntegrityError:
                pass
            assert s.row('SELECT used FROM verifications WHERE id=?', (vid,))['used'] == 0
            assert s.row('SELECT count(*) AS n FROM sessions')['n'] == total
            s.db.execute('DROP TRIGGER code_login_fail');s.db.commit()
            await api('/login/code', rollback)
            checks += 1
            denied = await proof('bob@example.test')
            bid = bob['user']['id']
            s.write('INSERT INTO account_controls(uid,banned_until,posting_until,reason,actor,updated) VALUES(?,?,?,?,?,?)', (bid, -1, 0, 'synthetic ban', aid, s.now()))
            await api('/login/code', denied, expected=403)
            checks += 1
            bot = await api('/developer/bots', dict(nickname='Synthetic bot', description='Synthetic login isolation'), session['token'], expected=201)
            botid = bot['bot']['id']
            s.write('UPDATE users SET email=? WHERE id=?', ('robot@example.test', botid))
            await api('/auth/code', dict(purpose='login', email='robot@example.test'))
            while s.security.tasks: await asyncio.gather(*tuple(s.security.tasks))
            assert ('robot@example.test', 'login') not in delivered
            checks += 1
            for _ in range(8): latest = s.new_session(aid)
            assert s.row('SELECT count(*) AS n FROM sessions WHERE user_id=?', (aid,))['n'] == 5
            await api('/me', token=latest['token'], method='GET')
            assert (await api('/health', method='GET'))['email_code_login']
            checks += 1
            print(f'PASS code login: {checks} cases; purpose/UID/email binding, no enumeration, cooldown/expiry/attempts/replay, concurrency, atomic rollback, ban/bot exclusion, unchanged password and bounded sessions')
        s.db.close();gc.collect()


if __name__ == '__main__': asyncio.run(main())
