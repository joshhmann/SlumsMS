/* Staff Bots page (admin/bots; 1.6.2, ticket 157): where the bots are, one map's bots and mobs, one bot's mind, and
   the kill calibration. Alpine component xbots. The live reads go through the login service's staff proxy
   (/api/login/staff/inspect/*, GM level 3+, a sign-in at most 12 h old) to the game's read-only inspect endpoint; the
   map names and calibration.json are the dashboard's export (admin/data/). Read only: nothing here changes the game.
   Everything is shown as text (x-text), never as HTML. #b<cid> opens a bot, #m<id> a map. */
(function () {
  var ROOT = (document.querySelector("[data-root]") || { dataset: { root: "../" } }).dataset.root;
  var API = ROOT + "api/login/staff/inspect/";
  var BOT_MIN = 1000000000;  // bot character ids start here; anything lower is a map id
  var EVERY = 10000;         // auto refresh (the game caches each answer 2 s)

  function get(url) {
    return fetch(url, { credentials: "same-origin", headers: { "X-Slums": "1" } })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) { j.status = r.status; return j; });
      })
      .catch(function () { return { ok: false, error: "Can't reach the website's sign-in service right now.", status: 0 }; });
  }
  function num(v) { return typeof v === "number" ? v.toLocaleString() : v === null || v === undefined ? "" : String(v); }
  function clock(ms) {
    var d = new Date(ms);
    return isNaN(d) || !ms ? "" : d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit", second: "2-digit" });
  }
  function pretty(v) { try { return JSON.stringify(v, null, 2); } catch (e) { return ""; } }

  function xbots() {
    return {
      q: "", state: "loading", msg: "", again: false, auto: false, timer: null, names: {},
      pos: null, top: [], map: null, bot: null, view: "", cal: null, calState: "loading",
      num: num, clock: clock, pretty: pretty,
      init: function () {
        var self = this;
        get("data/nav/names.json").then(function (j) { if (j.status === 200) { delete j.status; self.names = j; } });
        this.loadCal();
        this.positions();
        this.fromHash();
        window.addEventListener("hashchange", function () { self.fromHash(); });
      },
      mapName: function (id) {
        var n = this.names[String(id)];
        return Array.isArray(n) ? (n[0] && n[1] && n[0] !== n[1] ? n[0] + ": " + n[1] : n[1] || n[0]) : "";
      },
      fail: function (j, what) {
        this.again = j.status === 401;
        this.msg = j.status === 401 ? "Your sign-in is over 12 hours old. Sign in again to see the bots."
          : j.status === 404 ? (what === "bot" ? "That bot isn't online." : "Nobody is on that map, and it isn't loaded.")
          : j.status === 503 ? "Bot inspect is off on this server, or the game isn't answering."
          : j.error || "That didn't work.";
      },
      positions: function () {
        var self = this;
        return get(API + "positions").then(function (j) {
          if (!j.ok) { self.state = "error"; self.fail(j); return; }
          self.state = "ok"; self.pos = j;
          var m = j.maps || {};
          self.top = Object.keys(m).map(function (k) { return { id: +k, n: m[k] }; })
            .sort(function (a, b) { return b.n - a.n || a.id - b.id; }).slice(0, 25);
        });
      },
      fromHash: function () {
        var b = /^#b(\d{10})$/.exec(location.hash), m = /^#m(\d{1,9})$/.exec(location.hash);
        if (b) this.openBot(+b[1]); else if (m) this.openMap(+m[1]);
      },
      find: function () {
        var v = this.q.replace(/[\s,]/g, "");
        if (!/^\d{1,10}$/.test(v)) { this.msg = "Type a bot id (1,000,000,000 and up) or a map id."; return; }
        if (+v >= BOT_MIN) this.openBot(+v); else this.openMap(+v);
      },
      go: function (hash) { if (location.hash === hash) this.fromHash(); else location.hash = hash; },
      openMap: function (id) {
        var self = this;
        this.msg = "";
        return get(API + "maps/" + id).then(function (j) {
          if (!j.ok) { self.fail(j, "map"); return; }
          self.map = j; self.bot = null; self.view = "map";
          if (location.hash !== "#m" + id) history.replaceState(null, "", "#m" + id);
        });
      },
      openBot: function (cid) {
        var self = this;
        this.msg = "";
        return get(API + "bots/" + cid).then(function (j) {
          if (!j.ok) { self.fail(j, "bot"); return; }
          self.bot = j; self.view = "bot";
          if (location.hash !== "#b" + cid) history.replaceState(null, "", "#b" + cid);
        });
      },
      close: function () { this.view = ""; this.map = null; this.bot = null; history.replaceState(null, "", location.pathname); },
      refresh: function () {
        this.positions();
        if (this.view === "map" && this.map) this.openMap(this.map.map);
        if (this.view === "bot" && this.bot) this.openBot(this.bot.cid);
      },
      toggleAuto: function () {
        var self = this;
        clearInterval(this.timer); this.timer = null;
        if (this.auto) this.timer = setInterval(function () { if (!document.hidden) self.refresh(); }, EVERY);
      },
      who: function (b) {
        return b ? [b.name, b.level ? "Lv " + b.level : "", b.job].filter(Boolean).join(" · ") : "";
      },
      where: function (b) {
        if (!b || b.map === null || b.map === undefined) return "";
        var n = this.mapName(b.map);
        return (n ? n + " (" + b.map + ")" : String(b.map)) + (b.channel ? ", channel " + b.channel : "")
          + (b.x !== null && b.x !== undefined ? ", x " + b.x + ", y " + b.y : "");
      },
      tick: function (t) {
        return t && typeof t.lastMs === "number" ? t.lastMs.toFixed(2) + " ms last, " + (t.avgMs || 0).toFixed(2) + " ms average" : "";
      },
      count: function (n, what) { return n === null || n === undefined ? "? " + what + "s" : num(n) + " " + what + (n === 1 ? "" : "s"); },
      yes: function (v) { return v === true ? "yes" : v === false ? "no" : ""; },
      lines: function (a) { return Array.isArray(a) ? a.filter(function (x) { return typeof x === "string"; }) : []; },
      bartle: function (x) {
        if (!x) return [];
        return [["Leading", x.leading + (x.flipped ? " (learned its way off its seed)" : "")],
                ["Position", typeof x.x === "number" ? x.x.toFixed(2) + ", " + (x.y || 0).toFixed(2) : ""],
                ["Seed", typeof x.seedX === "number" ? x.seedX.toFixed(2) + ", " + (x.seedY || 0).toFixed(2) : ""],
                ["Plasticity", num(x.plasticity)], ["Rival pull", num(x.rivalPull)], ["Social pull", num(x.socialPull)],
                ["Blend", x.blend]].filter(function (r) { return r[1] !== "" && r[1] !== null && r[1] !== undefined; });
      },
      hp: function (m) { return typeof m.maxHp === "number" && m.maxHp > 0 ? Math.round(100 * m.hp / m.maxHp) + "%" : ""; },
      loadCal: function () {
        var self = this;
        get("data/calibration.json").then(function (j) {
          self.calState = j.status === 200 && Array.isArray(j.buckets) ? "ok" : j.status === 404 ? "none" : "error";
          self.cal = self.calState === "ok" ? j : null;
        });
      },
      ratio: function (b) { return typeof b.ratio === "number" ? b.ratio.toFixed(2) : "not enough yet"; },
      ratioClass: function (b) {
        return typeof b.ratio !== "number" ? "" : b.ratio > 1.25 ? "bt-hi" : b.ratio < 0.8 ? "bt-lo" : "bt-ok";
      }
    };
  }

  window.xbots = xbots;
  document.addEventListener("alpine:init", function () { window.Alpine.data("xbots", xbots); });
})();
