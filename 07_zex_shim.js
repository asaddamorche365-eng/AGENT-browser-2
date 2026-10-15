// chrome.* shim for running the Z EX extension inside an Android WebView
(function () {
  if (window.chrome && window.chrome.__zex) return;
  if (typeof ZBridge === 'undefined') return;
  var ROLE = ZBridge.role();          // 'content' | 'bg' | 'ext'
  var TID = ZBridge.tabId();
  var EXT_ID = 'abcdefghijklmnopabcdefghijklmnop';
  var RES = window.__ZEX_RES || {};
  var seq = 0, rp = {};
  var nid = function () { return ROLE + TID + '-' + (++seq) + '-' + Date.now().toString(36); };
  var clone = function (v) { return v === undefined ? v : JSON.parse(JSON.stringify(v)); };
  var chrome = { __zex: true };

  function Ev() {
    var fns = [];
    return {
      addListener: function (f) { fns.push(f); },
      removeListener: function (f) { var i = fns.indexOf(f); if (i >= 0) fns.splice(i, 1); },
      hasListener: function (f) { return fns.indexOf(f) >= 0; },
      hasListeners: function () { return fns.length > 0; },
      _fire: function () { var a = arguments; fns.slice().forEach(function (f) { try { f.apply(null, a); } catch (e) { console.error(e); } }); },
      _fns: fns
    };
  }
  function ret(cb, producer) {
    if (typeof cb === 'function') {
      Promise.resolve().then(function () { producer(function (v) { try { cb(v); } catch (e) { console.error(e); } }); });
      return;
    }
    return new Promise(function (res) { producer(res); });
  }
  function fail(cb, msg) {
    if (typeof cb === 'function') {
      chrome.runtime.lastError = { message: msg };
      try { cb(); } finally { chrome.runtime.lastError = undefined; }
      return;
    }
    return Promise.reject(new Error(msg));
  }
  function matchPat(p, url) {
    if (p === '<all_urls>') return true;
    var re = new RegExp('^' + p.split('*').map(function (s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }).join('.*') + '$');
    return re.test(url);
  }

  window.__zexReply = function (id, json) {
    var f = rp[id]; if (!f) return; delete rp[id];
    var v; try { v = JSON.parse(json); } catch (e) { v = undefined; }
    f(v === null ? undefined : v);
  };

  // ---------- storage ----------
  var cache = {};
  try { cache = JSON.parse(ZBridge.storageAll()) || {}; } catch (e) {}
  var storageOnChanged = Ev(), localOnChanged = Ev();
  function pick(src, keys) {
    var out = {}, k;
    if (keys == null) { for (k in src) out[k] = clone(src[k]); return out; }
    if (typeof keys === 'string') keys = [keys];
    if (Array.isArray(keys)) { keys.forEach(function (x) { if (x in src) out[x] = clone(src[x]); }); return out; }
    for (k in keys) out[k] = (k in src) ? clone(src[k]) : keys[k];
    return out;
  }
  function makeArea(persist, onCh) {
    var mem = persist ? cache : {};
    return {
      onChanged: onCh,
      get: function (keys, cb) { return ret(cb, function (d) { d(pick(mem, keys)); }); },
      set: function (obj, cb) {
        var ch = {}, clean = {};
        Object.keys(obj || {}).forEach(function (k) {
          if (obj[k] === undefined) return;
          ch[k] = {}; if (k in mem) ch[k].oldValue = clone(mem[k]);
          ch[k].newValue = clone(obj[k]); mem[k] = clone(obj[k]); clean[k] = obj[k];
        });
        if (persist && Object.keys(clean).length) ZBridge.storageSet(JSON.stringify(clean));
        if (Object.keys(ch).length) { storageOnChanged._fire(ch, persist ? 'local' : 'session'); if (persist) localOnChanged._fire(ch); }
        return ret(cb, function (d) { d(); });
      },
      remove: function (keys, cb) {
        keys = [].concat(keys); var ch = {};
        keys.forEach(function (k) { if (k in mem) { ch[k] = { oldValue: clone(mem[k]) }; delete mem[k]; } });
        if (persist) ZBridge.storageRemove(JSON.stringify(keys));
        if (Object.keys(ch).length) { storageOnChanged._fire(ch, persist ? 'local' : 'session'); if (persist) localOnChanged._fire(ch); }
        return ret(cb, function (d) { d(); });
      },
      clear: function (cb) {
        var ch = {}; Object.keys(mem).forEach(function (k) { ch[k] = { oldValue: clone(mem[k]) }; delete mem[k]; });
        if (persist) ZBridge.storageClear();
        if (Object.keys(ch).length) { storageOnChanged._fire(ch, persist ? 'local' : 'session'); if (persist) localOnChanged._fire(ch); }
        return ret(cb, function (d) { d(); });
      },
      getBytesInUse: function (k, cb) { if (typeof k === 'function') cb = k; return ret(cb, function (d) { d(0); }); },
      setAccessLevel: function (o, cb) { return ret(cb, function (d) { d(); }); }
    };
  }
  var local = makeArea(true, localOnChanged);
  chrome.storage = { local: local, sync: local, managed: local, session: makeArea(false, Ev()), onChanged: storageOnChanged };
  window.__zexStorageChanged = function (ch) {
    Object.keys(ch).forEach(function (k) { if ('newValue' in ch[k]) cache[k] = ch[k].newValue; else delete cache[k]; });
    storageOnChanged._fire(ch, 'local'); localOnChanged._fire(ch);
  };

  // ---------- runtime / messaging ----------
  var onMessage = Ev();
  chrome.runtime = {
    id: EXT_ID, lastError: undefined,
    onMessage: onMessage, onInstalled: Ev(), onStartup: Ev(), onConnect: Ev(), onSuspend: Ev(),
    getURL: function (p) {
      p = String(p || '').replace(/^\//, '');
      if (ROLE === 'content' && RES[p] && p !== 'style.css') return RES[p];
      return 'https://zex.local/' + p;
    },
    getManifest: function () { try { return JSON.parse(ZBridge.readAsset('manifest.json')); } catch (e) { return { manifest_version: 3, name: 'Z EX', version: '0' }; } },
    getPlatformInfo: function (cb) { return ret(cb, function (d) { d({ os: 'android', arch: 'arm' }); }); },
    sendMessage: function () {
      var a = [].slice.call(arguments), cb;
      if (typeof a[a.length - 1] === 'function') cb = a.pop();
      var msg = (a.length >= 2 && typeof a[0] === 'string') ? a[1] : a[0];
      var data = JSON.stringify(msg === undefined ? null : msg);
      return ret(cb, function (done) {
        var id = nid();
        if (ROLE === 'bg') { ZBridge.send(id, data); done(undefined); return; }
        rp[id] = done; ZBridge.send(id, data);
      });
    },
    connect: function () {
      return { name: '', postMessage: function () {}, disconnect: function () {}, onMessage: Ev(), onDisconnect: Ev() };
    },
    reload: function () { location.reload(); }
  };
  window.__zexDispatch = function (id, msgJson, senderJson, wantReply) {
    var msg, sender;
    try { msg = JSON.parse(msgJson); sender = JSON.parse(senderJson); } catch (e) { return; }
    var responded = false, isAsync = false;
    var sendResponse = function (r) {
      if (responded) return; responded = true;
      if (wantReply) ZBridge.reply(id, r === undefined ? 'null' : JSON.stringify(r));
    };
    onMessage._fns.slice().forEach(function (f) {
      try { if (f(msg, sender, sendResponse) === true) isAsync = true; } catch (e) { console.error(e); }
    });
    if (!responded && !isAsync && wantReply) ZBridge.reply(id, 'null');
  };

  // ---------- tabs ----------
  var tabsOnUpdated = Ev(), tabsOnRemoved = Ev();
  window.__zexTabUpdated = function (id, ch, tab) { tabsOnUpdated._fire(id, ch, tab); };
  window.__zexTabRemoved = function (id) { tabsOnRemoved._fire(id, {}); };
  function tabList() { try { return JSON.parse(ZBridge.tabsQuery()); } catch (e) { return []; } }
  chrome.tabs = {
    onUpdated: tabsOnUpdated, onRemoved: tabsOnRemoved, onCreated: Ev(), onActivated: Ev(),
    create: function (o, cb) { o = o || {}; return ret(cb, function (d) { d(JSON.parse(ZBridge.tabsCreate(o.url || 'about:blank', o.active !== false))); }); },
    remove: function (ids, cb) { [].concat(ids).forEach(function (i) { ZBridge.tabsRemove(i); }); return ret(cb, function (d) { d(); }); },
    update: function (id, props, cb) {
      if (typeof id === 'object') { cb = props; props = id; id = TID; }
      props = props || {};
      return ret(cb, function (d) { var r = ZBridge.tabsUpdate(id, props.url || '', props.active === true ? 1 : (props.active === false ? 0 : -1)); d(r ? JSON.parse(r) : undefined); });
    },
    reload: function (id, p, cb) { if (typeof id === 'function') { cb = id; id = TID; } ZBridge.tabsReload(id == null ? TID : id); return ret(cb, function (d) { d(); }); },
    get: function (id, cb) { return ret(cb, function (d) { var r = ZBridge.tabsGet(id); d(r ? JSON.parse(r) : undefined); }); },
    getCurrent: function (cb) { return ret(cb, function (d) { var r = ZBridge.tabsGet(TID); d(r ? JSON.parse(r) : undefined); }); },
    query: function (q, cb) {
      q = q || {};
      return ret(cb, function (d) {
        var pats = q.url ? [].concat(q.url) : null;
        d(tabList().filter(function (t) {
          if (q.active !== undefined && t.active !== q.active) return false;
          if (pats && !pats.some(function (p) { return matchPat(p, t.url || ''); })) return false;
          return true;
        }));
      });
    },
    sendMessage: function (id, msg) {
      var a = [].slice.call(arguments, 2), cb;
      if (typeof a[a.length - 1] === 'function') cb = a.pop();
      return ret(cb, function (done) {
        var rid = nid(); rp[rid] = done;
        ZBridge.tabsSend(rid, id, JSON.stringify(msg === undefined ? null : msg));
      });
    },
    captureVisibleTab: function (a, b, c) { return fail(typeof c === 'function' ? c : (typeof b === 'function' ? b : a), 'not supported'); }
  };

  // ---------- scripting ----------
  chrome.scripting = {
    executeScript: function (inj, cb) {
      return ret(cb, function (done) {
        var id = nid(); rp[id] = done;
        var tabId = inj.target && inj.target.tabId;
        var code;
        if (inj.func) {
          code = '(async function(){var r=null;try{r=await (' + inj.func.toString() + ').apply(null,' + JSON.stringify(inj.args || []) + ');}catch(e){}' +
                 'ZBridge.reply(' + JSON.stringify(id) + ',JSON.stringify([{result:r===undefined?null:r}]));})();';
        } else {
          var src = (inj.files || []).map(function (f) { return ZBridge.readAsset(f); }).join('\n;\n');
          code = src + '\n;ZBridge.reply(' + JSON.stringify(id) + ',"[{\\"result\\":null}]");';
        }
        ZBridge.exec(id, tabId, code);
      });
    },
    insertCSS: function (o, cb) { return ret(cb, function (d) { d(); }); }
  };

  // ---------- alarms ----------
  var alarmsOnAlarm = Ev(), alarmMap = {};
  function clearAlarm(n) { var a = alarmMap[n]; if (!a) return false; clearTimeout(a.t); clearInterval(a.i); delete alarmMap[n]; return true; }
  chrome.alarms = {
    onAlarm: alarmsOnAlarm,
    create: function (name, info) {
      if (typeof name === 'object') { info = name; name = ''; }
      info = info || {}; name = name || '';
      clearAlarm(name);
      var delay = info.when != null ? Math.max(0, info.when - Date.now())
        : (info.delayInMinutes != null ? info.delayInMinutes * 60000 : (info.periodInMinutes || 0) * 60000);
      var period = info.periodInMinutes ? info.periodInMinutes * 60000 : 0;
      var rec = { name: name, scheduledTime: Date.now() + delay };
      if (info.periodInMinutes) rec.periodInMinutes = info.periodInMinutes;
      var fire = function () { rec.scheduledTime = Date.now() + period; alarmsOnAlarm._fire({ name: name, scheduledTime: Date.now(), periodInMinutes: rec.periodInMinutes }); };
      var a = { rec: rec };
      a.t = setTimeout(function () {
        fire();
        if (period) a.i = setInterval(fire, period); else delete alarmMap[name];
      }, delay);
      alarmMap[name] = a;
    },
    clear: function (name, cb) { if (typeof name === 'function') { cb = name; name = ''; } var r = clearAlarm(name || ''); return ret(cb, function (d) { d(r); }); },
    clearAll: function (cb) { Object.keys(alarmMap).forEach(clearAlarm); return ret(cb, function (d) { d(true); }); },
    get: function (name, cb) { if (typeof name === 'function') { cb = name; name = ''; } return ret(cb, function (d) { d(alarmMap[name || ''] ? alarmMap[name || ''].rec : undefined); }); },
    getAll: function (cb) { return ret(cb, function (d) { d(Object.keys(alarmMap).map(function (k) { return alarmMap[k].rec; })); }); }
  };

  // ---------- misc stubs ----------
  var noop = function () { var a = [].slice.call(arguments), cb = a[a.length - 1]; return ret(typeof cb === 'function' ? cb : undefined, function (d) { d(); }); };
  chrome.debugger = {
    attach: function () { var a = [].slice.call(arguments); return fail(typeof a[a.length - 1] === 'function' ? a[a.length - 1] : undefined, 'debugger unavailable'); },
    detach: function () { var a = [].slice.call(arguments); return fail(typeof a[a.length - 1] === 'function' ? a[a.length - 1] : undefined, 'debugger unavailable'); },
    sendCommand: function () { var a = [].slice.call(arguments); return fail(typeof a[a.length - 1] === 'function' ? a[a.length - 1] : undefined, 'debugger unavailable'); },
    onEvent: Ev(), onDetach: Ev()
  };
  chrome.permissions = {
    request: function (p, cb) { return ret(cb, function (d) { d(true); }); },
    contains: function (p, cb) { return ret(cb, function (d) { d(true); }); },
    getAll: function (cb) { return ret(cb, function (d) { d({ permissions: [], origins: [] }); }); },
    remove: function (p, cb) { return ret(cb, function (d) { d(true); }); },
    onAdded: Ev(), onRemoved: Ev()
  };
  chrome.idle = { queryState: function (s, cb) { return ret(cb, function (d) { d('active'); }); }, setDetectionInterval: function () {}, onStateChanged: Ev() };
  chrome.power = { requestKeepAwake: function () {}, releaseKeepAwake: function () {} };
  chrome.notifications = {
    create: function (id, o, cb) {
      if (typeof id === 'object') { cb = o; o = id; id = ''; }
      try { ZBridge.toast(((o && o.title) || '') + ' ' + ((o && o.message) || '')); } catch (e) {}
      return ret(cb, function (d) { d(id || 'n'); });
    },
    update: noop, clear: function (id, cb) { return ret(cb, function (d) { d(true); }); }, onClicked: Ev(), onClosed: Ev(), onButtonClicked: Ev()
  };
  chrome.identity = {
    getRedirectURL: function (p) { return 'https://' + EXT_ID + '.chromiumapp.org/' + (p || ''); },
    launchWebAuthFlow: function (o, cb) { return fail(cb, 'identity unavailable'); }
  };
  chrome.proxy = { settings: { set: noop, clear: noop, get: function (o, cb) { return ret(cb, function (d) { d({ value: { mode: 'direct' } }); }); }, onChange: Ev() }, onProxyError: Ev() };
  chrome.webRequest = { onAuthRequired: Ev(), onBeforeRequest: Ev(), onBeforeSendHeaders: Ev(), onCompleted: Ev(), onErrorOccurred: Ev() };
  chrome.windows = {
    update: function (id, info, cb) { return ret(cb, function (d) { d({ id: 1 }); }); },
    getAll: function (o, cb) { if (typeof o === 'function') cb = o; return ret(cb, function (d) { d([{ id: 1, focused: true, tabs: [] }]); }); },
    getCurrent: function (o, cb) { if (typeof o === 'function') cb = o; return ret(cb, function (d) { d({ id: 1, focused: true }); }); },
    create: function (o, cb) { o = o || {}; if (o.url) chrome.tabs.create({ url: [].concat(o.url)[0], active: true }); return ret(cb, function (d) { d({ id: 1 }); }); },
    onRemoved: Ev(), onFocusChanged: Ev(), WINDOW_ID_NONE: -1, WINDOW_ID_CURRENT: -2
  };
  chrome.action = { setBadgeText: noop, setBadgeBackgroundColor: noop, setIcon: noop, setTitle: noop, onClicked: Ev() };
  chrome.browserAction = chrome.action;
  chrome.commands = { onCommand: Ev() };
  chrome.contextMenus = { create: function () {}, removeAll: noop, remove: noop, onClicked: Ev() };
  chrome.declarativeNetRequest = { updateDynamicRules: noop, getDynamicRules: function (cb) { return ret(cb, function (d) { d([]); }); } };
  chrome.i18n = { getMessage: function () { return ''; }, getUILanguage: function () { return navigator.language; } };
  chrome.extension = { getURL: chrome.runtime.getURL, getBackgroundPage: function (cb) { return ret(cb, function (d) { d(null); }); } };

  try { window.chrome = chrome; } catch (e) {}
  if (window.chrome !== chrome) { try { Object.defineProperty(window, 'chrome', { value: chrome, configurable: true, writable: true }); } catch (e) {} }
  try { window.browser = window.browser || chrome; } catch (e) {}

  // ---------- content-role: <link href=zex.local/style.css> -> <style> (X CSP) ----------
  if (ROLE === 'content') {
    var conv = function (n) {
      try {
        if (n && n.tagName === 'LINK') {
          var h = n.getAttribute('href') || '';
          if (h.indexOf('zex.local/style.css') >= 0 && RES['style.css']) {
            var s = document.createElement('style'); s.textContent = RES['style.css']; return s;
          }
        }
      } catch (e) {}
      return n;
    };
    var wrap = function (proto, name, argIdx) {
      var o = proto && proto[name]; if (!o) return;
      proto[name] = function () {
        var a = [].slice.call(arguments);
        for (var i = 0; i < a.length; i++) a[i] = conv(a[i]);
        return o.apply(this, a);
      };
    };
    wrap(Node.prototype, 'appendChild'); wrap(Node.prototype, 'insertBefore');
    wrap(Element.prototype, 'append'); wrap(Element.prototype, 'prepend');
    wrap(DocumentFragment.prototype, 'append'); wrap(DocumentFragment.prototype, 'prepend');
  }

  // ---------- bg / ext: cross-origin fetch via native (no CORS) ----------
  if (ROLE === 'bg' || ROLE === 'ext') {
    var _fetch = window.fetch.bind(window), fp = {};
    window.fetch = function (input, init) {
      try {
        var req = (typeof input === 'string' || input instanceof URL) ? null : input;
        var url = new URL(req ? req.url : String(input), location.href);
        if (!/^https?:$/.test(url.protocol) || url.origin === location.origin) return _fetch(input, init);
        init = init || {};
        var body = init.body !== undefined ? init.body : '';
        if (body && typeof body !== 'string') {
          if (body instanceof URLSearchParams) body = body.toString(); else return _fetch(input, init);
        }
        var h = {}, src = init.headers || (req && req.headers);
        if (src) {
          if (typeof Headers !== 'undefined' && src instanceof Headers) src.forEach(function (v, k) { h[k] = v; });
          else if (Array.isArray(src)) src.forEach(function (p) { h[p[0]] = p[1]; });
          else Object.keys(src).forEach(function (k) { h[k] = src[k]; });
        }
        var method = (init.method || (req && req.method) || 'GET').toUpperCase();
        return new Promise(function (res, rej) {
          var id = nid(); fp[id] = { res: res, rej: rej };
          ZBridge.fetch(id, method, url.href, JSON.stringify(h), body || '');
        });
      } catch (e) { return _fetch(input, init); }
    };
    window.__zexFetchDone = function (id, status, headersJson, b64) {
      var p = fp[id]; if (!p) return; delete fp[id];
      if (!status) { p.rej(new TypeError('Failed to fetch')); return; }
      var bytes = null;
      if (status !== 204 && status !== 205 && status !== 304) {
        var bin = atob(b64), u = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
        bytes = u;
      }
      var hd = {}; try { hd = JSON.parse(headersJson); } catch (e) {}
      try { p.res(new Response(bytes, { status: status, headers: hd })); } catch (e) { p.rej(e); }
    };
  }

  // ---------- bg boot ----------
  if (ROLE === 'bg') {
    window.importScripts = function () {
      for (var i = 0; i < arguments.length; i++) {
        var name = arguments[i], src = ZBridge.readAsset(name);
        if (!src) throw new Error('importScripts failed: ' + name);
        var s = document.createElement('script');
        s.textContent = src + '\n//# sourceURL=' + name;
        document.head.appendChild(s); s.remove();
      }
    };
    window.clients = { claim: function () { return Promise.resolve(); }, matchAll: function () { return Promise.resolve([]); } };
    window.skipWaiting = function () { return Promise.resolve(); };
    var booted = false;
    window.__zexBootBg = function () {
      if (booted) return; booted = true;
      try { importScripts('background.js'); } catch (e) { console.error('bg boot', e); }
      setTimeout(function () {
        if (!cache.__zex_installed) {
          local.set({ __zex_installed: 1 });
          chrome.runtime.onInstalled._fire({ reason: 'install' });
        }
        chrome.runtime.onStartup._fire();
      }, 1000);
    };
  }
})();
