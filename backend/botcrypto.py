"""Managed bot endpoints use the same Tink wire format as Android.

Only bot private keys live here; personal identities remain on users' devices.
Bot conversations are processed by this service and are explicitly labelled so.
"""
import base64
import hashlib
import io
import json
import os
import re
import uuid


def compact(value): return json.dumps(value, ensure_ascii=False, separators=(',', ':'))
def b64(value): return base64.b64encode(value).decode()
def un64(value): return base64.b64decode(value, validate=True)
def signed(e): return '\n'.join(e[k] for k in ('context','sender_key','recipient_key','sender_cipher','recipient_cipher')).encode()
def units(value): return len(value.encode('utf-16-le')) // 2


class BotCrypto:
    def __init__(self, c):
        self.c = c
        self.master = None

    def load(self):
        if self.master: return
        import tink
        from tink import aead, hybrid, signature, streaming_aead, secret_key_access
        aead.register(); hybrid.register(); signature.register(); streaming_aead.register()
        self.tink, self.aead, self.hybrid, self.signature, self.streaming = tink, aead, hybrid, signature, streaming_aead
        self.access = secret_key_access.TOKEN
        path = self.c['DATA'] / 'bot-master.key'
        if not path.exists():
            if self.c['row']('SELECT 1 FROM bots LIMIT 1'): raise RuntimeError('Managed bot master key is missing; restore the private backup')
            key = self.write(tink.new_keyset_handle(aead.aead_key_templates.AES256_GCM)).encode()
            try:
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, 'wb') as output: output.write(key); output.flush(); os.fsync(output.fileno())
            except FileExistsError: pass
        self.master = self.read(path.read_text()).primitive(aead.Aead)

    def read(self, text): return self.tink.json_proto_keyset_format.parse(text, self.access)
    def write(self, key): return self.tink.json_proto_keyset_format.serialize(key, self.access)

    def wrap(self, uid, value):
        self.load()
        return b64(self.master.encrypt(compact(value).encode(), ('qingliao:bot:1:' + uid).encode()))

    def unwrap(self, uid, text):
        self.load()
        return json.loads(self.master.decrypt(un64(text), ('qingliao:bot:1:' + uid).encode()))

    def create(self):
        self.load()
        enc = self.tink.new_keyset_handle(self.hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM)
        sig = self.tink.new_keyset_handle(self.signature.signature_key_templates.ECDSA_P256)
        public = {'enc': self.write(enc.public_keyset_handle()), 'sig': self.write(sig.public_keyset_handle())}
        public['key_id'] = hashlib.sha256((public['enc'] + '\n' + public['sig']).encode()).hexdigest()
        return {'enc': self.write(enc), 'sig': self.write(sig)}, public

    def private(self, uid):
        record = self.c['row']('SELECT identity FROM bots WHERE user_id=?', (uid,))
        if not record: self.c['reject']('机器人不存在', 404)
        return self.unwrap(uid+':identity', record['identity'])

    def validate(self, p, fid=''):
        kind, text = p.get('type'), p.get('text', '')
        if kind not in ('text','html','image','voice','file') or not isinstance(text, str) or units(text) > (50000 if kind=='html' else 5000):
            self.c['reject']('消息类型或长度不正确')
        if kind in ('text','html'):
            if not text.strip() or fid or 'file' in p: self.c['reject']('文字消息内容不正确')
            return
        f = p.get('file', {})
        name, mime, size = f.get('name'), f.get('mime'), f.get('size')
        if not isinstance(fid, str) or not re.fullmatch(r'[a-f0-9]{32}', fid) or f.get('id') != fid or \
            not isinstance(name, str) or not 1 <= units(name) <= 180 or any(ord(ch)<32 or 127<=ord(ch)<=159 or ch in '/\\' or '\u202a'<=ch<='\u202e' or '\u2066'<=ch<='\u2069' for ch in name) or \
            not isinstance(mime, str) or len(mime)>100 or not re.fullmatch(r'[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+', mime) or \
            not isinstance(size, int) or isinstance(size, bool) or not 1<=size<=self.c['MAX_FILE'] or \
            not isinstance(f.get('crypto'), str) or not 30<=len(f['crypto'])<=8192 or \
            not re.fullmatch(r'qingliao:attachment:1:[a-fA-F0-9-]{36}', f.get('context','')):
            self.c['reject']('附件格式不正确')

    def seal(self, uid, peer, client_id, inner, fid=''):
        self.load(); self.validate(inner, fid)
        a, b = self.c['security'].identity_for(uid), self.c['security'].identity_for(peer)
        if not a or not b: self.c['reject']('对方尚未建立聊天身份', 409)
        context = f'qingliao:message:1:{uid}:{peer}:{client_id}:{fid}'
        raw, aad = compact(inner).encode(), context.encode()
        e = dict(v=1, context=context, sender_key=a['key_id'], recipient_key=b['key_id'],
            sender_cipher=b64(self.read(a['enc']).primitive(self.hybrid.HybridEncrypt).encrypt(raw, aad)),
            recipient_cipher=b64(self.read(b['enc']).primitive(self.hybrid.HybridEncrypt).encrypt(raw, aad)))
        e['signature'] = b64(self.read(self.private(uid)['sig']).primitive(self.signature.PublicKeySign).sign(signed(e)))
        return e

    def open(self, uid, m):
        self.load()
        sender, recipient, fid = m['sender'], m['recipient'], m.get('file_id') or ''
        if uid not in (sender, recipient) or m['type']!='encrypted': self.c['reject']('消息不存在', 404)
        e = json.loads(m['text']); context = f"qingliao:message:1:{sender}:{recipient}:{m['client_id']}:{fid}"
        a = self.c['security'].identity_for(sender)
        b = self.c['security'].identity_for(recipient)
        if not a or not b or e['v']!=1 or e['context']!=context or e['sender_key']!=a['key_id'] or e['recipient_key']!=b['key_id']:
            self.c['reject']('消息身份无法验证', 409)
        self.read(a['sig']).primitive(self.signature.PublicKeyVerify).verify(un64(e['signature']), signed(e))
        raw = self.read(self.private(uid)['enc']).primitive(self.hybrid.HybridDecrypt).decrypt(
            un64(e['sender_cipher' if uid==sender else 'recipient_cipher']), context.encode())
        inner = json.loads(raw); self.validate(inner, fid)
        result = {'id':m['id'], 'sender':sender, 'recipient':recipient, 'created':m['created'], 'client_id':m['client_id'], 'type':inner['type'], 'text':inner.get('text','')}
        if fid: result['file'] = inner['file']
        return result

    def stream_key(self):
        self.load()
        return self.write(self.tink.new_keyset_handle(self.streaming.streaming_aead_key_templates.AES256_GCM_HKDF_1MB))

    def seal_signal(self, uid, call, signal):
        self.load()
        peer=call['callee'] if call['caller']==uid else call['caller']
        a,b=self.c['security'].identity_for(uid),self.c['security'].identity_for(peer)
        if not a or not b:self.c['reject']('通话身份不可用',409)
        context=f"qingliao:call:1:{call['id']}:{uid}:{peer}"; raw=compact(signal).encode()
        e=dict(v=1,context=context,sender_key=a['key_id'],recipient_key=b['key_id'],
            sender_cipher=b64(self.read(a['enc']).primitive(self.hybrid.HybridEncrypt).encrypt(raw,context.encode())),
            recipient_cipher=b64(self.read(b['enc']).primitive(self.hybrid.HybridEncrypt).encrypt(raw,context.encode())))
        e['signature']=b64(self.read(self.private(uid)['sig']).primitive(self.signature.PublicKeySign).sign(signed(e)))
        return {'envelope':e}

    def open_signal(self, uid, call, signal):
        self.load()
        peer=call['callee'] if call['caller']==uid else call['caller']
        a,b=self.c['security'].identity_for(peer),self.c['security'].identity_for(uid)
        e=signal['envelope'];context=f"qingliao:call:1:{call['id']}:{peer}:{uid}"
        if not a or not b or e['v']!=1 or e['context']!=context or e['sender_key']!=a['key_id'] or e['recipient_key']!=b['key_id']:
            self.c['reject']('通话身份无法验证',409)
        self.read(a['sig']).primitive(self.signature.PublicKeyVerify).verify(un64(e['signature']),signed(e))
        raw=self.read(self.private(uid)['enc']).primitive(self.hybrid.HybridDecrypt).decrypt(un64(e['recipient_cipher']),context.encode())
        return json.loads(raw)

    def encrypting(self, output, key, context):
        self.load()
        return self.read(key).primitive(self.streaming.StreamingAead).new_encrypting_stream(output, context.encode())

    def decrypting(self, source, key, context):
        self.load()
        return self.read(key).primitive(self.streaming.StreamingAead).new_decrypting_stream(source, context.encode())
