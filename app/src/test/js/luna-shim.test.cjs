'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

const shim = fs.readFileSync('app/src/main/assets/webos-shim.js', 'utf8');

function boot(customResponse) {
  const queued = [], subscriptions = new Map(), logs = [], events = [];
  let nextTimer = 1;
  let calls = 0;
  const manifest = {id:'com.example.sample',main:'index.html',title:'Sample webOS'};
  const profile = {profileId:'lg-uq8050psb-webos22',modelName:'UQ8050PSB',
    webOSVersion:'7.0',platformVersion:'webOS TV 22',
    screen:{width:1920,height:1080}};
  const native = {
    getProfile: () => JSON.stringify(profile),
    getAppInfo: () => JSON.stringify(manifest),
    log(level, message) { logs.push([level,message]); },
    request(uri, params) {
      calls++;
      const data=JSON.parse(params);
      if (customResponse) return JSON.stringify(customResponse(uri, data, calls));
      if (uri==='luna://com.palm.connectionmanager/getStatus') return JSON.stringify({
        returnValue:true,subscribed:data.subscribe===true,
        isInternetConnectionAvailable:true,__simulator:'ANDROID_NETWORK'
      });
      if (uri==='luna://com.webos.applicationManager/getAppLoadStatus')
        return JSON.stringify({returnValue:true,exist:data.appId===manifest.id,__simulator:'SIMULATOR_LIBRARY'});
      if (uri==='luna://com.palm.systemservice/time/getSystemTime')
        return JSON.stringify({returnValue:true,utc:1234567,__simulator:'ANDROID_CLOCK',subscribed:!!data.subscribe});
      return JSON.stringify({returnValue:false,errorCode:-1,errorText:'Unsupported Luna API',
        __simulator:'UNSUPPORTED'});
    }
  };
  const document = {
    activeElement: {tagName:'BUTTON',blur() {}},
    addEventListener() {},
    dispatchEvent(e) { events.push(e); return true; }
  };
  const sandbox = {
    AndroidLuna: native, console, document, navigator:{language:'pt-BR'},
    location:{origin:'http://127.0.0.1:33000',pathname:'/apps/com.example.sample/index.html'},
    Intl, WebAssembly, JSON, Map, KeyboardEvent:function (type, init) {this.type=type;this.key=init.key;},
    CustomEvent:function (type, init) {this.type=type;this.detail=init.detail;},
    setTimeout(fn) { queued.push(fn); return 1; },
    clearTimeout() {},
    setInterval(fn, ms) { const id=nextTimer++; subscriptions.set(id,{fn,ms}); return id; },
    clearInterval(id) { subscriptions.delete(id); },
    addEventListener() {},
    crossOriginIsolated: false
  };
  sandbox.window = sandbox;
  vm.runInNewContext(shim,sandbox,{filename:'webos-shim.js',timeout:1000});
  function flush() {
    for(let i=0;i<100&&queued.length;i++) queued.splice(0).forEach(fn=>fn());
    assert.equal(queued.length,0,'callbacks must reach quiescence');
  }
  return {web:sandbox,logs,calls:()=>calls,subscriptions,events,flush};
}

test('webOSTV.js public methods are exposed with correct function/object types',()=>{
  const {web,flush}=boot();
  assert.equal(web.webOS.platform.tv,true);
  assert.equal(typeof web.webOS.service.request,'function');
  assert.equal(typeof web.webOS.deviceInfo,'function');
  assert.equal(typeof web.webOS.systemInfo,'object');
  assert.equal(web.webOS.fetchAppId(),'com.example.sample');
  assert.equal(web.webOS.fetchAppRootPath(),
    'http://127.0.0.1:33000/apps/com.example.sample/');
  let device,info;
  web.webOS.deviceInfo(d=>device=d);
  web.webOS.fetchAppInfo(d=>info=d);
  assert.equal(device,undefined,'callbacks are asynchronous');
  flush();
  assert.equal(device.modelName,'UQ8050PSB');
  assert.equal(info.id,'com.example.sample');
  assert.equal(web.webOS.appInfo.title,'Sample webOS');
});

