(function () {
  if (window.top !== window) return;
  var NAMES = {
    five_hour: "Session (5h)", seven_day: "Weekly (7d)", seven_day_sonnet: "Sonnet (7d)",
    seven_day_opus: "Opus (7d)", seven_day_omelette: "Design (7d)"
  };
  function win(k) { return k === "five_hour" ? 5 * 3600e3 : /^seven_day/.test(k) ? 7 * 86400e3 : 0; }
  var data = {}, stamp = 0, collapsed = false;

  var box = document.createElement("div");
  box.style.cssText = "position:fixed;left:8px;bottom:84px;z-index:2147483647;width:190px;padding:8px 10px;" +
    "border-radius:12px;font:12px system-ui,sans-serif;color:#faf9f5;background:rgba(38,38,36,.95);" +
    "border:1px solid #3d3d3a;box-shadow:0 2px 10px rgba(0,0,0,.4)";
  document.documentElement.appendChild(box);
  box.onclick = function () { collapsed = !collapsed; render(); };

  function collect(o, k, out) {
    if (!o || typeof o !== "object") return;
    var known = k === "five_hour" || k === "seven_day";
    if ("utilization" in o && (o.utilization !== null || known)) {
      out[k] = { p: o.utilization || 0, at: o.resets_at ? Date.parse(o.resets_at) : 0 };
    }
    for (var key in o) {
      if (o[key] === null && (key === "five_hour" || key === "seven_day")) out[key] = { p: 0, at: 0 };
      else collect(o[key], key, out);
    }
  }

  function apply(text, ts) {
    try {
      var out = {};
      collect(JSON.parse(text), "", out);
      if (Object.keys(out).length) { data = out; stamp = ts || Date.now(); }
    } catch (e) {}
    render();
  }

  function fmt(ms) {
    if (ms <= 0) return "now";
    var h = Math.floor(ms / 3600e3), m = Math.floor(ms / 60e3) % 60;
    return h >= 24 ? Math.floor(h / 24) + "d " + (h % 24) + "h" : h + "h " + m + "m";
  }

  function render() {
    var now = Date.now(), keys = Object.keys(data), worst = 0, sum = [];
    keys.forEach(function (k) {
      if (data[k].p > worst) worst = data[k].p;
      if (k === "five_hour") sum.push("S " + Math.round(data[k].p) + "%");
      if (k === "seven_day") sum.push("W " + Math.round(data[k].p) + "%");
    });
    var html = '<div style="font-weight:700;display:flex;justify-content:space-between"><span>' +
      '<span style="color:#d97757">●</span> Meter' + (collapsed ? " · " + sum.join(" · ") : " ▾") + "</span>" +
      '<span style="font-weight:400;font-size:10px;color:#9c9a92">' +
      (stamp ? Math.round((now - stamp) / 1000) + "s" : "") + "</span></div>";
    if (!keys.length) html += '<div style="margin-top:6px;font-size:10px;color:#9c9a92">waiting for data...</div>';
    if (!collapsed) keys.forEach(function (k) {
      var d = data[k], w = win(k);
      var el = d.at && w ? Math.min(100, Math.max(0, 100 * (1 - (d.at - now) / w))) : -1;
      var col = d.p >= 85 ? "#d0493e" : d.p >= 60 ? "#e3a03c" : "#d97757";
      html += '<div style="margin-top:8px"><div style="display:flex;justify-content:space-between;font-weight:700">' +
        "<span>" + (NAMES[k] || k.replace(/_/g, " ")) + '</span><span style="color:' + col + '">' + d.p.toFixed(1) + "%</span></div>" +
        '<div style="position:relative;height:6px;border-radius:3px;background:#3a3a37;margin-top:4px">' +
        '<div style="height:6px;border-radius:3px;background:' + col + ";width:" + Math.min(100, d.p) + '%"></div>' +
        (el >= 0 ? '<div style="position:absolute;top:0;left:' + el + '%;width:3px;height:6px;background:#faf9f5"></div>' : "") +
        '</div><div style="margin-top:3px;font-size:10px;color:#9c9a92">' +
        (d.at ? "resets in " + fmt(d.at - now) : "") +
        (el >= 0 ? (d.p > el + 5 ? " · ▲ fast" : " · ✓ ok") : "") + "</div></div>";
    });
    box.innerHTML = html;
    box.style.borderColor = worst >= 95 ? "#d0493e" : "#3d3d3a";
  }

  function pull() {
    browser.runtime.sendMessage({ cmd: "get" }).then(function (r) {
      if (r && r.usage && r.ts > stamp) apply(r.usage, r.ts);
    }).catch(function () {});
  }

  function selfFetch() {
    var m = document.cookie.match(/(?:^|; )lastActiveOrg=([^;]+)/);
    if (!m) return;
    browser.runtime.sendMessage({ cmd: "org", org: m[1] }).catch(function () {});
    fetch("/api/organizations/" + m[1] + "/usage", { credentials: "include" })
      .then(function (r) { return r.text(); })
      .then(function (t) {
        apply(t, Date.now());
        browser.runtime.sendMessage({ cmd: "usage", usage: t }).catch(function () {});
      }).catch(function () {});
  }

  setInterval(render, 1000);
  setInterval(pull, 5000);
  setInterval(selfFetch, 30000);
  pull();
  selfFetch();
})();
