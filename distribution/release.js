'use strict';
fetch('../releases/latest.json', {cache:'no-store',credentials:'omit',redirect:'error'})
  .then(r=>{if(!r.ok)throw Error();return r.text()})
  .then(text=>{
    if(text.length>8192)throw Error();const r=JSON.parse(text);
    if(r.package!=='cn.qingliao.app'||!/^\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(r.version)||!Number.isInteger(r.size)||r.size<1||r.size>134217728||!Array.isArray(r.notes)||r.notes.length>12)throw Error();
    document.querySelector('#version').textContent=`v${r.version} · ${(r.size/1048576).toFixed(1)} MB · Android 8.0 及以上`;
    const ul=document.querySelector('#notes');ul.replaceChildren();
    r.notes.forEach(note=>{if(typeof note!=='string'||note.length>180)throw Error();const li=document.createElement('li');li.textContent=note;ul.append(li)});
  }).catch(()=>{document.querySelector('#version').textContent='最新版 · Android 8.0 及以上 · 可直接下载'});