test('Luna callbacks receive success and onComplete once',()=>{
  const {web,flush}=boot();
  const calls=[];
  const handle=web.webOS.service.request('luna://com.palm.connectionmanager',{
    method:'getStatus',parameters:{},
    onSuccess:r=>calls.push(['ok',r.isInternetConnectionAvailable]),
    onFailure:r=>calls.push(['fail',r.errorText]),
    onComplete:r=>calls.push(['complete',r.returnValue])
  });
  assert.equal(typeof handle.cancel,'function');
  flush();
  assert.deepEqual(calls,[['ok',true],['complete',true]]);
});

test('unsupported DRM fails visibly and never reports fake success',()=>{
  const {web,flush}=boot();
  const calls=[];
  web.webOS.service.request('luna://com.webos.service.drm',{
    method:'load',parameters:{appId:'com.example.sample'},
    onFailure:r=>calls.push(r),
    onSuccess:r=>assert.fail('DRM was incorrectly enabled'),
    onComplete:r=>assert.equal(r.returnValue,false)
  });
  flush();
  assert.equal(calls.length,1);
  assert.equal(calls[0].__simulator,'UNSUPPORTED');
});

test('subscription delivers snapshot, polls only on changes and cancels safely',()=>{
  const {web,flush,subscriptions}=boot();
  const received=[];
  const handle=web.webOS.service.request('luna://com.palm.connectionmanager',{
    method:'getStatus',subscribe:true,onSuccess:r=>received.push(r.isInternetConnectionAvailable)
  });
  flush();
  assert.deepEqual(received,[true]);
  assert.equal(subscriptions.size,1);
  const updater=Array.from(subscriptions.values())[0].fn;
  updater();flush();
  assert.deepEqual(received,[true],'unchanged status must not flood callbacks');
  handle.cancel();
  assert.equal(subscriptions.size,0);
  updater();flush();
  assert.deepEqual(received,[true],'cancelled subscription must remain silent');
});

test('cancel before asynchronous callback suppresses delivery',()=>{
  const {web,flush}=boot();
  let invoked=false;
  const handle=web.webOS.service.request('luna://com.palm.systemservice',{
    method:'time/getSystemTime',onSuccess:()=>invoked=true
  });
  handle.cancel();flush();
  assert.equal(invoked,false);
});

test('PalmServiceBridge uses JSON-encoded callback, handles cancel and unsupported URI',()=>{
  const {web,flush}=boot();
  const b=new web.PalmServiceBridge();
  const received=[];
  b.onservicecallback=x=>received.push(JSON.parse(x));
  b.call('luna://com.webos.applicationManager/getAppLoadStatus',
    JSON.stringify({appId:'com.example.sample'}));
  flush();
  assert.equal(received[0].exist,true);
  b.call('luna://com.palm.unknown/notFound','{}');
  flush();
  assert.equal(received[1].returnValue,false);
  b.call('luna://com.palm.unknown/notFound','{}');
  b.cancel();
  flush();
  assert.equal(received.length,2);
});

test('webOSDev wraps getStatus and refuses LG device identifier',()=>{
  const {web,flush}=boot();
  let connected=false,udidError=false;
  web.webOSDev.connection.getStatus({subscribe:false,onSuccess:r=>{
    connected=r.isInternetConnectionAvailable;
  }});
  web.webOSDev.LGUDID({onSuccess:()=>assert.fail('fake UDID'),onFailure:r=>{
    udidError=r.__simulator==='UNSUPPORTED';
  }});
  flush();
  assert.equal(connected,true);
  assert.equal(udidError,true);
});

test('webOSDev self relaunch event updates launchParams without modifying app data',()=>{
  const {web,events}=boot();
  web.__webOSSimulatorRelaunch({page:'settings',source:'test'});
  assert.equal(web.webOSDev.launchParams().page,'settings');
  assert.equal(events.at(-1).type,'webOSRelaunch');
  assert.equal(events.at(-1).detail.source,'test');
});

test('native response missing boolean returnValue is treated as bridge failure',()=>{
  const {web,flush}=boot(()=>({invalid:true}));
  let failure;
  web.webOS.service.request('luna://com.palm.connectionmanager',{
    method:'getStatus',onFailure:r=>failure=r
  });
  flush();
  assert.equal(failure.returnValue,false);
  assert.equal(failure.__simulator,'ERROR');
});
