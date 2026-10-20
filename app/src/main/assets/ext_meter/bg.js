var org = null;
var last = null;

function getOrg() {
  return browser.cookies.get({ url: "https://claude.ai", name: "lastActiveOrg" })
    .then(function (c) { return c ? c.value : org; })
    .catch(function () { return org; });
}

function send(m) {
  try { browser.runtime.sendNativeMessage("browser", m).catch(function () {}); } catch (e) {}
}

function save(t) {
  last = { usage: t, ts: Date.now() };
  send({ usage: t });
}

function poll() {
  getOrg().then(function (o) {
    if (!o) return;
    return fetch("https://claude.ai/api/organizations/" + o + "/usage", { credentials: "include" })
      .then(function (r) { return r.text(); })
      .then(save);
  }).catch(function () {});
}

browser.runtime.onMessage.addListener(function (m) {
  if (!m) return;
  if (m.cmd === "get") return Promise.resolve(last);
  if (m.cmd === "org") org = m.org;
  if (m.cmd === "usage") save(m.usage);
  if (m.cmd === "refresh") poll();
});

poll();
setInterval(poll, 30000);
