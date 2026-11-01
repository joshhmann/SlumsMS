/* The exchange page (market/exchange.html): a trading screen for every ticker (NX, LEAF, CHAOS, WS, ELIXIR), on Alpine
   and live.js. The picked ticker lives in Alpine.store("xc") and the page's #hash (exchange.html#leaf).
   - xbook: the ticker tabs, the ticker header, candles (api/market/exchange/history, 30 days of hours), depth, the book
     and the last trades (api/market/exchange/book every 10 s).
   - xmine: the order form (preview, then confirm with the 60 s token) and my orders with cancel (.../me).
   A write is a request to the game (exchange_desk.py): POST it, get {id}, then ask answer/<id> until the game answers.
   Units come from the desk (unit, unit_label, per): NX is priced per NX (the game's per 1,000 NX, converted by
   exchange_desk.py), items per item. */
(function () {
  var ROOT = (document.querySelector("[data-mk-root]") || { dataset: { mkRoot: "../" } }).dataset.mkRoot;
  var API = ROOT + "api/market/exchange/";
  // with the NX desk on (1.6.3, ticket 162) the NX tab is SlumsBank's quote panel (xdesk, market/nx/*) and NX has no
  // book: the term carries data-nxdesk and xbook leaves NX out of the tickers, so the item tabs keep theirs
  var DESK = !!document.querySelector("[data-nxdesk]");
  var DESK_PW = "Type your password to confirm this trade.";
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
    LIMIT_OPEN: function () { return "You already have " + lim(store().meta).max_open_per_ticker + " open orders on this ticker. Cancel one first."; },
    LIMIT_DAY: function () { return "That's " + lim(store().meta).per_day + " orders today. Try again tomorrow."; },
    COOLDOWN: function () { return "Wait " + lim().cooldown_sec + " seconds between orders."; },
    DUPLICATE: "That order was already sent.",
    TOKEN_EXPIRED: "That preview expired. Preview the order again.",
    TRY_AGAIN: "You're changing channel, logging out or in the Cash Shop. Try again in a moment.",
    NO_ANSWER: "The game didn't answer. Try again later.",
    // data.game: the game has no sign-in of yours since it last restarted (1.6.2 b005); /login/?again=1 records one
    REAUTH: function (a) {
      return a.data && a.data.game ? "The game restarted since you signed in. For safety, sign in again to trade."
        : "For safety, trading needs a sign-in from the last 12 hours. Sign in again.";
    },
    HALTED: "The exchange is paused right now. Nothing was placed; try again later.",
    PASSWORD_REQUIRED: "Type your password to confirm this order.",
    // the game keeps NX near the official rate: sells at 50% of it or more, buys at 200% or less (b005); before a
    // rate exists, a fixed floor for sells and a fixed ceiling for buys (the other end is then the ticker's own cap)
    PRICE_BAND: function (a, side) {
      var d = a.data || {}, lo = typeof d.minPrice === "number", hi = typeof d.maxPrice === "number";
      if (!d.rate && side === "SELL" && lo) return "NX has no official rate yet, so a sell starts at " + fmt(d.minPrice) + " mesos per NX.";
      if (!d.rate && side === "BUY" && hi) return "NX has no official rate yet, so a buy can offer at most " + fmt(d.maxPrice) + " mesos per NX.";
      var range = lo && hi ? "from " + fmt(d.minPrice) + " to " + fmt(d.maxPrice) : lo ? "of at least " + fmt(d.minPrice)
        : hi ? "of at most " + fmt(d.maxPrice) : "closer to it";
      return "NX trades near the official rate" + (d.rate ? " (" + fmt(d.rate) + ")" : "") + ": pick a price " + range + ".";
    },
    // the NX desk (1.6.3): its own prices, its own refusals
    DESK_CLOSED: "The NX desk is closed right now.",
    DESK_EMPTY: function (a) {
      var n = a.data && a.data.desk_nx;
      return typeof n === "number" ? "SlumsBank has only " + fmt(n) + " NX to sell right now: try a smaller amount."
        : "SlumsBank has no NX to sell right now.";
    },
    // the rate moved between the preview and the confirm; the answer carries the fresh quote to show and confirm
    QUOTE_MOVED: "The rate moved while you were looking. Here is the new quote: check it and confirm again.",
    MIN_QTY: function (a) {
      var n = a.data && a.data.min_qty;
      return typeof n === "number" ? "The desk's smallest trade is " + fmt(n) + " NX." : "That's under the desk's smallest trade.";
    },
    PRICES_STALE: "The NX prices are updating. Try again in a minute.",
    ERROR: "Something went wrong in the game. Try again later."
  };
  function said(a, side) {
    var c = CODES[a.code];
    if (c) return typeof c === "function" ? c(a, side) : c;
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

  // 1.6.2 b005: every site sell, and a site buy past the password-free amount, need the account's password for that
  // one order. It goes to the sign-in service (/api/login/exchange-approve), which hands it to the game's sign-in
  // check; then the confirm goes as usual. The box is emptied as soon as the password is sent.
  function exchangeApprove(token, pw) {
    return fetch(ROOT + "api/login/exchange-approve", { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify({ token: token, pass: pw }) })
      .then(function (r) { return r.json().catch(function () { return { ok: false, error: "The server said no (" + r.status + ")." }; }); })
      .catch(function () { return { ok: false, error: "Can't reach the server right now." }; });
  }
  // a held quote has to reach the game before it expires: stop taking a yes a few seconds early
  function leftOf(iso) {
    var t = iso ? new Date(iso).getTime() : NaN;
    return isNaN(t) ? 0 : Math.max(0, Math.floor((t - Date.now()) / 1000) - 5);
  }

  function store() { return window.Alpine.store("xc"); }
  var LIM = { fee_bps_each_side: 100, max_open_per_ticker: 5, per_day: 20, per_day_scope: "all", cooldown_sec: 10, expiry_hours: 72 };
  // the exchange's limits, with a ticker's own where it has them (NX: 5 open, 20 a day, 72 h; items: their own)
  function lim(t) {
    var l = Object.assign({}, LIM, store().limits || {});
    if (t) ["max_open", "per_day", "expiry_hours"].forEach(function (k) {
      var top = k === "max_open" ? "max_open_per_ticker" : k;
      if (typeof t[k] === "number") l[top] = t[k];
    });
    return l;
  }
  function hours(h) { return h % 24 === 0 && h > 48 ? h / 24 + " days" : h + " hours"; }

  // Slums-chan's trading lines, once the exchange says it's open (sections/buddy.py OPEN_LINES; b067)
  function openLines() {
    var b = document.querySelector(".bubble[data-open-lines]");
    if (!b) return;
    var open = b.getAttribute("data-open-lines");
    b.removeAttribute("data-open-lines");
    b.setAttribute("data-lines", open);
    try { b.textContent = JSON.parse(open)[0]; } catch (e) { /* keep the old line */ }
  }

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
        store().closed = !!(this.data && this.data.open === false);  // switched off on this server (b063)
        if (this.data && this.data.open === true) openLines();
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
      get list() {  // with the desk on, the NX tab is SlumsBank's quote panel (xdesk) and has no book: NX comes first
        var ts = ((this.data && this.data.tickers) || []).filter(function (t) { return !DESK || t.id !== "NX"; });
        if (!DESK) return ts;
        var q = store().desk;  // xdesk puts the quote here, so the tab can show the desk's rate and today's move
        return [{ id: "NX", name: "NX desk", unit: "nx", unit_label: "mesos per NX", qty_label: "NX", per: 1,
                  last: q ? q.mid : null, change_24h_pct: q ? q.moved_today_pct : null }].concat(ts);
      },
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
        var q = t.unit === "item" ? " per order" : " per order, in lots of " + fmt(t.lot), l = lim(t);
        return "priced in " + t.unit_label + "; " + fmt(t.min_qty) + " to " + fmt(t.max_qty) + " " + t.qty_label + q
          + "; up to " + l.max_open_per_ticker + " open orders and " + l.per_day + " new ones a day"
          + "; an order lasts " + hours(l.expiry_hours) + ".";
      },
      limitRule: function () {
        var l = lim();
        return "the daily count is " + (l.per_day_scope === "ticker" ? "per ticker" : "across all tickers")
          + ", and orders are at least " + l.cooldown_sec + " seconds apart.";
      },
      get events() { return (this.data && this.data.events) || []; },
      eventText: function (e) {
        // ends_at: Unix seconds (an ISO time works too); null for news with no end
        var end = typeof e.ends_at === "number" ? e.ends_at * 1000 : e.ends_at ? new Date(e.ends_at).getTime() : NaN;
        var left = isNaN(end) ? null : Math.max(0, Math.round((end - Date.now()) / 60000));
        return e.name + (e.ticker ? " on " + e.ticker : "") + (left === null ? "" : left >= 60 ? ": about " + Math.round(left / 60) + " h left" : ": " + left + " min left");
      },
      usePrice: function (price) { window.dispatchEvent(new CustomEvent("xc-price", { detail: price })); },

      // candles: the game's hours that traded; with none yet, the last trades as a line
      chart: function () {
        var W = 640, H = 280, L = 6, R = 70, T = 10, VOL = 46, B = 20, self = this, span = DAY * 3;
        this.ranges.forEach(function (r) { if (r[0] === self.range) span = r[1]; });
        var now = Date.now(), x0 = now - span, x1 = now + HOUR;
        var cs = (this.candlesFor === store().tk ? this.candles : []).map(function (c) {
          return { at: new Date(c.t).getTime(), o: c.o, h: c.h, l: c.l, c: c.c, v: c.v, n: c.n, t: c.t };
        }).filter(function (c) { return c.at >= x0 - HOUR && !isNaN(c.at); });
        var rate = this.cur && this.cur.rate, fair = this.cur && this.cur.fair;
        var line = cs.length ? [] : this.trades.filter(function (t) { return t.at >= x0; });
        if (!cs.length && !line.length)
          return '<text class="xc-ax" x="' + (W - R) / 2 + '" y="' + H / 2 + '" text-anchor="middle">No trades in this range yet</text>';
        var ps = [];
        cs.forEach(function (c) { ps.push(c.h, c.l); });
        line.forEach(function (t) { ps.push(t.price); });
        if (rate) ps.push(rate);
        if (fair) ps.push(fair);
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
        if (fair) out += '<line class="xc-fairline" x1="' + L + '" x2="' + (W - R) + '" y1="' + Y(fair).toFixed(1) + '" y2="' + Y(fair).toFixed(1) + '"><title>Fair value '
          + fmt(fair) + '</title></line><text class="xc-ax xc-fairt" x="' + (L + 4) + '" y="' + (Y(fair) - 4).toFixed(1) + '">fair ' + short(fair) + '</text>';
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
      stale: false, staleGame: false, needPw: false, pw: "",
      init: function () {
        var self = this;
        init.call(this);
        this.$watch("data", function (d) {
          if (d && d.open === false) store().closed = true;
          if (d && d.characters && d.characters.indexOf(self.character) < 0) self.character = d.characters[0] || "";
          if (d && d.waiting && d.waiting.some(function (w) { return w.type === "mine"; })) setTimeout(function () { self.refresh(); }, 2500);
        });
        this.$watch("$store.xc.meta", function (m, old) {
          if (old && m && m.id === old.id) return;  // the same ticker refreshed: keep what the player typed
          self.pv = null; self.msg = ""; self.price = null; self.qty = self.lot();
        });
        this.qty = this.lot();
      },
      get meta() { return store().meta || { id: "NX", unit: "nx", unit_label: "mesos per NX", per: 1, qty_label: "NX",
                                             min_qty: 100, max_qty: 50000, lot: 100, min_price: 1, max_price: 100000 }; },
      get isItem() { return this.meta.unit === "item"; },
      // a sign-in over 12 h old can't trade (b005): the site or the game says REAUTH
      get reauth() { return this.stale || !!(this.data && this.data.reauth); },
      // the banner says why (b005 check on TEST 2026-10-30: after a game restart it said "a sign-in from the last
      // 12 hours" to someone who had signed in 15 minutes before)
      reauthText: function () {
        return this.staleGame ? "The game restarted since you signed in, so for safety it needs a fresh sign-in."
          : "For safety, trading needs a sign-in from the last 12 hours, so a copied cookie can't empty your account.";
      },
      againUrl: function () { return ROOT + "login/?again=1&next=" + encodeURIComponent(location.pathname); },
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
          if (!j.ok) {
            self.busy = false;
            if (j.code === "REAUTH") { self.stale = true; self.pv = null; }
            self.say(j.error || "That didn't work.", false);
            return null;
          }
          return answer(j.id, function () { self.say("Still waiting for the game..."); }).then(function (a) {
            self.busy = false;
            if (!a.ok && a.code === "PASSWORD_REQUIRED" && path === "confirm") return { passwordRequired: true, data: a.data || {} };
            if (!a.ok) {
              if (a.code === "REAUTH") { self.stale = true; self.staleGame = !!(a.data && a.data.game); self.pv = null; }
              self.say(said(a, body && body.side), false);
              return null;
            }
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
          self.needPw = !!d.passwordRequired;
          self.pw = "";
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
      // 1.6.2 b005: the account's password for that one order (exchangeApprove), then the confirm as usual
      approve: function (token) {
        var self = this, pw = this.pw;
        this.pw = "";
        if (!pw) { this.say("Type your password to confirm this order.", false); return Promise.resolve(false); }
        this.busy = true;
        this.say("Checking your password...");
        return exchangeApprove(token, pw).then(function (j) {
          self.busy = false;
          if (j.ok) return true;
          if (j.code === "TOKEN_EXPIRED" || j.code === "HALTED") { self.pv = null; clearInterval(self.clock); }
          self.say(j.error || "That didn't work.", false);
          return false;
        });
      },
      confirm: function () {
        var self = this, pv = this.pv;
        if (!pv || this.left <= 0 || this.busy) return;
        (this.needPw ? this.approve(pv.token) : Promise.resolve(true)).then(function (approved) {
          if (!approved) return;
          self.place(pv);
        });
      },
      place: function (pv) {
        var self = this;
        clearInterval(this.clock);
        this.ask("confirm", { token: pv.token }, "Placing your order...").then(function (d) {
          if (d && d.passwordRequired) {   // the game wants the password for this one (the token still holds)
            self.needPw = true;
            self.say(CODES.PASSWORD_REQUIRED, false);
            if (d.data.expiresAt) {
              var ends = new Date(d.data.expiresAt).getTime();
              var tick = function () { self.left = Math.max(0, Math.floor((ends - Date.now()) / 1000) - 5); if (!self.left) clearInterval(self.clock); };
              tick(); self.clock = setInterval(tick, 1000);
            }
            return;
          }
          self.pv = null; self.needPw = false;
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

  // The NX desk (1.6.3, ticket 162): SlumsBank's two prices in place of the NX tab's book. quote, history and me come
  // from /api/market/exchange/nx/*, which serves the game's nx-desk.json; a trade is the same two steps as an order:
  // preview (the desk holds that quote for 60 s) then confirm by its token. The game keeps its prices per 1,000 NX and
  // exchange_desk.py converts them, so everything here is mesos per NX. Nothing is priced by this file: the spread,
  // the fee, the daily cap and the smallest trade all come from the desk's own fields.
  function xdesk() {
    return {
      fmt: fmt, when: when, clock: clock, pct: pct,
      quote: null, qstate: "loading", err: "", stale: false, stamp: "",
      hist: [], me: null,
      side: "BUY", nx: 1000, character: "",
      busy: false, msg: "", msgClass: "", pv: null, left: 0, needPw: false, pw: "",
      staleSignIn: false, staleGame: false, timer: null, poller: null,
      init: function () {
        var self = this;
        this.load();
        this.loadHistory();
        this.loadMe();
        this.poller = setInterval(function () {  // the desk rewrites its file every minute
          if (document.hidden) return;
          self.load(); self.loadMe();
        }, 30000);
      },
      destroy: function () { clearInterval(this.poller); clearInterval(this.timer); },

      load: function () {
        var self = this;
        return fetch(API + "nx/quote", { credentials: "same-origin", cache: "no-store" })
          .then(function (r) { return r.json().catch(function () { return { ok: false, error: "The server said no (" + r.status + ")." }; }); })
          .then(function (j) {
            if (j && j.ok) {
              self.quote = j; window.Alpine.store("xc").desk = j;  // the NX tab's own line in the tab strip
              self.qstate = "ok"; self.stale = false; self.err = ""; self.stamp = "as of " + clock(j.generated_at);
              return;
            }
            self.quote = null;
            if (j && j.code === "PRICES_STALE") { self.qstate = "stale"; self.stale = true; self.stamp = ""; return; }
            self.qstate = "error";
            self.err = (j && j.error) || "The desk's prices didn't load. Try again in a bit.";
          })
          .catch(function () { self.qstate = "error"; self.err = "Can't reach the server right now."; });
      },
      loadHistory: function () {
        var self = this;
        fetch(API + "nx/history?hours=168", { credentials: "same-origin", cache: "no-store" })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (j) { if (j && j.ok && j.history) self.hist = j.history; })
          .catch(function () { /* keep what we had */ });
      },
      loadMe: function () {
        var self = this;
        fetch(API + "nx/me", { credentials: "same-origin", cache: "no-store" })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (j) {
            if (!j || !j.ok) return;
            self.me = j;
            if (j.state === "signedOut") return;
            if (j.characters && j.characters.length && j.characters.indexOf(self.character) < 0) self.character = j.characters[0];
          })
          .catch(function () { /* keep what we had */ });
      },

      get signedIn() { return !!(this.me && this.me.state !== "signedOut"); },
      get reauth() { return this.staleSignIn || !!(this.me && this.me.reauth); },
      reauthText: function () {
        return this.staleGame ? "The game restarted since you signed in, so for safety it needs a fresh sign-in."
          : "For safety, trading needs a sign-in from the last 12 hours, so a copied cookie can't empty your account.";
      },
      againUrl: function () { return ROOT + "login/?again=1&next=" + encodeURIComponent(location.pathname); },
      characters: function () { return (this.me && this.me.characters) || []; },
      fills: function () { return (this.me && this.me.fills) || []; },
      asOf: function () { return this.me && this.me.asOf ? "as of " + when(this.me.asOf) : ""; },
      say: function (text, ok) { this.msg = text; this.msgClass = ok === true ? "ok" : ok === false ? "bad" : ""; },

      // the panel's sentences: every number is the desk's
      whyClosed: function () {
        var r = this.quote && this.quote.closed_reason;
        return r ? "(" + r + ")" : "";
      },
      movedText: function () {
        var q = this.quote;
        if (!q) return "";
        return pct(q.moved_today_pct) + " today (limit about " + q.day_cap_pct + "% each way)";
      },
      driftText: function () {
        var q = this.quote;
        if (!q || typeof q.drift_bps_last_hour !== "number") return "";
        var b = q.drift_bps_last_hour / 100;
        return "Last hour " + (b > 0 ? "+" : b < 0 ? "" : "") + b.toFixed(2) + "%.";
      },
      gapText: function () {
        var q = this.quote;
        if (!q || !q.buy || !q.sell) return "";
        var each = (q.spread_bps || 0) / 100;
        return "The gap between the two prices is " + (((q.sell - q.buy) / q.buy) * 100).toFixed(2) + "% ("
          + each.toFixed(2) + "% each side).";
      },
      feeText: function () { var q = this.quote; return q && q.fee_bps ? (q.fee_bps / 100).toFixed(2) + "%" : "0%"; },
      capText: function () { var q = this.quote; return q ? "about " + q.day_cap_pct + "%" : ""; },
      smallest: function () { var q = this.quote; return q ? "smallest " + fmt(q.min_qty) + " NX, in steps of 100" : ""; },
      stepNx: function () { var q = this.quote; return q && q.min_qty ? q.min_qty : 100; },
      per: function () { var q = this.quote; return q ? (this.side === "BUY" ? q.sell : q.buy) : null; },
      priceText: function () {
        var each = this.per();
        if (!each || !this.nx) return "";
        return "At " + fmt(each) + " mesos each: " + fmt(Math.round(this.nx * each)) + " mesos for "
          + fmt(this.nx) + " NX" + (this.side === "BUY" ? " (you pay)." : " (you get).");
      },
      previewLabel: function () { return "Preview " + (this.side === "BUY" ? "buy" : "sell"); },
      pvTitle: function () {
        var pv = this.pv;
        return pv ? (pv.side === "SELL" ? "Selling " : "Buying ") + fmt(this.nxOf(pv)) + " NX" : "";
      },
      nxOf: function (pv) { return pv ? (pv.nx !== undefined ? pv.nx : 0) : 0; },
      leftText: function () {
        return this.left > 0 ? "Confirm within " + this.left + " seconds: the rate moves." : "That quote ran out. Preview it again.";
      },

      // a write is a request to the game (exchange_desk.py): POST it, then ask answer/<id> until the game answers.
      // Returns the game's answer as it came ({ok, code, message, data}) so the caller can tell QUOTE_MOVED apart.
      ask: function (path, body, waiting) {
        var self = this;
        this.busy = true;
        this.say(waiting);
        return post(path, body).then(function (j) {
          if (!j.ok) {
            self.busy = false;
            if (j.code === "REAUTH") self.staleSignIn = true;
            self.say(j.error || "That didn't work.", false);
            return null;
          }
          return answer(j.id, function () { self.say("Still waiting for the game..."); }).then(function (a) {
            self.busy = false;
            if (!a.ok) {
              if (a.code === "REAUTH") { self.staleSignIn = true; self.staleGame = !!(a.data && a.data.game); }
              if (a.code !== "PASSWORD_REQUIRED") self.say(said(a, body && body.side), false);
            }
            return a;
          });
        });
      },
      hold: function (d) {  // the quote to show and confirm (from the preview, or a QUOTE_MOVED answer)
        // QUOTE_MOVED's fresh quote is only {price, mid, expiresAt, token}: the rest is the trade being made
        var nx = d.nx !== undefined ? d.nx : (this.pv ? this.pv.nx : this.nx);
        this.pv = { token: d.token, side: d.side || (this.pv && this.pv.side) || this.side, nx: nx,
                    price: d.price, mid: d.mid,
                    mesos: d.mesos !== undefined ? d.mesos
                      : (typeof d.price === "number" ? Math.round(nx * d.price) : null),
                    desk_nx: d.desk_nx !== undefined ? d.desk_nx : (this.quote ? this.quote.desk_nx : null),
                    expiresAt: d.expiresAt };
        this.startClock();
      },
      startClock: function () {
        var self = this;
        clearInterval(this.timer);
        var tick = function () { self.left = leftOf(self.pv && self.pv.expiresAt); if (!self.left) clearInterval(self.timer); };
        tick();
        this.timer = setInterval(tick, 1000);
      },
      preview: function () {
        var self = this;
        this.pv = null; this.needPw = false; clearInterval(this.timer); this.left = 0;
        this.ask("nx/preview", { side: this.side, nx: this.nx, character: this.character }, "Asking the desk for a quote...")
          .then(function (a) {
            if (!a) return;
            if (a.ok) { self.hold(a.data || {}); return; }
            if (a.code === "QUOTE_MOVED" && a.data) { self.hold(a.data); return; }  // the desk quoted afresh
            if (a.code === "PASSWORD_REQUIRED" && a.data && a.data.token) {  // a sale, or a buy over the free amount
              self.hold(a.data); self.needPw = true; self.say(DESK_PW, false);
            }
          });
      },
      approve: function (token) {  // b005: the account's password for that one trade, then the confirm as usual
        var self = this, pw = this.pw;
        this.pw = "";
        if (!pw) { this.say("Type your password to confirm this trade.", false); return Promise.resolve(false); }
        this.busy = true;
        this.say("Checking your password...");
        return exchangeApprove(token, pw).then(function (j) {
          self.busy = false;
          if (j.ok) return true;
          if (j.code === "TOKEN_EXPIRED" || j.code === "HALTED" || j.code === "QUOTE_MOVED") { self.pv = null; clearInterval(self.timer); }
          self.say(j.error || "That didn't work.", false);
          return false;
        });
      },
      confirm: function () {
        var self = this, pv = this.pv;
        if (!pv || this.left <= 0 || this.busy) return;
        (this.needPw ? this.approve(pv.token) : Promise.resolve(true)).then(function (ok) { if (ok) self.place(pv); });
      },
      place: function (pv) {
        var self = this;
        clearInterval(this.timer);
        this.ask("nx/confirm", { token: pv.token }, "Trading with the desk...").then(function (a) {
          if (!a) return;
          if (a.ok) {
            var f = (a.data && a.data.fill) || {};
            self.pv = null; self.needPw = false; self.left = 0;
            self.say("Traded: " + fmt(f.nx) + " NX at " + fmt(f.price) + " mesos each for " + fmt(f.mesos) + " mesos.", true);
            self.load(); self.loadMe(); self.loadHistory();
            return;
          }
          if (a.code === "PASSWORD_REQUIRED") {  // the token still holds: ask for the password, keep the quote
            self.pv = pv; self.needPw = true;
            if (a.data && a.data.expiresAt) pv.expiresAt = a.data.expiresAt;
            self.startClock();
            self.say(DESK_PW, false);
            return;
          }
          if (a.code === "QUOTE_MOVED" && a.data && a.data.token) { self.hold(a.data); return; }
          self.pv = null; self.needPw = false;
        });
      },
      drop: function () {
        clearInterval(this.timer);
        this.pv = null; this.needPw = false; this.pw = ""; this.left = 0; this.say("");
        this.loadMe();
      },

      // 7 days of hourly rates: the line is the desk's mid, the band is what it bought and sold around it, and the
      // shaded part is the day's cap (the rate at the start of the last day, plus and minus day_cap_pct)
      chart: function () {
        var W = 640, H = 260, L = 6, R = 70, T = 12, B = 22;
        var hs = (this.hist || []).map(function (h) {
          return { at: new Date(h.hour).getTime(), mid: h.mid, buy: h.buy, sell: h.sell };
        }).filter(function (h) { return !isNaN(h.at) && typeof h.mid === "number"; });
        if (!hs.length) return '<text class="xc-ax" x="' + W / 2 + '" y="' + H / 2 + '" text-anchor="middle">No hours yet</text>';
        var now = Date.now(), x0 = hs[0].at, x1 = Math.max(now + HOUR, hs[hs.length - 1].at + HOUR);
        var cap = (this.quote && this.quote.day_cap_pct) || 0, start = hs[0].mid, i;
        for (i = 0; i < hs.length; i++) if (hs[i].at >= now - DAY) { start = hs[i].mid; break; }
        var capLo = start * (1 - cap / 100), capHi = start * (1 + cap / 100);
        var ps = [capLo, capHi];
        hs.forEach(function (h) {
          ps.push(h.mid);
          if (typeof h.buy === "number") ps.push(h.buy);
          if (typeof h.sell === "number") ps.push(h.sell);
        });
        var lo = Math.min.apply(null, ps), hi = Math.max.apply(null, ps), pad = (hi - lo) * 0.08 || hi * 0.02 || 1;
        lo = Math.max(0, lo - pad); hi += pad;
        function X(v) { return L + (v - x0) / (x1 - x0) * (W - L - R); }
        function Y(v) { return T + (hi - v) / (hi - lo) * (H - B - T); }
        var out = "";
        for (i = 0; i <= 4; i++) {  // the price grid on the right, like a trading screen
          var gv = lo + (hi - lo) * i / 4;
          out += '<line class="xc-grid" x1="' + L + '" x2="' + (W - R) + '" y1="' + Y(gv).toFixed(1) + '" y2="' + Y(gv).toFixed(1) + '"/>'
            + '<text class="xc-ax" x="' + (W - R + 6) + '" y="' + (Y(gv) + 4).toFixed(1) + '">' + short(gv) + '</text>';
        }
        // today's cap: a shaded band across the whole chart, behind everything else
        out += '<rect class="xc-deskcap" x="' + L + '" y="' + Y(capHi).toFixed(1) + '" width="' + (W - L - R) + '" height="'
          + Math.max(1, Y(capLo) - Y(capHi)).toFixed(1) + '"><title>From the rate a day ago, the desk can move '
          + cap + '% each way</title></rect>';
        var mids = hs.map(function (h) { return X(h.at).toFixed(1) + "," + Y(h.mid).toFixed(1); });
        var sells = [], buys = [], band = typeof hs[0].buy === "number" && typeof hs[0].sell === "number";
        hs.forEach(function (h) {
          if (typeof h.buy !== "number" || typeof h.sell !== "number") { band = false; return; }
          sells.push(X(h.at).toFixed(1) + "," + Y(h.sell).toFixed(1));
          buys.push(X(h.at).toFixed(1) + "," + Y(h.buy).toFixed(1));
        });
        if (band) out += '<polygon class="xc-deskband" points="' + sells.concat(buys.reverse()).join(" ") + '"/>';
        var first = hs[0].mid, last = hs[hs.length - 1].mid;
        out += '<polyline class="xc-line ' + dirOf(last - first) + '" points="' + mids.join(" ") + '"/>';
        out += '<circle class="xc-dot" cx="' + X(hs[hs.length - 1].at).toFixed(1) + '" cy="' + Y(last).toFixed(1)
          + '" r="3"><title>' + fmt(last) + ' mesos each, ' + when(hs[hs.length - 1].hour) + '</title></circle>';
        var ly = Y(last);
        out += '<line class="xc-lastline ' + dirOf(last - first) + '" x1="' + L + '" x2="' + (W - R) + '" y1="' + ly.toFixed(1)
          + '" y2="' + ly.toFixed(1) + '"/>'
          + '<rect class="xc-tag ' + dirOf(last - first) + '" x="' + (W - R + 2) + '" y="' + (ly - 9).toFixed(1)
          + '" width="' + (R - 4) + '" height="18" rx="3"/>'
          + '<text class="xc-tagt" x="' + (W - R + 6) + '" y="' + (ly + 4).toFixed(1) + '">' + short(last) + '</text>'
          + '<line class="xc-grid" x1="' + L + '" x2="' + (W - R) + '" y1="' + (H - B) + '" y2="' + (H - B) + '"/>'
          + '<text class="xc-ax" x="' + L + '" y="' + (H - 5) + '">' + when(new Date(x0).toISOString()) + '</text>'
          + '<text class="xc-ax" x="' + (W - R) + '" y="' + (H - 5) + '" text-anchor="end">now</text>';
        return out;
      }
    };
  }

  document.addEventListener("alpine:init", function () {
    window.Alpine.store("xc", { tk: "NX", meta: null, limits: null, closed: false, desk: null });
    window.Alpine.data("xbook", xbook);
    window.Alpine.data("xmine", xmine);
    window.Alpine.data("xdesk", xdesk);
  });
})();
