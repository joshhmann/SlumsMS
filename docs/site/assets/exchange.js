/* The exchange page (market/exchange.html): a trading screen for every ticker (NX, LEAF, CHAOS, WS, ELIXIR), on Alpine
   and live.js. The picked ticker lives in Alpine.store("xc") and the page's #hash (exchange.html#leaf).
   - xbook: the ticker tabs, the ticker header, candles (api/market/exchange/history, 30 days of hours), depth, the book
     and the last trades (api/market/exchange/book every 10 s).
   - xmine: the order form (preview, then confirm with the 60 s token) and my orders with cancel (.../me).
   A write is a request to the game (exchange_desk.py): POST it, get {id}, then ask answer/<id> until the game answers.
   Units come from the game (unit, unit_label, per): NX is priced per 1,000 NX, items per item. */
(function () {
  var ROOT = (document.querySelector("[data-mk-root]") || { dataset: { mkRoot: "../" } }).dataset.mkRoot;
  var API = ROOT + "api/market/exchange/";
  var HOUR = 3600000, DAY = 24 * HOUR;
  var nf = new Intl.NumberFormat("en-US");
  function fmt(n) { return n === null || n === undefined ? "-" : nf.format(n); }
  function short(v) {
    if (v === null || v === undefined) return "-";
    var a = Math.abs(v);
    return a >= 1e9 ? (v / 1e9).toFixed(2) + "b" : a >= 1e6 ? (v / 1e6).toFixed(a >= 1e7 ? 1 : 3) + "m"
      : a >= 1e4 ? Math.round(v / 1e3) + "k" : nf.format(Math.round(v));
  }
  function when(iso) {
    if (!iso) return "";
    var d = new Date(iso);
    return isNaN(d) ? "" : d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
  function clock(iso) { var d = new Date(iso); return isNaN(d) ? "" : d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }); }
  function pct(p) { return p === null || p === undefined ? "-" : (p > 0 ? "▲ +" : p < 0 ? "▼ " : "") + p.toFixed(2) + "%"; }
  function dirOf(p) { return p === null || p === undefined || Math.abs(p) < 0.005 ? "flat" : p > 0 ? "up" : "down"; }
  function icon(t) { return t && t.item_id ? ROOT + "database/icons/items/" + t.item_id + ".png" : ROOT + "assets/brand/badge-180.png"; }
  // Object.assign would read the getters once; copy them as getters
  function extend(base, more) { return Object.defineProperties(base, Object.getOwnPropertyDescriptors(more)); }

  // what the game's codes mean for the player (ExchangeSite.java)
  var CODES = {
    STALE: "That took too long to reach the game. Try again.",
    HALTED: "The exchange is paused by a GM right now.",
    UNAVAILABLE: "The exchange isn't running right now. Try again later.",
    LOG_OUT_FIRST: "You're logged in to the game on another character. Pick the one you're playing, or log out first.",
    LOG_IN_FIRST: "To sell items, log in to the game on that character first: the items come out of its bags.",
    INSUFFICIENT_FUNDS: "Not enough to cover it: mesos on that character, NX on your account, or tradeable items in its bags.",
    NOT_OWNER: "That character isn't on your account.",
    NOT_FOUND: "That order wasn't found (it may have already closed).",
    LIMIT_OPEN: function () { return "You already have " + lim().max_open_per_ticker + " open orders on this ticker. Cancel one first."; },
    LIMIT_DAY: function () { return "That's " + lim().per_day + " orders today. Try again tomorrow."; },
    COOLDOWN: function () { return "Wait " + lim().cooldown_sec + " seconds between orders."; },
    DUPLICATE: "That order was already sent.",
    TOKEN_EXPIRED: "That preview expired. Preview the order again.",
    TRY_AGAIN: "You're changing channel, logging out or in the Cash Shop. Try again in a moment.",
    NO_ANSWER: "The game didn't answer. Try again later.",
    ERROR: "Something went wrong in the game. Try again later."
  };
  function said(a) {
    var c = CODES[a.code];
    if (c) return typeof c === "function" ? c() : c;
    return a.message ? "The game said: " + a.message + "." : "That didn't work.";
  }

  function post(path, body) {
    return fetch(API + path, { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().catch(function () { return { ok: false, error: "The server said no (" + r.status + ")." }; }); })
      .catch(function () { return { ok: false, error: "Can't reach the server right now." }; });
  }
  // the game answers within a couple of seconds; give up after 2 minutes
  function answer(id, onSlow) {
    var start = Date.now(), slow = false;
    return new Promise(function (done) {
      (function ask() {
        fetch(API + "answer/" + encodeURIComponent(id), { credentials: "same-origin", cache: "no-store" })
          .then(function (r) { return r.json(); })
          .then(function (j) {
            if (j && j.ok && !j.pending) return done(j.answer);
            if (j && j.ok === false) return done({ ok: false, code: "", message: j.error || "" });
            if (!slow && Date.now() - start > 15000) { slow = true; if (onSlow) onSlow(); }
            if (Date.now() - start > 120000) return done({ ok: false, code: "NO_ANSWER" });
            setTimeout(ask, 1500);
          })
          .catch(function () { setTimeout(ask, 3000); });
      })();
    });
  }

  function store() { return window.Alpine.store("xc"); }
  var LIM = { fee_bps_each_side: 100, max_open_per_ticker: 5, per_day: 20, per_day_scope: "all", cooldown_sec: 10, expiry_hours: 72 };
  function lim() { return store().limits || LIM; }

  function xbook() {
    var base = window.live(API + "book", 10);
    var init = base.init;
    return extend(base, {
      fmt: fmt, short: short, when: when, clock: clock, pct: pct, dirOf: dirOf, icon: icon,
      range: "3d", ranges: [["24h", DAY], ["3d", 3 * DAY], ["7d", 7 * DAY], ["30d", 30 * DAY]],
      candles: [], candlesFor: "",
      init: function () {
        var self = this;
        init.call(this);
        this.$watch("data", function () { self.sync(); });
        this.$watch("$store.xc.tk", function () { self.sync(); self.loadHistory(); });
        window.addEventListener("hashchange", function () { self.pickFromHash(); });
        this.pickFromHash();
        this.loadHistory();
        this.histTimer = setInterval(function () { if (!document.hidden) self.loadHistory(); }, 60000);
      },
      pickFromHash: function () {
        var h = (location.hash || "").replace("#", "").toUpperCase();
        if (/^[A-Z]{1,8}$/.test(h)) store().tk = h;
      },
      pick: function (id) {
        store().tk = id;
        try { history.replaceState(null, "", id === "NX" ? location.pathname : "#" + id.toLowerCase()); } catch (e) { /* fine */ }
      },
      sync: function () {  // share the picked ticker's rules and the limits with the order form
        if (this.data && this.data.limits) store().limits = this.data.limits;
        if (this.list.length && !this.list.some(function (x) { return x.id === store().tk; })) store().tk = this.list[0].id;
        store().meta = this.cur;
      },
      loadHistory: function () {
        var self = this, id = store().tk;
        fetch(API + "history?ticker=" + encodeURIComponent(id), { credentials: "same-origin", cache: "no-store" })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (j) { if (j && j.ok && j.ticker === store().tk) { self.candles = j.candles || []; self.candlesFor = j.ticker; } })
          .catch(function () { /* keep what we had */ });
      },
      get list() { return (this.data && this.data.tickers) || []; },
      get halted() { return !!(this.data && this.data.halted); },
      get cur() {
        var id = store().tk, l = this.list;
        for (var i = 0; i < l.length; i++) if (l[i].id === id) return l[i];
        return l[0] || null;
      },
      get unit() { return (this.cur && this.cur.unit_label) || ""; },
      get qtyLabel() { return (this.cur && this.cur.qty_label) || "NX"; },
      get bids() { return (this.cur && this.cur.bids) || []; },
      get asks() { return (this.cur && this.cur.asks) || []; },
      get asksDown() { return this.asks.slice().reverse(); },  // the lowest ask sits next to the spread
      get trades() {  // oldest first, with the time in ms
        return ((this.cur && this.cur.trades) || []).map(function (t) { return { t: t.t, at: new Date(t.t).getTime(), price: t.price, qty: t.qty }; })
          .filter(function (t) { return !isNaN(t.at); }).sort(function (a, b) { return a.at - b.at; });
      },
      get dir() { return dirOf(this.cur && this.cur.change_24h_pct); },
      mid: function () { var c = this.cur; return c && c.bid && c.ask ? Math.round((c.bid + c.ask) / 2) : null; },
      spread: function () {
        var c = this.cur;
        if (!c) return "-";
        if (!c.bid || !c.ask) return c.ask ? "no buyers" : c.bid ? "no sellers" : "-";
        return fmt(c.ask - c.bid);
      },
      bar: function (l, side) {
        var all = this.asks.concat(this.bids), max = Math.max.apply(null, all.map(function (x) { return x.qty; }).concat([1]));
        var w = Math.round(l.qty / max * 100);
        return "background: linear-gradient(to left, var(--xc-" + side + "-bg) " + w + "%, transparent " + w + "%)";
      },
      recent: function () {
        var t = this.trades;
        return t.map(function (x, i) { return { t: x.t, price: x.price, qty: x.qty, dir: i && x.price > t[i - 1].price ? "up" : i && x.price < t[i - 1].price ? "down" : "" }; })
          .reverse().slice(0, 14);
      },
      lim: lim,
      feePct: function () { return (lim().fee_bps_each_side / 100) + "%"; },
      sizeRule: function (t) {
        var q = t.unit === "item" ? " per order" : " per order, in lots of " + fmt(t.lot);
        return "priced in " + t.unit_label + "; " + fmt(t.min_qty) + " to " + fmt(t.max_qty) + " " + t.qty_label + q + ".";
      },
      limitRule: function () {
        var l = lim();
        return l.max_open_per_ticker + " open orders per ticker, " + l.per_day + " new orders a day"
          + (l.per_day_scope === "ticker" ? " per ticker" : " across all tickers") + ", " + l.cooldown_sec + " seconds apart.";
      },
      expiryText: function () { var h = lim().expiry_hours; return h % 24 === 0 && h > 48 ? h / 24 + " days" : h + " hours"; },
      usePrice: function (price) { window.dispatchEvent(new CustomEvent("xc-price", { detail: price })); },

      // candles: the game's hours that traded; with none yet, the last trades as a line
      chart: function () {
        var W = 640, H = 280, L = 6, R = 70, T = 10, VOL = 46, B = 20, self = this, span = DAY * 3;
        this.ranges.forEach(function (r) { if (r[0] === self.range) span = r[1]; });
        var now = Date.now(), x0 = now - span, x1 = now + HOUR;
        var cs = (this.candlesFor === store().tk ? this.candles : []).map(function (c) {
          return { at: new Date(c.t).getTime(), o: c.o, h: c.h, l: c.l, c: c.c, v: c.v, n: c.n, t: c.t };
        }).filter(function (c) { return c.at >= x0 - HOUR && !isNaN(c.at); });
        var rate = this.cur && this.cur.rate;
        var line = cs.length ? [] : this.trades.filter(function (t) { return t.at >= x0; });
        if (!cs.length && !line.length)
          return '<text class="xc-ax" x="' + (W - R) / 2 + '" y="' + H / 2 + '" text-anchor="middle">No trades in this range yet</text>';
        var ps = [];
        cs.forEach(function (c) { ps.push(c.h, c.l); });
        line.forEach(function (t) { ps.push(t.price); });
        if (rate) ps.push(rate);
        var lo = Math.min.apply(null, ps), hi = Math.max.apply(null, ps), pad = (hi - lo) * 0.1 || hi * 0.03 || 1;
        lo = Math.max(0, lo - pad); hi += pad;
        var top = H - B - VOL - 6;
        function X(v) { return L + (v - x0) / (x1 - x0) * (W - L - R); }
        function Y(v) { return T + (hi - v) / (hi - lo) * (top - T); }
        var out = "";
        for (var i = 0; i <= 4; i++) {  // the price grid on the right, like a trading screen
          var v = lo + (hi - lo) * i / 4;
          out += '<line class="xc-grid" x1="' + L + '" x2="' + (W - R) + '" y1="' + Y(v).toFixed(1) + '" y2="' + Y(v).toFixed(1) + '"/>'
            + '<text class="xc-ax" x="' + (W - R + 6) + '" y="' + (Y(v) + 4).toFixed(1) + '">' + short(v) + '</text>';
        }
        var last;
        if (cs.length) {
          var w = Math.max(1.5, (W - L - R) / (span / HOUR) * 0.7), vmax = Math.max.apply(null, cs.map(function (c) { return c.v; }).concat([1]));
          cs.forEach(function (c) {
            var cx = X(c.at + HOUR / 2), cls = c.c >= c.o ? "up" : "down";
            var y1 = Y(Math.max(c.o, c.c)), y2 = Y(Math.min(c.o, c.c));
            var tip = '<title>' + when(c.t) + ": open " + fmt(c.o) + ", high " + fmt(c.h) + ", low " + fmt(c.l) + ", close " + fmt(c.c)
              + ", " + fmt(c.v) + " " + self.qtyLabel + " in " + c.n + " trade" + (c.n === 1 ? "" : "s") + '</title>';
            out += '<g class="xc-candle ' + cls + '">' + tip
              + '<line x1="' + cx.toFixed(1) + '" x2="' + cx.toFixed(1) + '" y1="' + Y(c.h).toFixed(1) + '" y2="' + Y(c.l).toFixed(1) + '"/>'
              + '<rect x="' + (cx - w / 2).toFixed(1) + '" y="' + y1.toFixed(1) + '" width="' + w.toFixed(1) + '" height="' + Math.max(1, y2 - y1).toFixed(1) + '"/>'
              + '<rect class="xc-vol" x="' + (cx - w / 2).toFixed(1) + '" y="' + (H - B - c.v / vmax * VOL).toFixed(1) + '" width="' + w.toFixed(1)
              + '" height="' + (c.v / vmax * VOL).toFixed(1) + '"/></g>';
          });
          last = cs[cs.length - 1].c;
        } else {
          var pts = line.map(function (t) { return X(t.at).toFixed(1) + "," + Y(t.price).toFixed(1); });
          out += '<polyline class="xc-line ' + this.dir + '" points="' + pts.join(" ") + '"/>';
          line.forEach(function (t) {
            out += '<circle class="xc-dot" cx="' + X(t.at).toFixed(1) + '" cy="' + Y(t.price).toFixed(1) + '" r="3"><title>'
              + fmt(t.price) + " for " + fmt(t.qty) + " " + self.qtyLabel + ", " + when(t.t) + '</title></circle>';
          });
          last = line[line.length - 1].price;
        }
        if (rate) out += '<line class="xc-rateline" x1="' + L + '" x2="' + (W - R) + '" y1="' + Y(rate).toFixed(1) + '" y2="' + Y(rate).toFixed(1) + '"/>';
        var ly = Y(last);
        out += '<line class="xc-lastline ' + this.dir + '" x1="' + L + '" x2="' + (W - R) + '" y1="' + ly.toFixed(1) + '" y2="' + ly.toFixed(1) + '"/>'
          + '<rect class="xc-tag ' + this.dir + '" x="' + (W - R + 2) + '" y="' + (ly - 9).toFixed(1) + '" width="' + (R - 4) + '" height="18" rx="3"/>'
          + '<text class="xc-tagt" x="' + (W - R + 6) + '" y="' + (ly + 4).toFixed(1) + '">' + short(last) + '</text>'
          + '<line class="xc-grid" x1="' + L + '" x2="' + (W - R) + '" y1="' + (H - B) + '" y2="' + (H - B) + '"/>'
          + '<text class="xc-ax" x="' + L + '" y="' + (H - 5) + '">' + when(new Date(x0).toISOString()) + '</text>'
          + '<text class="xc-ax" x="' + (W - R) + '" y="' + (H - 5) + '" text-anchor="end">now</text>';
        return out;
      },
      depth: function () {
        var W = 640, H = 150, T = 10, B = 20, bids = this.bids, asks = this.asks, q = this.qtyLabel;
        if (!bids.length && !asks.length) return '<text class="xc-ax" x="' + W / 2 + '" y="' + H / 2 + '" text-anchor="middle">The book is empty</text>';
        var all = bids.concat(asks).map(function (l) { return l.price; });
        var lo = Math.min.apply(null, all), hi = Math.max.apply(null, all);
        if (hi === lo) { lo *= 0.97; hi = hi * 1.03 || 1; }
        var cum = function (ls) { var s = 0; return ls.map(function (l) { s += l.qty; return [l.price, s]; }); };
        var cb = cum(bids), ca = cum(asks);
        var top = Math.max(cb.length ? cb[cb.length - 1][1] : 0, ca.length ? ca[ca.length - 1][1] : 0) || 1;
        function X(p) { return 8 + (p - lo) / (hi - lo) * (W - 16); }
        function Y(n) { return H - B - n / top * (H - T - B); }
        function steps(c, cls) {
          if (!c.length) return "";
          var d = "M" + X(c[0][0]).toFixed(1) + "," + (H - B), prev = 0;
          c.forEach(function (p) { d += " L" + X(p[0]).toFixed(1) + "," + Y(prev).toFixed(1) + " L" + X(p[0]).toFixed(1) + "," + Y(p[1]).toFixed(1); prev = p[1]; });
          d += " L" + X(c[c.length - 1][0]).toFixed(1) + "," + (H - B) + " Z";
          return '<path class="xc-dep ' + cls + '" d="' + d + '"/>';
        }
        return steps(cb, "up") + steps(ca, "down")
          + '<text class="xc-ax" x="8" y="' + (H - 5) + '">' + short(lo) + '</text>'
          + '<text class="xc-ax" x="' + (W - 8) + '" y="' + (H - 5) + '" text-anchor="end">' + short(hi) + '</text>'
          + '<text class="xc-ax" x="8" y="' + (T + 8) + '">' + fmt(top) + " " + q + '</text>';
      }
    });
  }

  function xmine() {
    var base = window.live(API + "me", 20);
    var init = base.init;
    return extend(base, {
      fmt: fmt, when: when, lim: lim,
      side: "BUY", qty: null, price: null, character: "", busy: false, msg: "", msgClass: "", pv: null, left: 0, clock: null,
      init: function () {
        var self = this;
        init.call(this);
        this.$watch("data", function (d) {
          if (d && d.characters && d.characters.indexOf(self.character) < 0) self.character = d.characters[0] || "";
          if (d && d.waiting && d.waiting.some(function (w) { return w.type === "mine"; })) setTimeout(function () { self.refresh(); }, 2500);
        });
        this.$watch("$store.xc.meta", function (m, old) {
          if (old && m && m.id === old.id) return;  // the same ticker refreshed: keep what the player typed
          self.pv = null; self.msg = ""; self.price = null; self.qty = self.lot();
        });
        this.qty = this.lot();
      },
      get meta() { return store().meta || { id: "NX", unit: "1000nx", unit_label: "mesos per 1,000 NX", per: 1000, qty_label: "NX",
                                             min_qty: 100, max_qty: 50000, lot: 100, min_price: 1000, max_price: 100000000 }; },
      get isItem() { return this.meta.unit === "item"; },
      lot: function () { var m = this.meta; return m.unit === "item" ? Math.max(1, m.min_qty || 1) : 1000; },
      orders: function () { return (this.data && this.data.orders) || []; },
      openCount: function () {
        var id = this.meta.id;
        return this.orders().filter(function (o) { return o.status === "OPEN" && (o.ticker || "NX") === id; }).length;
      },
      q: function (o) { return o.qty !== undefined ? o.qty : o.nx; },
      qLeft: function (o) { return o.qtyLeft !== undefined ? o.qtyLeft : o.nxLeft; },
      status: function (s) { return { OPEN: "Open", FILLED: "Traded", CANCELLED: "Cancelled", EXPIRED: "Expired" }[s] || s; },
      say: function (text, ok) { this.msg = text; this.msgClass = ok === true ? "ok" : ok === false ? "bad" : ""; },
      estimate: function () {
        if (!this.qty || !this.price) return "";
        var mesos = Math.floor(this.qty * this.price / (this.meta.per || 1)), fee = Math.floor(mesos / 100);
        return this.side === "SELL"
          ? "If it all trades at your price: about " + fmt(mesos - fee) + " mesos after the 1% fee (" + fmt(fee) + ")."
          : "Sets aside up to " + fmt(mesos + fee) + " mesos: " + fmt(mesos) + " plus the 1% fee (" + fmt(fee) + ").";
      },
      ask: function (path, body, waiting) {
        var self = this;
        this.busy = true;
        this.say(waiting);
        return post(path, body).then(function (j) {
          if (!j.ok) { self.busy = false; self.say(j.error || "That didn't work.", false); return null; }
          return answer(j.id, function () { self.say("Still waiting for the game..."); }).then(function (a) {
            self.busy = false;
            if (!a.ok) { self.say(said(a), false); return null; }
            self.say("");
            return a.data;
          });
        });
      },
      preview: function () {
        var self = this;
        this.pv = null;
        this.ask("preview", { ticker: this.meta.id, character: this.character, side: this.side, qty: this.qty, price: this.price },
          "Checking with the game...").then(function (d) {
          if (!d) return;
          self.pv = d;
          var ends = new Date(d.expiresAt).getTime();
          clearInterval(self.clock);
          // stop taking a yes a few seconds early: the confirm still has to reach the game
          var tick = function () { self.left = Math.max(0, Math.floor((ends - Date.now()) / 1000) - 5); if (!self.left) clearInterval(self.clock); };
          tick();
          self.clock = setInterval(tick, 1000);
        });
      },
      pvQty: function () { var p = this.pv; return p ? (p.qty !== undefined ? p.qty : p.nx) : 0; },
      asset: function (a) { return a === "NX" ? "NX" : a === "MESO" ? "mesos" : (this.meta.qty_label || "items"); },
      confirm: function () {
        var self = this, pv = this.pv;
        if (!pv || this.left <= 0) return;
        clearInterval(this.clock);
        this.ask("confirm", { token: pv.token }, "Placing your order...").then(function (d) {
          self.pv = null;
          if (!d) return;
          var done = d.filledQty || d.filledNx;
          self.say("Order " + d.orderId + " placed" + (done ? ": " + fmt(done) + " traded right away." : "."), true);
          self.refresh();
        });
      },
      cancel: function (o) {
        var self = this;
        this.ask("cancel", { orderId: o.orderId }, "Cancelling order " + o.orderId + "...").then(function (d) {
          if (!d) return;
          self.say("Order " + o.orderId + " cancelled. What was left comes back to you.", true);
          self.refresh();
        });
      }
    });
  }

  document.addEventListener("alpine:init", function () {
    window.Alpine.store("xc", { tk: "NX", meta: null, limits: null });
    window.Alpine.data("xbook", xbook);
    window.Alpine.data("xmine", xmine);
  });
})();
