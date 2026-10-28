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

  function xbook() {
    var base = window.live(API + "book", 10);
    return extend(base, {
      fmt: fmt, when: when,
      get book() { return this.data && this.data.book; },
      get halted() { return !!(this.book && this.book.halted); },
      get rate() { return this.book && this.book.rate && this.book.rate.mesos_per_1000nx; },
      get bids() { return (this.book && this.book.bids) || []; },
      get asksDown() { return ((this.book && this.book.asks) || []).slice().reverse(); },  // lowest ask next to the spread
      get trades() { return (this.book && this.book.trades) || []; },
      pick: function (price) { window.dispatchEvent(new CustomEvent("xc-price", { detail: price })); },
      spread: function () {
        var a = (this.book && this.book.asks || [])[0], b = this.bids[0];
        if (!a && !b) return "The book is empty: be the first to post an order.";
        if (!a || !b) return a ? "No buyers yet." : "No sellers yet.";
        return "Spread: " + fmt(a.price - b.price) + " mesos";
      },
      chart: function () {
        var t = this.trades, W = 600, H = 180, P = 28;
        if (!t.length) return "";
        var xs = t.map(function (x) { return new Date(x.t).getTime(); });
        var ps = t.map(function (x) { return x.price; });
        if (this.rate) ps.push(this.rate);
        var x0 = Math.min.apply(null, xs), x1 = Math.max.apply(null, xs) || x0 + 1;
        var lo = Math.min.apply(null, ps), hi = Math.max.apply(null, ps);
        if (hi === lo) { lo = lo * 0.95; hi = hi * 1.05; }
        var maxNx = Math.max.apply(null, t.map(function (x) { return x.nx; }));
        function X(v) { return P + (x1 === x0 ? (W - 2 * P) / 2 : (v - x0) / (x1 - x0) * (W - 2 * P)); }
        function Y(v) { return H - P - (v - lo) / (hi - lo) * (H - 2 * P); }
        var out = '<text class="xc-ax" x="' + P + '" y="14">' + fmt(Math.round(hi)) + '</text>'
          + '<text class="xc-ax" x="' + P + '" y="' + (H - 6) + '">' + fmt(Math.round(lo)) + '</text>';
        if (this.rate) out += '<line class="xc-rateline" x1="' + P + '" x2="' + (W - P) + '" y1="' + Y(this.rate) + '" y2="' + Y(this.rate) + '"/>';
        t.forEach(function (x, i) {
          out += '<circle class="xc-dot" cx="' + X(xs[i]).toFixed(1) + '" cy="' + Y(x.price).toFixed(1) + '" r="'
            + (3 + 5 * Math.sqrt(x.nx / maxNx)).toFixed(1) + '"><title>' + fmt(x.price) + " for " + fmt(x.nx) + " NX, " + when(x.t) + '</title></circle>';
        });
        return out;
      }
    });
  }

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
