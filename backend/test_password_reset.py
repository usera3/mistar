"""Email-only recovery with real temporary DB/password hashes; SMTP captured in memory."""
import asyncio
import gc
import os
import tempfile
from aiohttp.test_utils import TestClient, TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='mistar-email-reset-') as directory:
        os.environ.update(QINGLIAO_DATA=directory, QINGLIAO_REQUIRE_EMAIL='1', QINGLIAO_REQUIRE_E2EE='1', OTP_SECRET='isolated-reset-test-secret', SMTP_PASSWORD='fake-reset-test-password')
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

            async def proof(email, purpose='reset', token='', **extra):
                # Advance only the isolated fixture's resend timestamps, never production state.
                s.write('UPDATE verifications SET created=created-61000 WHERE email=?', (email.strip().lower(),))
                response = await api('/auth/code', dict(email=email, purpose=purpose, **extra), token)
                while s.security.tasks:
                    await asyncio.gather(*tuple(s.security.tasks))
                return dict(email=email, verification_id=response['verification_id'], code=delivered[(email.strip().lower(), purpose)])

            async def register(name, email):
                return await api('/register', dict(username=name, nickname=name, password='initial-test-password', **await proof(email, 'register')))

            alice = await register('reset_alice', 'alice@example.test')
            bob = await register('reset_bob', 'bob@example.test')
            alice_other = await api('/login', dict(username='reset_alice', password='initial-test-password'))
            unknown = await api('/auth/code', dict(purpose='reset', email='unknown@example.test'))
            while s.security.tasks:
                await asyncio.gather(*tuple(s.security.tasks))
            assert set(unknown) == {'verification_id', 'retry_after', 'message'} and ('unknown@example.test', 'reset') not in delivered
            checks += 1
            reset = await proof(' ALICE@EXAMPLE.TEST ')
            assert s.row('SELECT user_id FROM verifications WHERE id=?', (reset['verification_id'],))['user_id'] == alice['user']['id']
            checks += 1
            await api('/auth/code', dict(purpose='reset', email='alice@example.test'), expected=429)
            checks += 1
            await api('/password/reset', dict(reset, email='bob@example.test', new_password='changed-test-password'), expected=400)
            await api('/password/reset', dict(reset, code='invalid', new_password='changed-test-password'), expected=400)
            assert s.row('SELECT attempts FROM verifications WHERE id=?', (reset['verification_id'],))['attempts'] == 2
            checks += 1
            # Neither request contains a username. Possession of the bound email proof selects Alice.
            await api('/password/reset', dict(reset, new_password='changed-test-password'))
            checks += 1
            for token in (alice['token'], alice_other['token']):
                await api('/me', token=token, expected=401, method='GET')
            await api('/me', token=bob['token'], method='GET')
            checks += 1
            await api('/login', dict(username='reset_alice', password='initial-test-password'), expected=401)
            current = await api('/login', dict(username='alice@example.test', password='changed-test-password'))
            assert current['user']['id'] == alice['user']['id']
            checks += 1
            await api('/password/reset', dict(reset, new_password='replay-test-password'), expected=400)
            checks += 1
            other_purpose = await proof('alice@example.test', 'identity_restart', current['token'], password='changed-test-password')
            await api('/password/reset', dict(other_purpose, new_password='purpose-test-password'), expected=400)
            checks += 1
            expired = await proof('bob@example.test')
            s.write('UPDATE verifications SET expires=0 WHERE id=?', (expired['verification_id'],))
            await api('/password/reset', dict(expired, new_password='expired-test-password'), expected=400)
            checks += 1
            locked = await proof('bob@example.test')
            for _ in range(5):
                await api('/password/reset', dict(locked, code='invalid', new_password='locked-test-password'), expected=400)
            await api('/password/reset', dict(locked, new_password='locked-test-password'), expected=400)
            checks += 1
            # Old clients may include this optional field; it cannot redirect recovery to another user.
            compatible = await proof('bob@example.test', username='reset_alice')
            await api('/password/reset', dict(compatible, username='reset_alice', new_password='bob-changed-password'))
            assert (await api('/login', dict(username='bob@example.test', password='bob-changed-password')))['user']['id'] == bob['user']['id']
            assert (await api('/login', dict(username='alice@example.test', password='changed-test-password')))['user']['id'] == alice['user']['id']
            checks += 1
            assert len(list(s.db.execute("SELECT id FROM users WHERE email='alice@example.test'"))) == 1
            assert (await api('/health', method='GET'))['password_reset_email_only']
            checks += 1
            print(f'PASS email-only reset: {checks} cases; normalized unique email, uniform unknown response, cooldown/TTL/attempts/purpose/replay, all-session revocation, account isolation and legacy optional username')
        s.db.close()
        gc.collect()


if __name__ == '__main__':
    asyncio.run(main())
