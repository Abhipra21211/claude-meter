(function () {
  var P = 'cm_gm_';
  var st = {
    get: function (k, d) { try { var v = localStorage.getItem(P + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(P + k, JSON.stringify(v)); } catch (e) {} },
    del: function (k) { try { localStorage.removeItem(P + k); } catch (e) {} }
  };
  window.unsafeWindow = window;
  window.GM_getValue = function (k, d) { return st.get(k, d); };
  window.GM_setValue = function (k, v) { st.set(k, v); };
  window.GM_deleteValue = function (k) { st.del(k); };
  window.GM_addStyle = function (c) {
    var s = document.createElement('style'); s.textContent = c;
    (document.head || document.documentElement).appendChild(s); return s;
  };
  window.GM_info = { script: { name: 'Claude Counter', version: 'app' } };
  window.GM_xmlhttpRequest = function (o) {
    fetch(o.url, { method: o.method || 'GET', headers: o.headers, body: o.data, credentials: 'include' })
      .then(function (r) { return r.text().then(function (t) {
        if (o.onload) o.onload({ status: r.status, responseText: t, response: t, finalUrl: r.url });
      }); })
      .catch(function (e) { if (o.onerror) o.onerror(e); });
  };
  window.GM = {
    getValue: function (k, d) { return Promise.resolve(st.get(k, d)); },
    setValue: function (k, v) { st.set(k, v); return Promise.resolve(); },
    deleteValue: function (k) { st.del(k); return Promise.resolve(); },
    addStyle: window.GM_addStyle,
    info: window.GM_info
  };
})();
