// Synthetic responses only. No account, cookie or school data is used.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/capture.js'), 'utf8');
const origin = 'https://webvpn.ujs.edu.cn';
const prefix = '/http/77726476706e69737468656265737421fae0598869256243300d8db9d6562d';
const timetable = origin + prefix + '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151';
const exams = origin + prefix + '/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105';
const packets = [];
class MockXHR {
  constructor() { this.listeners = new Map(); this.status = 200; this.responseType = ''; }
  open(method, url) { this.method=method; this.url=url; }
  setRequestHeader() {}
  addEventListener(type, callback) { this.listeners.set(type, callback); }
  removeEventListener(type) { this.listeners.delete(type); }
  send() { this.responseText=JSON.stringify(this.fixture || {kbList:[],sjkList:[]}); this.listeners.get('load')?.(); }
}
const sandbox = { URL, URLSearchParams, Request, Headers, Promise, XMLHttpRequest:MockXHR,
  location:{href:origin + prefix + '/kbcx/xskbcx_cxXskbcxIndex.html'}, navigator:{userAgent:'FixtureBrowser'},
  UjsCapture:{postMessage:s=>packets.push(JSON.parse(s))},
  fetch:async()=>new Response(JSON.stringify({items:[],totalCount:0,totalResult:0,totalPage:0}),{status:200}) };
sandbox.window=sandbox;
vm.createContext(sandbox);
vm.runInContext(script,sandbox);
vm.runInContext(script,sandbox); // Installing twice must not duplicate packets.
function xhr(url, method='POST', fixture, body='xnm=2026&xqm=3') {
  const x=new sandbox.XMLHttpRequest(); x.fixture=fixture; x.open(method,url);
  x.setRequestHeader('Cookie','DO_NOT_CAPTURE'); x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
  x.send(body);
}
(async()=>{
  xhr(timetable);
  assert.equal(packets.length,1);
  assert.equal(packets[0].kind,'timetable');
  assert.equal(packets[0].userAgent,'FixtureBrowser');
  assert.equal(packets[0].headers.cookie,undefined);
  xhr(origin+'/cas/login','POST',{},'password=DO_NOT_CAPTURE');
  xhr(timetable.replace(origin,'https://example.com'));
  xhr(timetable,'GET');
  xhr(timetable,'POST',{code:'NOT_LOGIN'});
  assert.equal(packets.length,1);
  xhr(exams,'POST',{items:[],totalCount:0,totalPage:0});
  assert.equal(packets.length,2);
  const response=await sandbox.fetch(new Request(exams,{method:'POST',body:'xnm=2026&xqm=3'}));
  await response.json(); // The hook must not consume the original response.
  await new Promise(resolve=>setTimeout(resolve,50));
  assert.equal(packets.length,3);
  assert.ok(packets.every(p=>!JSON.stringify(p).includes('DO_NOT_CAPTURE')));
  console.log('PASS: query allowlist, login/body exclusion, response schema, header filtering, idempotence, XHR and fetch.');
})().catch(error=>{console.error(error);process.exitCode=1;});
