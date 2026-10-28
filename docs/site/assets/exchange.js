/* The NX-meso exchange page (market/exchange.html): two Alpine panels on live.js.
   - xbook: the book, the official rate, its chart and the last trades (GET api/market/exchange/book every 10 s).
   - xmine: the order form (preview, then confirm with the 60 s token) and my orders with cancel (GET .../me).
   A write is a request to the game (exchange_desk.py): POST it, get {id}, then ask answer/<id> until the game answers. */
(function () {
  var API = (document.querySelector("[data-mk-root]") || { dataset: { mkRoot: "../" } }).dataset.mkRoot + "api/market/exchange/";
  var nf = new Intl.NumberFormat("en-US");
  function fmt(n) { return n === null || n === undefined ? "" : nf.format(n); }
  function when(iso) {
    if (!iso) return "";
    var d = new Date(iso);
    return isNaN(d) ? "" : d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
  // what the game's codes mean for the player (ExchangeSite.java)
  var CODES = {
    STALE: "That took too long to reach the game. Try again.",
    HALTED: "The exchange is paused by a GM right now.",
    UNAVAILABLE: "The exchange isn't running right now. Try again later.",
    LOG_OUT_FIRST: "You're logged in to the game on another character. Pick the one you're playing, or log out first.",
    INSUFFICIENT_FUNDS: "That character doesn't have enough mesos, or your account doesn't have enough NX.",
    NOT_OWNER: "That character isn't on your account.",
    NOT_FOUND: "That order wasn't found (it may have already closed).",
    LIMIT_OPEN: "You already have 5 open orders. Cancel one first.",
    LIMIT_DAY: "That's 20 orders today. Try again tomorrow.",
    COOLDOWN: "Wait 10 seconds between orders.",
    DUPLICATE: "That order was already sent.",
    TOKEN_EXPIRED: "That preview expired. Preview the order again.",
    TRY_AGAIN: "You're changing channel, logging out or in the Cash Shop. Try again in a moment.",
    NO_ANSWER: "The game didn't answer. Try again later.",
    ERROR: "Something went wrong in the game. Try again later."
  };
  function said(a) {
    if (CODES[a.code]) return CODES[a.code];
    return a.message ? "The game said: " + a.message + "." : "That didn't work.";
  }

  // Object.assign would read the getters once; copy them as getters
  function extend(base, more) { return Object.defineProperties(base, Object.getOwnPropertyDescriptors(more)); }

  function post(path, body) {
    return fetch(API + path, { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().catch(function () { return { ok: false, error: "The server said no (" + r.status + ")." }; }); })
      .catch(function () { return { ok: false, error: "Can't reach the server right now." }; });
  }
  // the game answers within a poll (a few seconds); give up after 2 minutes
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

  var DAY = 86400000;
  function xbook() {
    var base = window.live(API + "book", 10);
    return extend(base, {
      fmt: fmt, when: when, range: "24h", ranges: [["6h", DAY / 4], ["24h", DAY], ["3d", 3 * DAY], ["All", 0]],
      clock: function (iso) { var d = new Date(iso); return isNaN(d) ? "" : d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }); },
      get book() { return this.data && this.data.book; },
      get halted() { return !!(this.book && this.book.halted); },
      get rate() { return this.book && this.book.rate && this.book.rate.mesos_per_1000nx; },
      get bids() { return (this.book && this.book.bids) || []; },
      get asks() { return (this.book && this.book.asks) || []; },
      get asksDown() { return this.asks.slice().reverse(); },  // the lowest ask sits next to the spread
      get trades() {  // oldest first, each with its time in ms
        return ((this.book && this.book.trades) || []).map(function (t) { return { t: t.t, at: new Date(t.t).getTime(), price: t.price, nx: t.nx }; })
          .filter(function (t) { return !isNaN(t.at); }).sort(function (a, b) { return a.at - b.at; });
      },
      get last() { var t = this.trades; return t.length ? t[t.length - 1].price : this.rate; },
      // the change over 24 h: the last trade against the first trade of the day (of the trades the game publishes)
      change: function () {
        var t = this.trades, since = Date.now() - DAY;
        var first = t.filter(function (x) { return x.at >= since; })[0];
        if (!first || !this.last || first === t[t.length - 1]) return null;
        return (this.last - first.price) / first.price * 100;
      },
      get dir() { var c = this.change(); return c === null || Math.abs(c) < 0.05 ? "flat" : c > 0 ? "up" : "down"; },
      changeText: function () {
        var c = this.change();
        if (c === null) return "24h: -";
        return (c > 0 ? "\u25b2 +" : c < 0 ? "\u25bc " : "") + c.toFixed(2) + "% 24h";
      },
      stats: function () {
        var since = Date.now() - DAY, day = this.trades.filter(function (x) { return x.at >= since; });
        if (!day.length) return { hi: null, lo: null, vol: 0 };
        var ps = day.map(function (x) { return x.price; });
        return { hi: Math.max.apply(null, ps), lo: Math.min.apply(null, ps), vol: day.reduce(function (s, x) { return s + x.nx; }, 0) };
      },
      mid: function () { var a = this.asks[0], b = this.bids[0]; return a && b ? Math.round((a.price + b.price) / 2) : null; },
      spread: function () {
        var a = this.asks[0], b = this.bids[0];
        if (!a || !b) return a ? "no buyers" : b ? "no sellers" : "-";
        return fmt(a.price - b.price);
      },
      bar: function (l, side) {
        var all = this.asks.concat(this.bids), max = Math.max.apply(null, all.map(function (x) { return x.nx; }).concat([1]));
        var w = Math.round(l.nx / max * 100);
        return "background: linear-gradient(to left, var(--xc-" + side + "-bg) " + w + "%, transparent " + w + "%)";
      },
      recent: function () {
        var t = this.trades;
        return t.map(function (x, i) { return { t: x.t, price: x.price, nx: x.nx, dir: i && x.price > t[i - 1].price ? "up" : i && x.price < t[i - 1].price ? "down" : "" }; })
          .reverse().slice(0, 14);
      },
      pick: function (price) { window.dispatchEvent(new CustomEvent("xc-price", { detail: price })); },
      chart: function () {
        var W = 640, H = 240, L = 8, R = 72, T = 12, B = 22, span = 0, self = this;
        this.ranges.forEach(function (r) { if (r[0] === self.range) span = r[1]; });
        var since = span ? Date.now() - span : 0;
        var t = this.trades.filter(function (x) { return x.at >= since; });
        if (!t.length) return '<text class="xc-ax" x="' + W / 2 + '" y="' + H / 2 + '" text-anchor="middle">No trades in this range</text>';
        var x0 = span ? since : t[0].at, x1 = Math.max(Date.now(), t[t.length - 1].at);
        var ps = t.map(function (x) { return x.price; });
        if (this.rate) ps.push(this.rate);
        var lo = Math.min.apply(null, ps), hi = Math.max.apply(null, ps), pad = (hi - lo) * 0.12 || hi * 0.03;
        lo -= pad; hi += pad;
        var maxNx = Math.max.apply(null, t.map(function (x) { return x.nx; }));
        function X(v) { return L + (v - x0) / (x1 - x0 || 1) * (W - L - R); }
        function Y(v) { return T + (hi - v) / (hi - lo) * (H - T - B); }
        var out = "";
        for (var i = 0; i <= 3; i++) {  // price grid on the right, like a trading screen
          var v = lo + (hi - lo) * i / 3;
          out += '<line class="xc-grid" x1="' + L + '" x2="' + (W - R) + '" y1="' + Y(v).toFixed(1) + '" y2="' + Y(v).toFixed(1) + '"/>'
            + '<text class="xc-ax" x="' + (W - R + 6) + '" y="' + (Y(v) + 4).toFixed(1) + '">' + short(v) + '</text>';
        }
        var pts = t.map(function (x) { return X(x.at).toFixed(1) + "," + Y(x.price).toFixed(1); });
        var lastX = X(x1).toFixed(1), lastY = Y(t[t.length - 1].price).toFixed(1);
        out += '<polygon class="xc-area ' + this.dir + '" points="' + X(t[0].at).toFixed(1) + "," + (H - B) + " " + pts.join(" ") + " "
          + lastX + "," + lastY + " " + lastX + "," + (H - B) + '"/>';
        out += '<polyline class="xc-line ' + this.dir + '" points="' + pts.join(" ") + " " + lastX + "," + lastY + '"/>';
        if (this.rate) out += '<line class="xc-rateline" x1="' + L + '" x2="' + (W - R) + '" y1="' + Y(this.rate).toFixed(1) + '" y2="' + Y(this.rate).toFixed(1) + '"/>';
        t.forEach(function (x) {
          out += '<circle class="xc-dot" cx="' + X(x.at).toFixed(1) + '" cy="' + Y(x.price).toFixed(1) + '" r="' + (2 + 4 * Math.sqrt(x.nx / maxNx)).toFixed(1)
            + '"><title>' + fmt(x.price) + " for " + fmt(x.nx) + " NX, " + when(x.t) + '</title></circle>';
        });
        out += '<rect class="xc-tag ' + this.dir + '" x="' + (W - R + 2) + '" y="' + (lastY - 9) + '" width="' + (R - 4) + '" height="18" rx="3"/>'
          + '<text class="xc-tagt" x="' + (W - R + 6) + '" y="' + (+lastY + 4) + '">' + short(t[t.length - 1].price) + '</text>';
        return out + '<text class="xc-ax" x="' + L + '" y="' + (H - 6) + '">' + when(new Date(x0).toISOString()) + '</text>'
          + '<text class="xc-ax" x="' + (W - R) + '" y="' + (H - 6) + '" text-anchor="end">now</text>';
      },
      depth: function () {
        var W = 640, H = 160, T = 10, B = 20, bids = this.bids, asks = this.asks;
        if (!bids.length && !asks.length) return '<text class="xc-ax" x="' + W / 2 + '" y="' + H / 2 + '" text-anchor="middle">The book is empty</text>';
        var all = bids.concat(asks).map(function (l) { return l.price; });
        var lo = Math.min.apply(null, all), hi = Math.max.apply(null, all);
        if (hi === lo) { lo *= 0.97; hi *= 1.03; }
        var cum = function (ls) { var s = 0; return ls.map(function (l) { s += l.nx; return [l.price, s]; }); };
        var cb = cum(bids), ca = cum(asks);
        var top = Math.max(cb.length ? cb[cb.length - 1][1] : 0, ca.length ? ca[ca.length - 1][1] : 0);
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
          + '<text class="xc-ax" x="8" y="' + (T + 8) + '">' + fmt(top) + ' NX</text>';
      }
    });
  }
  function short(v) { return v >= 1e6 ? (v / 1e6).toFixed(v >= 1e7 ? 1 : 3) + "m" : v >= 1e3 ? Math.round(v / 1e3) + "k" : String(Math.round(v)); }

  function xmine() {
    var base = window.live(API + "me", 20);
    var init = base.init;
    return extend(base, {
      fmt: fmt, when: when,
      side: "SELL", nx: 1000, price: null, character: "", busy: false, msg: "", msgClass: "", pv: null, left: 0, clock: null,
      init: function () {
        var self = this;
        init.call(this);
        this.$watch("data", function (d) {
          if (d && d.characters && d.characters.indexOf(self.character) < 0) self.character = d.characters[0] || "";
          if (d && d.waiting && d.waiting.some(function (w) { return w.type === "mine"; })) setTimeout(function () { self.refresh(); }, 2500);
        });
      },
      orders: function () { return (this.data && this.data.orders) || []; },
      openCount: function () { return this.orders().filter(function (o) { return o.status === "OPEN"; }).length; },
      status: function (s) { return { OPEN: "Open", FILLED: "Traded", CANCELLED: "Cancelled", EXPIRED: "Expired" }[s] || s; },
      say: function (text, ok) { this.msg = text; this.msgClass = ok === true ? "ok" : ok === false ? "bad" : ""; },
      estimate: function () {
        if (!this.nx || !this.price) return "";
        var mesos = Math.floor(this.nx * this.price / 1000), fee = Math.floor(mesos / 100);
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
        this.ask("preview", { character: this.character, side: this.side, nx: this.nx, price: this.price },
          "Checking with the game...").then(function (d) {
          if (!d) return;
          self.pv = d;
          var ends = new Date(d.expiresAt).getTime();
          clearInterval(self.clock);
          // stop taking a yes 15 s early: the game may take a poll to pick the confirm up
          var tick = function () { self.left = Math.max(0, Math.floor((ends - Date.now()) / 1000) - 15); if (!self.left) clearInterval(self.clock); };
          tick();
          self.clock = setInterval(tick, 1000);
        });
      },
      confirm: function () {
        var self = this, pv = this.pv;
        if (!pv || this.left <= 0) return;
        clearInterval(this.clock);
        this.ask("confirm", { token: pv.token }, "Placing your order...").then(function (d) {
          self.pv = null;
          if (!d) return;
          self.say("Order " + d.orderId + " placed" + (d.filledNx ? ": " + fmt(d.filledNx) + " NX traded right away." : "."), true);
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
    window.Alpine.data("xbook", xbook);
    window.Alpine.data("xmine", xmine);
  });
})();
