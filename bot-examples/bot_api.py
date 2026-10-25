"""Small synchronous REST helpers. Python standard library, no crypto SDK needed.

from bot_api import BotAPI
bot = BotAPI()  # reads QINGLIAO_BOT_TOKEN
bot.send_text('12345678', '你好', 'hello-1001')
bot.send_html('12345678', '<b>HTML 卡片</b>', 'card-1001')
bot.send_file('12345678', 'photo.png', 'image-1001', kind='image')
"""
import json
import mimetypes
import os
import ssl
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None


class BotAPI:
    def __init__(self,token=None,ca=None):
        self.token=token or os.environ['QINGLIAO_BOT_TOKEN']
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca or Path(__file__).with_name('server_ca.crt'))))

    def request(self,path,body=None,content_type='application/json'):
        if not path.startswith('/api/bot/'):raise ValueError('Bot API path required')
        if isinstance(body,dict):body=json.dumps(body,ensure_ascii=False).encode()
        request=urllib.request.Request('https://47.245.2.254'+path,data=body,headers={'Authorization':'Bearer '+self.token,'Content-Type':content_type})
        with self.opener.open(request,timeout=35) as response:
            value=response.read(3*1024*1024+1)
            if len(value)>3*1024*1024:raise ValueError('Response too large')
            return json.loads(value)

    def send_text(self,peer,text,client_id):return self.request('/api/bot/messages',{'recipient':peer,'client_id':client_id,'type':'text','text':text})
    def send_html(self,peer,html,client_id):return self.request('/api/bot/messages',{'recipient':peer,'client_id':client_id,'type':'html','text':html})
    def send_file(self,peer,path,client_id,kind='file',mime=None):
        path=Path(path)
        if kind not in ('image','voice','file') or not path.is_file() or not 1<=path.stat().st_size<=25*1024*1024:raise ValueError('Invalid file or type; maximum 25 MiB')
        data=path.read_bytes()
        if len(data)>25*1024*1024:raise ValueError('File changed during upload')
        query=urllib.parse.urlencode({'recipient':peer,'name':path.name})
        f=self.request('/api/bot/uploads?'+query,data,mime or mimetypes.guess_type(path.name)[0] or 'application/octet-stream')
        return self.request('/api/bot/messages',{'recipient':peer,'client_id':client_id,'type':kind,'file_id':f['id']})
