"""Read-only release feed. Only the OS administrator can publish; no upload API."""
import asyncio
import json
import os
import re
import signal
from pathlib import Path

ORIGIN = 'https://api.claudcode.xyz/xingyu/releases/'
CERTIFICATE = 'a38b07b5c3fbf83f3f054240773df907d3ba5d990119ac74d06bd4358ad2d7f1'


def validate(value):
    if not isinstance(value, dict): raise ValueError('Invalid release')
    code, version = value.get('version_code'), value.get('version')
    if type(code) is not int or not 1 <= code <= 1000000: raise ValueError('Invalid code')
    if not isinstance(version, str) or not re.fullmatch(r'[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}', version): raise ValueError('Invalid version')
    for name in ('size', 'published_at', 'min_sdk'):
        if type(value.get(name)) is not int: raise ValueError('Invalid number')
    if not 1 <= value['size'] <= 128 * 1024 * 1024 or value['min_sdk'] != 26 or value['published_at'] <= 0: raise ValueError('Invalid bounds')
    if type(value.get('schema')) is not int or value['schema'] != 1 or value.get('package') != 'cn.qingliao.app' or value.get('certificate_sha256') != CERTIFICATE: raise ValueError('Invalid identity')
    if value.get('url') != ORIGIN + f'xingyu-{version}-{code}.apk': raise ValueError('Invalid URL')
    if not isinstance(value.get('sha256'), str) or not re.fullmatch('[a-f0-9]{64}', value['sha256']): raise ValueError('Invalid digest')
    notes = value.get('notes')
    if not isinstance(notes, list) or not 1 <= len(notes) <= 12 or any(not isinstance(s, str) or not 1 <= len(s) <= 180 or any(ord(c) < 32 for c in s) for s in notes): raise ValueError('Invalid notes')
    return {k: value[k] for k in ('schema','package','version_code','version','size','published_at','min_sdk','certificate_sha256','url','sha256','notes')}


class Releases:
    def __init__(self, context):
        self.c = context
        self.path = Path(os.environ.get('QINGLIAO_RELEASE_FILE', '/etc/qingliao/release.json'))
        self.current = None
        self.task = None
        self.load()

    def load(self):
        try:
            with self.path.open('rb') as stream: raw = stream.read(8193)
            if len(raw) > 8192: raise ValueError('Large manifest')
            value = validate(json.loads(raw))
            # Mistaken/replayed publication never silently replaces a newer feed.
            if self.current and value['version_code'] <= self.current['version_code']: return False
            self.current = value
            return True
        except (OSError, ValueError, TypeError): return False

    async def refresh(self):
        if self.load():
            event = {'event':'app.update', 'version_code':self.current['version_code']}
            # emit checks session validity; sequential bounded sends avoid fan-out tasks.
            for uid in tuple(self.c['online']): await self.c['emit'](uid, event)

    def request_refresh(self):
        if self.task is None or self.task.done(): self.task = asyncio.create_task(self.refresh())

    async def lifecycle(self, app):
        loop = asyncio.get_running_loop()
        enabled = False
        if hasattr(signal, 'SIGUSR1'):
            try: loop.add_signal_handler(signal.SIGUSR1, self.request_refresh); enabled = True
            except (NotImplementedError, RuntimeError): pass
        yield
        if enabled: loop.remove_signal_handler(signal.SIGUSR1)
        if self.task and not self.task.done():
            self.task.cancel()
            try: await self.task
            except asyncio.CancelledError: pass

    async def get(self, request):
        from aiohttp import web
        return web.json_response({'release':self.current})

    def routes(self, app):
        app.router.add_get('/api/app-release', self.get)
        app.cleanup_ctx.append(self.lifecycle)
