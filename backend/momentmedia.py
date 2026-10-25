"""Bounded moment media containers and authenticated attachment transport; no codec service."""
import asyncio
import os
import re
import secrets
import struct
import wave
from pathlib import Path
from aiohttp import web

MIB=1024*1024
LIMITS={'video':(25*MIB,180000),'voice':(5*MIB,60000)}

def probe(path,kind):
    """Validate bounded MP4/M4A or PCM-WAV structure. This is not transcoding/codec decoding."""
    if kind not in LIMITS:raise ValueError('请选择视频或语音')
    size=Path(path).stat().st_size
    if not 32<=size<=LIMITS[kind][0]:raise ValueError('媒体大小不符合限制')
    with open(path,'rb') as f:head=f.read(12)
    if kind=='voice' and head[:4]==b'RIFF' and head[8:]==b'WAVE':
        with wave.open(str(path),'rb') as w:
            if w.getcomptype()!='NONE' or w.getnchannels() not in (1,2) or w.getsampwidth() not in (1,2,3,4) or not 8000<=w.getframerate()<=96000:raise ValueError('请选择 PCM WAV 或 M4A 语音')
            frames=w.getnframes();duration=(frames*1000+w.getframerate()-1)//w.getframerate()
            expected=frames*w.getnchannels()*w.getsampwidth()
            if expected>size or len(w.readframes(frames))!=expected:raise ValueError('音频内容不完整')
        if not 0<duration<=60000:raise ValueError('语音最长 60 秒')
        return {'mime':'audio/wav','duration':duration,'width':0,'height':0}
    if head[4:8]!=b'ftyp':raise ValueError('视频请选择 MP4；语音请选择 M4A 或 PCM WAV')
    durations=[];handlers=[];dimensions=[];count=0;found=set()
    with open(path,'rb') as f:
        def boxes(start,end,depth=0):
            nonlocal count
            if depth>6:raise ValueError('媒体层级过深')
            at=start
            while at<end:
                count+=1
                if count>2048 or end-at<8:raise ValueError('媒体结构不正确')
                f.seek(at);length,tag=struct.unpack('>I4s',f.read(8));header=8
                if length==1:
                    if end-at<16:raise ValueError('媒体结构不完整')
                    length=struct.unpack('>Q',f.read(8))[0];header=16
                if length==0:length=end-at
                if length<header or length>end-at:raise ValueError('媒体长度不正确')
                begin=at+header;finish=at+length;payload=length-header
                if depth==0:found.add(tag)
                if tag==b'ftyp':
                    if depth or payload<8 or payload>128:raise ValueError('MP4 文件头不正确')
                    f.seek(begin);brands=f.read(payload)
                    if not any(x in (b'isom',b'iso2',b'iso5',b'iso6',b'mp41',b'mp42',b'M4A ',b'avc1') for x in [brands[:4]]+[brands[n:n+4] for n in range(8,len(brands),4)]):raise ValueError('不支持的媒体容器')
                elif tag in (b'moov',b'trak',b'mdia',b'minf',b'dinf',b'stbl'):
                    if tag==b'moov' and payload>4*MIB:raise ValueError('媒体索引过大')
                    boxes(begin,finish,depth+1)
                elif tag in (b'mvhd',b'mdhd'):
                    if payload<20:raise ValueError('媒体时长不完整')
                    f.seek(begin);data=f.read(min(payload,32));version=data[0]
                    if version==0:scale=int.from_bytes(data[12:16],'big');ticks=int.from_bytes(data[16:20],'big')
                    elif version==1 and len(data)>=32:scale=int.from_bytes(data[20:24],'big');ticks=int.from_bytes(data[24:32],'big')
                    else:raise ValueError('媒体时长版本不正确')
                    if not scale or not ticks:raise ValueError('媒体时长无效')
                    durations.append((ticks*1000+scale-1)//scale)
                elif tag==b'hdlr':
                    if payload<12:raise ValueError('媒体轨道不完整')
                    f.seek(begin+8);handlers.append(f.read(4))
                elif tag==b'tkhd':
                    if payload<84:raise ValueError('视频信息不完整')
                    f.seek(finish-8);width,height=struct.unpack('>II',f.read(8));dimensions.append((width>>16,height>>16))
                elif tag==b'dref':
                    if payload<8:raise ValueError('媒体引用不完整')
                    f.seek(begin+4);entries=int.from_bytes(f.read(4),'big')
                    if not 1<=entries<=8:raise ValueError('媒体引用不正确')
                    cursor=begin+8
                    for _ in range(entries):
                        if cursor+12>finish:raise ValueError('媒体引用不完整')
                        f.seek(cursor);entry_size,entry_kind,flags=struct.unpack('>I4sI',f.read(12))
                        if entry_kind!=b'url ' or flags&1!=1 or entry_size!=12:raise ValueError('不支持外部媒体引用')
                        cursor+=entry_size
                    if cursor!=finish:raise ValueError('媒体引用不正确')
                elif tag==b'moof':raise ValueError('请选择普通 MP4 文件，不支持分段流')
                at=finish
        boxes(0,size)
    if not {b'ftyp',b'moov',b'mdat'}<=found or not durations:raise ValueError('媒体内容不完整')
    duration=max(durations)
    if not 0<duration<=LIMITS[kind][1]:raise ValueError('视频最长 3 分钟，语音最长 60 秒')
    video=b'vide' in handlers;audio=b'soun' in handlers
    if kind=='video' and not video or kind=='voice' and (not audio or video):raise ValueError('媒体类型与选择不匹配')
    width,height=max(dimensions,default=(0,0),key=lambda pair:pair[0]*pair[1])
    if kind=='video' and not (0<width<=8192 and 0<height<=8192):raise ValueError('视频尺寸不支持')
    return {'mime':'video/mp4' if kind=='video' else 'audio/mp4','duration':duration,'width':width,'height':height}

class MomentMedia:
    def __init__(self,core):self.c=core
    def video(self,fid,uid,owner=False):
        c=self.c
        if not re.fullmatch('[a-f0-9]{32}',fid):c['reject']('视频不存在',404)
        media=c['row']("SELECT * FROM post_photos WHERE id=? AND kind='video'",(fid,))
        if not media or owner and media['owner']!=uid:c['reject']('视频不存在',404)
        if media['post_id']:c['visible_post'](uid,media['post_id'])
        elif media['owner']!=uid:c['reject']('视频不存在',404)
        return media
    async def upload_cover(self,request):
        c=self.c;uid=request['uid'];fid=request.match_info['fid'];self.video(fid,uid,owner=True)
        c['limited']('post-cover:'+uid,30,3600)
        async with c['avatar_slots']:
            data=bytearray()
            async for chunk in c['guard'].chunks(request,c['MAX_AVATAR']):data.extend(chunk)
            jpeg,(width,height)=await asyncio.to_thread(c['prepare_post_photo'],bytes(data),960,82)
            # Recheck after the bounded decoder yields: the post may have been deleted.
            media=self.video(fid,uid,owner=True)
            old=c['row']("SELECT id,width,height FROM post_photos WHERE parent_media=? AND kind='cover'",(fid,))
            if old:return web.json_response(old)  # Immutable and idempotent across publish retries.
            c['guard'].storage_check(request,len(jpeg))
            cover=secrets.token_hex(16);path=c['DATA']/'moments'/cover
            try:
                path.write_bytes(jpeg)
                c['write']("INSERT INTO post_photos(id,owner,post_id,size,width,height,created,kind,parent_media) VALUES(?,?,?,?,?,?,?,'cover',?)",(cover,uid,media['post_id'],len(jpeg),width,height,media['created'],fid))
            except BaseException:path.unlink(missing_ok=True);raise
        return web.json_response({'id':cover,'width':width,'height':height})
    async def cover(self,request):
        c=self.c;fid=request.match_info['fid'];self.video(fid,request['uid'])
        cover=c['row']("SELECT id FROM post_photos WHERE parent_media=? AND kind='cover'",(fid,))
        if not cover:c['reject']('视频封面不存在',404)
        path=c['DATA']/'moments'/cover['id']
        if not path.is_file():c['reject']('视频封面不存在',404)
        return web.FileResponse(path,headers={'Content-Type':'image/jpeg','Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})
    async def upload(self,request):
        c=self.c;kind=request.query.get('kind','');uid=request['uid']
        if kind not in LIMITS:c['reject']('请选择视频或语音')
        maximum=LIMITS[kind][0]
        if request.content_length is not None and request.content_length>maximum:c['reject']('视频不能超过 25 MB，语音不能超过 5 MB',413)
        c['limited']('post-media:'+uid,30,3600)
        fid=secrets.token_hex(16);path=c['DATA']/'moments'/fid;temp=path.with_suffix('.upload');size=0
        try:
            with temp.open('xb') as output:
                async for chunk in c['guard'].chunks(request,maximum):
                    size+=len(chunk)
                    if size>maximum:c['reject']('媒体文件过大',413)
                    output.write(chunk)
            try:info=await asyncio.wait_for(asyncio.to_thread(probe,temp,kind),10)
            except (ValueError,wave.Error,EOFError,struct.error) as e:c['reject'](str(e)[:100])
            except asyncio.TimeoutError:c['reject']('媒体检查超时，请选择较小的文件')
            c['guard'].storage_check(request,size)
            os.replace(temp,path)
            c['write']('INSERT INTO post_photos(id,owner,size,width,height,created,kind,mime,duration) VALUES(?,?,?,?,?,?,?,?,?)',(fid,uid,size,info['width'],info['height'],c['now'](),kind,info['mime'],info['duration']))
            return web.json_response(dict(info,id=fid,kind=kind,size=size))
        except BaseException:
            path.unlink(missing_ok=True);raise
        finally:temp.unlink(missing_ok=True)
    async def download(self,request):
        c=self.c;uid=request['uid'];fid=request.match_info['fid']
        if not re.fullmatch('[a-f0-9]{32}',fid):c['reject']('媒体不存在',404)
        media=c['row']("SELECT * FROM post_photos WHERE id=? AND kind IN ('video','voice')",(fid,))
        if not media:c['reject']('媒体不存在',404)
        if media['post_id']:c['visible_post'](uid,media['post_id'])
        elif media['owner']!=uid:c['reject']('媒体不存在',404)
        path=c['DATA']/'moments'/fid
        if not path.is_file():c['reject']('媒体不存在',404)
        return web.FileResponse(path,headers={'Content-Type':media['mime'],'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})
    def routes(self,app):
        app.add_routes([web.post('/api/post-media',self.upload),web.get('/api/post-media/{fid}',self.download),web.post('/api/post-media/{fid}/cover',self.upload_cover),web.get('/api/post-media/{fid}/cover',self.cover)])
