/* Slums Market for signed-in players: watchlist, price alerts and the Buy/Sell board (tools/site/market_service.py).
   Everything here is added by script once /api/market/me answers; without it the pages are plain read-only market
   pages. Player-written text (names, reasons) is only ever set with textContent. */
(function () {
  var host = document.querySelector("[data-mk-root]");
  if (!host || !window.fetch) return;
  var root = host.getAttribute("data-mk-root");
  var me = null, summary = null, items = null, blocked = {};

  function api(path, body) {
    var opt = { credentials: "same-origin" };
    if (body !== undefined) {
      opt.method = "POST";
      opt.headers = { "Content-Type": "application/json", "X-Slums": "1" };
      opt.body = JSON.stringify(body);
    }
    return fetch(root + "api/market/" + path, opt).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) { return { status: r.status, body: j }; });
    });
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function num(n) { return typeof n === "number" ? n.toLocaleString("en-US") : "-"; }
  function short(n) {
    if (typeof n !== "number") return "-";
    if (n >= 1e9) return (n / 1e9).toFixed(1) + "b";
    if (n >= 1e6) return (n / 1e6).toFixed(2).replace(/\.00$/, "") + "m";
    if (n >= 1e4) return Math.round(n / 1e3) + "k";
    return num(n);
  }
  /* Same as sections/market.py when(): "6:00 AM PT (13:00 UTC), Oct 27", never the browser's own time zone. */
  var PT_CLOCK, PT_DAY;
  try {
    PT_CLOCK = new Intl.DateTimeFormat("en-US", { timeZone: "America/Los_Angeles", hour: "numeric", minute: "2-digit" });
    PT_DAY = new Intl.DateTimeFormat("en-US", { timeZone: "America/Los_Angeles", month: "short", day: "numeric" });
  } catch (e) { PT_CLOCK = PT_DAY = null; }
  function two(n) { return (n < 10 ? "0" : "") + n; }
  function when(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return "";
    var utc = two(d.getUTCHours()) + ":" + two(d.getUTCMinutes()) + " UTC";
    if (!PT_CLOCK) return utc + ", " + d.toLocaleDateString("en-US", { timeZone: "UTC", month: "short", day: "numeric" });
    return PT_CLOCK.format(d).replace(/\s/g, " ") + " PT (" + utc + "), " + PT_DAY.format(d);
  }
  function priceLink(id, name) {
    var a = el("a", null, name);
    a.href = root + "market/prices/" + id;
    return summary && summary[id] ? a : el("span", null, name);
  }
  function btn(text, cls, fn) {
    var b = el("button", "btn " + (cls || ""), text);
    b.type = "button";
    b.addEventListener("click", fn);
    return b;
  }
  function say(box, text, kind) { if (box) { box.textContent = text || ""; box.className = "status " + (kind || ""); } }
  function act(path, body, statusBox) {
    return api(path, body).then(function (r) {
      if (!r.body.ok) { say(statusBox, r.body.error || "That didn't work. Try again in a bit.", "bad"); return false; }
      say(statusBox, "");
      return load().then(function () { return true; });
    });
  }
  function getJSON(path) {
    return fetch(root + path, { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : null; })
      .catch(function () { return null; });
  }

  // ── item picker: type a name, pick from the list ──
  function picker(input, onPick) {
    var list = el("ul", "dbhits");
    input.parentNode.insertBefore(list, input.nextSibling);
    input.addEventListener("input", function () {
      var q = input.value.trim().toLowerCase();
      input.removeAttribute("data-id");
      list.textContent = "";
      if (q.length < 2) return;
      (items ? Promise.resolve(items) : getJSON("database/search-index.json").then(function (rows) {
        items = (rows || []).filter(function (r) { return r[0] === "i" && !blocked[r[1]]; });
        return items;
      })).then(function (rows) {
        var hits = rows.filter(function (r) { return r[2].toLowerCase().indexOf(q) >= 0; }).slice(0, 10);
        list.textContent = "";
        hits.forEach(function (r) {
          var b = el("button", null, r[2]);
          b.type = "button";
          b.addEventListener("click", function () {
            input.value = r[2]; input.setAttribute("data-id", r[1]); list.textContent = "";
            if (onPick) onPick(r[1], r[2]);
          });
          var li = el("li"); li.appendChild(b); list.appendChild(li);
        });
      });
    });
  }

  // ── badge on the sidebar's "My market" ──
  function badge() {
    document.querySelectorAll("[data-mk-badge]").forEach(function (b) {
      b.textContent = me && me.badge ? String(me.badge) : "";
      b.hidden = !(me && me.badge);
    });
  }

  // ── price page: watch, alert, post ──
  function itemPanel() {
    var panel = document.querySelector("[data-mk-item]");
    if (!panel) return;
    var id = +panel.getAttribute("data-mk-item"), inner = panel.querySelector(".in");
    // the controls are redrawn after every change; the status line under them stays
    var box = inner.querySelector(".mkbody"), status = inner.querySelector(".status");
    if (!box) {
      box = el("div", "mkbody"); status = el("p", "status");
      status.setAttribute("role", "status"); status.setAttribute("aria-live", "polite");
      inner.appendChild(box); inner.appendChild(status);
    }
    box.textContent = "";
    var watching = me.watch.some(function (w) { return w.itemId === id; });
    var row = el("div", "mkrow");
    row.appendChild(btn(watching ? "Watching ✓" : "Watch", watching ? "" : "primary", function () {
      act("watch", { itemId: id, on: !watching }, status);
    }));
    var post = el("a", "btn", "Post WTB / WTS");
    post.href = root + "market/board?item=" + id + "#post";
    row.appendChild(post);
    box.appendChild(row);
    var form = el("form", "mkform");
    var dir = el("select"); ["below", "above"].forEach(function (d) { var o = el("option", null, d); o.value = d; dir.appendChild(o); });
    var price = el("input"); price.type = "number"; price.min = "1"; price.step = "1"; price.placeholder = "price in mesos"; price.required = true;
    form.appendChild(el("span", null, "Alert me when it sells")); form.appendChild(dir); form.appendChild(price);
    var go = el("button", "btn", "Set alert"); go.type = "submit"; form.appendChild(go);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      act("alerts", { itemId: id, dir: dir.value, price: parseInt(price.value, 10) }, status).then(function (ok) {
        if (ok) say(status, "Alert set. You'll get an in-game note and a badge here when it fires.", "ok");
      });
    });
    box.appendChild(form);
    me.alerts.filter(function (a) { return a.itemId === id; }).forEach(function (a) {
      box.appendChild(el("p", "small", "Your alert: " + a.dir + " " + num(a.price) + (a.armed ? " (armed)" : " (fired, re-arm it in My market)")));
    });
    panel.hidden = false;
  }

  // ── My market page ──
  function minePage() {
    var w = document.getElementById("mk-watch"), al = document.getElementById("mk-alerts"), ps = document.getElementById("mk-posts");
    if (!w) return;
    document.getElementById("mk-mine").hidden = false;
    document.getElementById("mk-who").textContent = "Signed in as " + (me.character || "you") + ".";
    var status = document.getElementById("mk-status");
    w.textContent = "";
    if (!me.watch.length) w.appendChild(el("li", "empty", "Nothing yet. Press Watch on any price page."));
    me.watch.forEach(function (x) {
      var li = el("li"), s = summary && summary[x.itemId];
      li.appendChild(priceLink(x.itemId, x.name));
      li.appendChild(el("b", "num", s && s.m ? short(s.m) : "no sales yet"));
      li.appendChild(btn("Remove", "small", function () { act("watch", { itemId: x.itemId, on: false }, status); }));
      w.appendChild(li);
    });
    document.getElementById("mk-watch-n").textContent = me.watch.length + " of " + me.limits.watch;
    al.textContent = "";
    if (!me.alerts.length) al.appendChild(el("li", "empty", "No alerts. Set one on any price page."));
    me.alerts.forEach(function (a) {
      var li = el("li", a["new"] ? "fired" : "");
      li.appendChild(priceLink(a.itemId, a.name));
      li.appendChild(el("span", null, a.dir + " " + num(a.price)));
      li.appendChild(el("span", "muted", a.armed ? "armed" : "fired" + (a.firedPrice ? " at " + num(a.firedPrice) : "") + (a.firedAt ? ", " + when(a.firedAt) : "")));
      var b = el("span", "acts");
      if (!a.armed) b.appendChild(btn("Re-arm", "small", function () { act("alerts/rearm", { id: a.id }, status); }));
      b.appendChild(btn("Delete", "small", function () { act("alerts/delete", { id: a.id }, status); }));
      li.appendChild(b);
      al.appendChild(li);
    });
    document.getElementById("mk-alerts-n").textContent = me.alerts.length + " of " + me.limits.alerts;
    myPosts(ps, status);
    myTrades(document.getElementById("mk-trades"));
    if (me.badge) api("alerts/seen", {});  // seen now; the highlight stays until the next visit
  }

  function myPosts(ps, status) {
    if (!ps) return;
    ps.textContent = "";
    if (!me.posts.length) ps.appendChild(el("li", "empty", "No posts. Post one on the Buy/Sell board."));
    me.posts.forEach(function (p) {
      var li = el("li", p.status === "open" ? "" : "closed");
      li.appendChild(el("span", "sb " + (p.kind === "WTS" ? "s" : "b"), p.kind === "WTS" ? "S>" : "B>"));
      li.appendChild(priceLink(p.itemId, p.name));
      li.appendChild(el("span", "num", num(p.qty) + " × " + num(p.price)));
      li.appendChild(el("span", "muted", p.character + " · " + (p.status === "open" ? "until " + when(p.expiresAt)
        : p.status + (p.reason ? ": " + p.reason : ""))));
      if (p.status === "open") li.appendChild(btn("Take down", "small", function () { act("posts/close", { id: p.id }, status); }));
      ps.appendChild(li);
    });
  }

  // ── Buy/Sell board page ──
  var boardRows = null;
  function myTrades(ul) {
    if (!ul) return;
    // the account's own counts from /me (everyone with 1+ rated trades); without them, the public Traders board's rows
    var own = me.trades && typeof me.trades === "object";
    (own ? Promise.resolve(null) : getJSON("world/traders.json")).then(function (t) {
      ul.textContent = "";
      if (!own && !t) { ul.appendChild(el("li", "empty", "Trade counts aren't available right now.")); return; }
      (me.characters || []).forEach(function (c) {
        var r = own ? (me.trades[c] && [me.trades[c].completed, me.trades[c].fellThrough]) : t[c], li = el("li");
        li.appendChild(el("span", null, c));
        if (r) {
          li.appendChild(el("b", "num", num(r[0]) + " done"));
          li.appendChild(el("span", "muted", num(r[1]) + " fell through · " + Math.round(100 * r[0] / ((r[0] + r[1]) || 1)) + "% completed"));
        } else li.appendChild(el("span", "muted", own ? "no rated trades yet" : "under 5 trades so far"));
        ul.appendChild(li);
      });
      if (!ul.firstChild) ul.appendChild(el("li", "empty", "No characters yet."));
    });
  }

  function boardPage() {
    var form = document.getElementById("post");
    if (!form) return;
    var status = document.getElementById("mk-status");
    form.hidden = false;
    var who = form.querySelector("[name=character]");
    if (!who.options.length) {
      me.characters.forEach(function (c) { var o = el("option", null, c); o.value = c; who.appendChild(o); });
    }
    myPosts(document.getElementById("mk-posts"), status);
    document.getElementById("mk-posts-n").textContent =
      me.posts.filter(function (p) { return p.status === "open"; }).length + " of " + me.limits.posts + " open";
    api("board").then(function (r) { boardRows = (r.body && r.body.posts) || []; drawBoard(); });
  }
  function drawBoard() {
    var body = document.getElementById("mk-board"), f = document.getElementById("mk-filter");
    if (!body || !boardRows) return;
    var kind = f.querySelector("[name=kind]").value, q = f.querySelector("[name=q]").value.trim().toLowerCase();
    body.textContent = "";
    var rows = boardRows.filter(function (p) {
      return (!kind || p.kind === kind) && (!q || p.name.toLowerCase().indexOf(q) >= 0 || p.character.toLowerCase().indexOf(q) >= 0);
    });
    rows.forEach(function (p) {
      var tr = el("tr"), s = summary && summary[p.itemId];
      var tag = el("td"); tag.appendChild(el("span", "sb " + (p.kind === "WTS" ? "s" : "b"), p.kind === "WTS" ? "S>" : "B>"));
      tr.appendChild(tag);
      var name = el("td"); name.appendChild(priceLink(p.itemId, p.name)); tr.appendChild(name);
      tr.appendChild(el("td", "r num", num(p.price)));
      tr.appendChild(el("td", "r num", s && s.m ? short(s.m) : "-"));
      tr.appendChild(el("td", "r num", num(p.qty)));
      tr.appendChild(el("td", p.character ? null : "muted", p.character || "someone"));  // GM or QA posts: no name
      tr.appendChild(el("td", "muted", p.expiresAt ? "until " + when(p.expiresAt) : ""));
      body.appendChild(tr);
    });
    document.getElementById("mk-board-n").textContent = rows.length + " posts";
    document.getElementById("mk-board-empty").hidden = rows.length > 0;
  }
  function wireBoard() {
    var form = document.getElementById("post"), f = document.getElementById("mk-filter");
    if (!form) return;
    var input = form.querySelector("[name=item]"), status = document.getElementById("mk-status");
    picker(input);
    var pre = new URLSearchParams(location.search).get("item");
    if (pre && /^\d+$/.test(pre)) {
      getJSON("database/search-index.json").then(function (rows) {
        items = (rows || []).filter(function (r) { return r[0] === "i"; });
        var hit = items.filter(function (r) { return String(r[1]) === pre; })[0];
        if (hit) { input.value = hit[2]; input.setAttribute("data-id", hit[1]); }
      });
    }
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var id = parseInt(input.getAttribute("data-id"), 10);
      if (!id) { say(status, "Pick the item from the list under the box.", "bad"); return; }
      var q = parseInt(form.querySelector("[name=qty]").value, 10), pr = parseInt(form.querySelector("[name=price]").value, 10);
      if (q * pr > 2147483647) { say(status, "That's over 2,147,483,647 mesos in all, the most one trade can hold. Post a smaller amount.", "bad"); return; }
      act("posts", {
        kind: form.querySelector("[name=kind]:checked").value, itemId: id,
        qty: parseInt(form.querySelector("[name=qty]").value, 10), price: parseInt(form.querySelector("[name=price]").value, 10),
        character: form.querySelector("[name=character]").value
      }, status).then(function (ok) {
        if (ok) say(status, "Posted. It shows on the board (here and on the board NPC at the FM entrance) once the game checks it, within a minute or two.", "ok");
      });
    });
    f.addEventListener("input", drawBoard);
    f.addEventListener("change", drawBoard);
  }

  function load() {
    return api("me").then(function (r) {
      if (r.status !== 200 || !r.body.ok) {
        var out = document.getElementById("mk-signedout");
        if (out) out.hidden = false;
        return;
      }
      me = r.body;
      badge(); itemPanel(); minePage(); boardPage();
    }).catch(function () {});
  }
  Promise.all([getJSON("market/summary.json"), getJSON("market/untradeable.json")]).then(function (r) {
    summary = r[0] || {};
    (r[1] || []).forEach(function (id) { blocked[id] = true; });
    wireBoard(); load();
  });
})();
