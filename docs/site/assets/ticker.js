/* The NX ticker in the Megaphone: for signed-in players, the exchange's last price and its 24 h change, like a stock
   ticker, at the front of the scroll. Signed out (the exchange answers 401) or with the exchange off, the Megaphone
   stays as built. One request a minute at most per tab (sessionStorage). */
(function () {
  var rail = document.querySelector(".rail .roll > div");
  if (!rail) return;
  var root = (document.querySelector('script[src$="assets/ticker.js"]').getAttribute("src") || "").replace(/assets\/ticker\.js$/, "");
  var KEY = "slums-xtick", DAY = 86400000;
  var nf = new Intl.NumberFormat("en-US");

  function show(t) {
    if (!t || !t.last) return;
    var c = t.change, cls = c === null ? "" : c > 0.05 ? "up" : c < -0.05 ? "down" : "";
    var arrow = cls === "up" ? "▲ +" : cls === "down" ? "▼ " : "";
    [false, true].forEach(function (copy) {  // the rail is written twice so it loops; the copy is hidden
      var a = document.createElement("a");
      a.className = "xtick";
      a.href = root + "market/exchange.html";
      if (copy) { a.setAttribute("aria-hidden", "true"); a.tabIndex = -1; }
      a.innerHTML = "<em>NX/MESOS</em> <b></b> <span></span>";
      a.querySelector("b").textContent = nf.format(t.last);
      var s = a.querySelector("span");
      s.textContent = c === null ? "" : arrow + c.toFixed(2) + "%";
      if (cls) s.className = cls;
      if (copy) rail.insertBefore(a, rail.children[Math.floor(rail.children.length / 2)]);
      else rail.insertBefore(a, rail.firstChild);
    });
  }

  function sum(book) {
    var t = (book.trades || []).map(function (x) { return { at: new Date(x.t).getTime(), price: x.price }; })
      .filter(function (x) { return !isNaN(x.at); }).sort(function (a, b) { return a.at - b.at; });
    var last = t.length ? t[t.length - 1].price : book.rate && book.rate.mesos_per_1000nx;
    var day = t.filter(function (x) { return x.at >= Date.now() - DAY; });
    var change = day.length > 1 ? (last - day[0].price) / day[0].price * 100 : null;
    return { last: last, change: change, at: Date.now() };
  }

  try {
    var kept = JSON.parse(sessionStorage.getItem(KEY) || "null");
    if (kept && Date.now() - kept.at < 60000) return show(kept.t);
  } catch (e) { /* no storage: just ask */ }
  fetch(root + "api/market/exchange/book", { credentials: "same-origin", cache: "no-store" })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var t = j && j.book ? sum(j.book) : null;
      try { sessionStorage.setItem(KEY, JSON.stringify({ at: Date.now(), t: t })); } catch (e) { /* fine */ }
      show(t);
    })
    .catch(function () { /* the Megaphone stays as built */ });
})();
