/* The exchange's tickers in the Megaphone: for signed-in players, each ticker's last price and its 24 h change (NX,
   LEAF, CHAOS, WS, ELIXIR), like a stock ticker, at the front of the scroll. Signed out (the exchange answers 401) or with the exchange off, the Megaphone
   stays as built. One request a minute at most per tab (sessionStorage). */
(function () {
  var rail = document.querySelector(".rail .roll > div");
  if (!rail) return;
  var root = (document.querySelector('script[src$="assets/ticker.js"]').getAttribute("src") || "").replace(/assets\/ticker\.js$/, "");
  var KEY = "slums-xtick2", DAY = 86400000;
  var nf = new Intl.NumberFormat("en-US");

  function item(t, copy) {
    var c = t.change, cls = c === null || c === undefined ? "" : c > 0.005 ? "up" : c < -0.005 ? "down" : "";
    var a = document.createElement("a");
    a.className = "xtick";
    a.href = root + "market/exchange" + (t.id === "NX" ? "" : "#" + t.id.toLowerCase());
    if (copy) { a.setAttribute("aria-hidden", "true"); a.tabIndex = -1; }
    a.innerHTML = "<em></em> <b></b> <span></span>";
    a.querySelector("em").textContent = t.id;
    a.querySelector("b").textContent = nf.format(t.last);
    var s = a.querySelector("span");
    s.textContent = cls === "up" ? "\u25b2 +" + c.toFixed(2) + "%" : cls === "down" ? "\u25bc " + c.toFixed(2) + "%" : c === null || c === undefined ? "" : "0.00%";
    if (cls) s.className = cls;
    return a;
  }

  function show(list) {
    if (!list || !list.length) return;
    var half = Math.floor(rail.children.length / 2), mark = rail.children[half];
    list.forEach(function (t) {  // the rail is written twice so it loops; the copy is hidden
      rail.insertBefore(item(t, false), rail.children[list.indexOf(t)]);
      rail.insertBefore(item(t, true), mark);
    });
  }

  function sum(j) {  // tickers that have traded: {id, last, change (24 h %)}
    return (j.tickers || []).filter(function (t) { return t && t.last; }).map(function (t) {
      var c = t.change_24h_pct;
      if (c === undefined) {  // a game without tickers: work it out from the last trades
        var tr = (t.trades || []).filter(function (x) { return new Date(x.t).getTime() >= Date.now() - DAY; });
        c = tr.length > 1 ? (t.last - tr[0].price) / tr[0].price * 100 : null;
      }
      return { id: t.id, last: t.last, change: c };
    });
  }

  try {
    var kept = JSON.parse(sessionStorage.getItem(KEY) || "null");
    if (kept && Date.now() - kept.at < 60000) return show(kept.t);
  } catch (e) { /* no storage: just ask */ }
  fetch(root + "api/market/exchange/book", { credentials: "same-origin", cache: "no-store" })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var t = j && j.ok ? sum(j) : null;
      try { sessionStorage.setItem(KEY, JSON.stringify({ at: Date.now(), t: t })); } catch (e) { /* fine */ }
      show(t);
    })
    .catch(function () { /* the Megaphone stays as built */ });
})();
