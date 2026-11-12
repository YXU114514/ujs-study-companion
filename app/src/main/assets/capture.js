(function () {
  'use strict';
  if (window.__ujsCaptureInstalled) return;
  window.__ujsCaptureInstalled = true;
  var ORIGIN = 'https://webvpn.ujs.edu.cn';
  var PROXY = '/http/77726476706e69737468656265737421fae0598869256243300d8db9d6562d';
  function identify(url, method) {
    if (String(method || 'GET').toUpperCase() !== 'POST') return null;
    try {
      var u = new URL(String(url), location.href);
      if (u.origin !== ORIGIN) return null;
      if (u.pathname === PROXY + '/kbcx/xskbcx_cxXsgrkb.html') return {kind:'timetable',url:u.href};
      if (u.pathname === PROXY + '/kwgl/kscx_cxXsksxxIndex.html' && u.searchParams.get('doType') === 'query') return {kind:'exams',url:u.href};
    } catch (_) {}
    return null;
  }
  function allowedHeader(name) {
    return /^(content-type|accept|x-requested-with|authorization|[a-z0-9-]*(?:csrf|xsrf)[a-z0-9-]*)$/i.test(name);
  }
  function bodyText(body) {
    if (body == null) return '';
    if (typeof body === 'string') return body;
    if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) return body.toString();
    return null;
  }
  function deliver(meta, body, headers, value) {
    if (!meta || body === null || body.length > 131072 || !value || typeof value !== 'object' || Array.isArray(value)) return;
    if (meta.kind === 'timetable' && (!Array.isArray(value.kbList) || !Array.isArray(value.sjkList))) return;
    if (meta.kind === 'exams' && !Array.isArray(value.items)) return;
    try {
      var packet = JSON.stringify({kind:meta.kind,url:meta.url,page:location.href,userAgent:navigator.userAgent,body:body,headers:headers,data:value});
      if (packet.length <= 4194304 && window.UjsCapture) window.UjsCapture.postMessage(packet);
    } catch (_) {}
  }
  var open = XMLHttpRequest.prototype.open;
  var setHeader = XMLHttpRequest.prototype.setRequestHeader;
  var send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__ujsMeta = identify(url, method);
    this.__ujsHeaders = {};
    return open.apply(this, arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
    if (this.__ujsMeta && allowedHeader(String(name))) this.__ujsHeaders[String(name).toLowerCase()] = String(value);
    return setHeader.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    var xhr = this, meta = this.__ujsMeta, headers = this.__ujsHeaders || {}, text = bodyText(body);
    if (meta && text !== null) {
      var loaded = function () {
        xhr.removeEventListener('load', loaded);
        if (xhr.status < 200 || xhr.status >= 300) return;
        try {
          var value = xhr.responseType === 'json' ? xhr.response : JSON.parse(xhr.responseText);
          deliver(meta, text, headers, value);
        } catch (_) {}
      };
      xhr.addEventListener('load', loaded);
    }
    return send.apply(this, arguments);
  };
  if (window.fetch) {
    var fetchOriginal = window.fetch;
    window.fetch = function (input, init) {
      init = init || {};
      var isRequest = typeof Request !== 'undefined' && input instanceof Request;
      var meta = identify(isRequest ? input.url : input, init.method || (isRequest ? input.method : 'GET'));
      var headers = {}, bodyPromise = null;
      if (meta) {
        try {
          new Headers(init.headers || (isRequest ? input.headers : {})).forEach(function (v,k) { if (allowedHeader(k)) headers[k.toLowerCase()] = v; });
          bodyPromise = Object.prototype.hasOwnProperty.call(init, 'body')
            ? Promise.resolve(bodyText(init.body))
            : (isRequest ? input.clone().text() : Promise.resolve(''));
        } catch (_) { meta = null; }
      }
      var promise = fetchOriginal.apply(this, arguments);
      if (meta && bodyPromise) promise.then(function (response) {
        if (!response.ok) return;
        Promise.all([bodyPromise,response.clone().json()]).then(function (values) {
          deliver(meta,values[0],headers,values[1]);
        }).catch(function () {});
      }).catch(function () {});
      return promise;
    };
  }
})();
