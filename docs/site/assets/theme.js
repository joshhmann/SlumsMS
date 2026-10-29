/* Light/dark and accent switches. Without JavaScript both stay hidden: the system setting decides light or dark and
   the accent is Henesys green (tokens.css). Choices are kept in localStorage; a small inline script in <head>
   applies them before the page paints. */
(function () {
  var root = document.documentElement;
  function wire(box, attr, key, values, fallback) {
    if (!box) return;
    function current() {
      var v = root.getAttribute(attr);
      return values.indexOf(v) >= 0 ? v : fallback;
    }
    function show() {
      var v = current();
      box.querySelectorAll("button").forEach(function (b) {
        b.setAttribute("aria-pressed", String(b.getAttribute(attr + "-btn") === v));
      });
    }
    box.addEventListener("click", function (e) {
      var b = e.target.closest("button[" + attr + "-btn]");
      if (!b) return;
      var v = b.getAttribute(attr + "-btn");
      if (v === fallback) root.removeAttribute(attr); else root.setAttribute(attr, v);
      try { if (v === fallback) localStorage.removeItem(key); else localStorage.setItem(key, v); } catch (err) {}
      show();
    });
    box.hidden = false;
    show();
  }
  wire(document.querySelector("[data-theme-switch]"), "data-theme", "slums-theme", ["light", "dark"], "system");
  wire(document.querySelector("[data-accent-switch]"), "data-accent", "slums-accent", ["blue", "orange", "slumschan"], "green");
})();

/* Hero art takes turns: a new picture every 7 seconds, paused while the tab is hidden. With reduced motion asked for,
   one picture is picked per visit and stays. The list is live: the Slums-chan theme adds the secrets to it. */
(function () {
  var box = document.querySelector("[data-rotor]");
  if (!box) return;
  var pics = box.children, at = 0;
  function show(i) {
    pics[at].classList.remove("is-on");
    pics[at].setAttribute("aria-hidden", "true");
    at = i % pics.length;
    pics[at].classList.add("is-on");
    pics[at].removeAttribute("aria-hidden");
  }
  if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    show(Math.floor(Math.random() * pics.length));
    return;
  }
  setInterval(function () { if (!document.hidden) show(at + 1); }, 7000);
})();

/* World: "in 2 h 14 min" next to an event's start time. The time itself (PT and UTC) is already in the page. */
(function () {
  var els = document.querySelectorAll("time[data-countdown]");
  if (!els.length) return;
  function tick() {
    els.forEach(function (el) {
      var mins = Math.round((Date.parse(el.getAttribute("datetime")) - Date.now()) / 60000);
      var s = el.nextElementSibling && el.nextElementSibling.classList.contains("soon") ? el.nextElementSibling : null;
      if (!s) { s = document.createElement("span"); s.className = "soon"; el.after(s); }
      s.textContent = mins > 60 ? " · in " + Math.floor(mins / 60) + " h " + (mins % 60) + " min"
        : mins > 0 ? " · in " + mins + " min" : mins > -60 ? " · on now" : "";
    });
  }
  tick();
  setInterval(tick, 30000);
})();

/* Secrets: fourteen hidden Slums-chan stickers for signed-in players, each one a card in My cards (market/cards.html).
   The Konami code; typing "slums", "bustin", "henesys", "kerning" or "slime" outside a text box; clicking the hero
   picture 7 times in a row, a page's Slums-chan 5 times or the footer line 3 times; waking the sleeping one on the 404
   page (3 clicks); clicking her speech bubble 5 times; trying all three colour dots within 10 seconds; a visit between
   2 and 5 AM; or 5 minutes without touching a page. The pictures live in secret/, which only the private build has and nginx serves only with a
   sign-in, so nothing loads until a signed-in player finds one; a signed-out visitor gets a hint to sign in instead
   (not for the late-night and idle ones, which would pop up uninvited). Found ones are kept in localStorage and saved
   to the account (the market service, /api/market/cards), merged both ways once per visit, so they follow the player
   to other devices. The first three (the original ones) unlock the Slums-chan accent, so nobody who had it loses it.
   Until then its dot shows locked ("?") and says how to get it. With it picked, the home pictures switch to the spicy
   set: every secret found plus two that only the theme shows (also cards: for the theme, and for finding them all). */
(function () {
  var SECRETS = {
    konami: { src: "peace.webp", w: 726, name: "Peace out", hint: "A classic code from the old days.",
              alt: "Slums-chan looks back over her shoulder with a peace sign" },
    slums: { src: "shoulder.webp", w: 568, name: "Over the shoulder", hint: "Say where you are.",
             alt: "Slums-chan peeks back over her shoulder with a grin" },
    clicks: { src: "look.webp", w: 650, name: "Bustin&rsquo; since 06", hint: "The home page picture wants attention.",
              alt: "Slums-chan looks back at you: “Bustin’ chat since 2006!”" },
    bustin: { src: "lean.webp", w: 801, name: "Big smile", hint: "What has she been doing to chat since 2006?",
              alt: "Slums-chan leans forward with a big smile" },
    sidekick: { src: "kneel.webp", w: 623, name: "Sidekick", hint: "The Slums-chan by a page&rsquo;s heading wants attention too.",
                alt: "Slums-chan kneels and looks back over her shoulder" },
    wake: { src: "lying.webp", w: 668, name: "Wide awake", hint: "Someone&rsquo;s asleep on a page that doesn&rsquo;t exist.",
            alt: "Slums-chan, wide awake now, lies back with her arms behind her head" },
    henesys: { src: "reach.webp", w: 744, name: "C&rsquo;mere", hint: "Name the town that&rsquo;s full again.",
               alt: "Slums-chan winks and reaches out to you" },
    owl: { src: "chin.webp", w: 606, name: "Night owl", hint: "She&rsquo;s up late. Are you?",
           alt: "Slums-chan rests her chin in her hands, a heart beside her" },
    kerning: { src: "bend.webp", w: 714, name: "Oops", hint: "Say the city where the thieves hang out.",
               alt: "Slums-chan bends over and smiles back at you" },
    colours: { src: "sitpeace.webp", w: 445, name: "Peace sit", hint: "Try on every colour, quickly.",
               alt: "Slums-chan sits with her knees up, winking with a peace sign" },
    afk: { src: "lounge.webp", w: 659, name: "AFK", hint: "Sit still for a while.",
           alt: "Slums-chan lies on her front, chin in her hand" },
    footer: { src: "glance.webp", w: 431, name: "Small print", hint: "Read the small print at the bottom. Then poke it.",
              alt: "Slums-chan glances back over her shoulder" },
    slime: { src: "hug.webp", w: 457, name: "Slime hug", hint: "Say her favourite pet.",
             alt: "Slums-chan hugs a blue slime and winks" },
    bubble: { src: "peek.webp", w: 750, name: "Caught you!", hint: "Keep poking what she says.",
              alt: "Slums-chan, caught looking back over her shoulder" }
  };
  var IDS = Object.keys(SECRETS), THEME = ["konami", "slums", "clicks"];
  var THEME_ONLY = [{ src: "shy.webp", w: 582, alt: "Slums-chan kneels with a shy smile" },
                    { src: "dpeace.webp", w: 650, alt: "Slums-chan kneels and throws two peace signs" }];
  var REWARDS = {  // the market service hands these out itself (market_service.py REWARD_CARDS)
    theme: { src: "shy.webp", w: 582, name: "Shy", hint: "Unlock the Slums-chan theme.", alt: THEME_ONLY[0].alt },
    all: { src: "dpeace.webp", w: 650, name: "Double peace", hint: "Find every secret.", alt: THEME_ONLY[1].alt }
  };
  var root = document.documentElement;
  var me = document.querySelector('script[src$="assets/theme.js"]');
  var base = me ? me.getAttribute("src").replace(/assets\/theme\.js$/, "") : "";
  var box = null, signedIn = null;

  function found() {
    try { return JSON.parse(localStorage.getItem("slums-secrets") || "[]"); } catch (err) { return []; }
  }
  function keep(got) { try { localStorage.setItem("slums-secrets", JSON.stringify(got)); } catch (err) {} }
  function unlocked() { var got = found(); return THEME.every(function (id) { return got.indexOf(id) >= 0; }); }
  var api = base + "api/market/cards";
  function save(ids) {  // to the account; a failure is fine, the next visit's merge sends them again
    return fetch(api, { method: "POST", credentials: "same-origin", body: JSON.stringify({ ids: ids }),
                        headers: { "Content-Type": "application/json", "X-Slums": "1" } })
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
  }
  // once per visit (tab), signed in: the account's cards and this browser's are merged both ways
  function merge() {
    return fetch(api, { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        if (!j || !j.found) return null;
        var got = found(), mine = Object.keys(j.found).filter(function (id) { return SECRETS[id]; });
        var missing = got.filter(function (id) { return SECRETS[id] && mine.indexOf(id) < 0; });
        keep(got.concat(mine.filter(function (id) { return got.indexOf(id) < 0; })));
        if (unlocked()) showUnlock();
        return missing.length ? save(missing) : j;
      }).catch(function () { return null; });
  }
  function whoami() {  // asked once per page; no login service (or no network) counts as signed out
    if (!signedIn) signedIn = fetch(base + "api/login/me", { credentials: "same-origin" })
      .then(function (r) { return r.ok ? r.json() : {}; }).then(function (j) { return !!j.signed_in; })
      .catch(function () { return false; });
    return signedIn;
  }
  function close() { if (box) { box.remove(); box = null; } }
  function card(html) {
    close();
    box = document.createElement("div");
    box.className = "secret";
    box.setAttribute("role", "dialog");
    box.setAttribute("aria-label", "A secret");
    box.innerHTML = '<button type="button" class="secret-x" aria-label="Close">&times;</button>' + html;
    box.querySelector(".secret-x").addEventListener("click", close);
    document.body.appendChild(box);
    box.querySelector(".secret-x").focus();
    return box;
  }
  var dot = document.querySelector('[data-accent-btn="slumschan"]');
  function showUnlock() {
    if (!dot) return;
    dot.hidden = false;
    dot.classList.remove("locked");
    dot.setAttribute("title", "Slums-chan");
    dot.setAttribute("aria-label", "Slums-chan");
  }
  // locked: the dot is there with a "?", so players know there's something to find; clicking it explains
  if (dot && !unlocked()) {
    dot.hidden = false;
    dot.classList.add("locked");
    dot.setAttribute("title", "A secret theme (locked)");
    dot.setAttribute("aria-label", "A secret theme (locked)");
    dot.addEventListener("click", function (e) {
      if (unlocked()) return;
      e.stopPropagation();  // don't pick it yet
      var n = found().filter(function (g) { return IDS.indexOf(g) >= 0; }).length;
      card('<p class="secret-note"><b>There&rsquo;s a secret theme here.</b> Slums-chan has hidden ' + IDS.length +
        ' secrets around the site. Find three to unlock her theme. You&rsquo;ve found ' + n + " of " + IDS.length +
        '. <span class="muted">Hint: try a classic code, and say where you are.</span> Signed-in players only: ' +
        'see them in <a href="' + base + 'market/cards">My cards</a>.</p>');
    });
  }
  function reveal(id, quiet) {  // quiet: no sign-in hint, and nothing if it's already found
    if (quiet && found().indexOf(id) >= 0) return;
    whoami().then(function (ok) {
      if (!ok && quiet) return;
      if (!ok) {
        var next = encodeURIComponent(location.pathname);
        card('<p class="secret-note"><b>Something&rsquo;s hiding here.</b> <a href="' + base + "login/?next=" + next +
          '">Sign in</a> with your game account to see it.</p>');
        return;
      }
      var s = SECRETS[id], got = found(), was = unlocked();
      if (got.indexOf(id) < 0) got.push(id);
      keep(got);
      save([id]);
      var left = IDS.length - got.filter(function (g) { return IDS.indexOf(g) >= 0; }).length;
      var note = left > 0 ? left + (left === 1 ? " more is" : " more are") + " hiding somewhere."
        : "That&rsquo;s all of them. Nice.";
      if (!was && unlocked()) note += " <b>The Slums-chan theme is unlocked:</b> pick it next to the colour dots up top.";
      var c = card('<img src="' + base + "secret/" + s.src + '" alt="" width="' + s.w + '" height="820">' +
        '<p class="secret-note"><b>You found a card: ' + s.name + (/[!?.]$/.test(s.name) ? "" : ".") + "</b> " + note +
        ' <a href="' + base + 'market/cards">My cards</a></p>');
      c.querySelector("img").alt = s.alt;
      if (unlocked()) showUnlock();
    });
  }

  if (unlocked()) showUnlock();

  var KONAMI = ["ArrowUp", "ArrowUp", "ArrowDown", "ArrowDown", "ArrowLeft", "ArrowRight", "ArrowLeft", "ArrowRight", "b", "a"];
  var keys = [], typed = "";
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") { close(); return; }
    var t = e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
    var k = e.key.length === 1 ? e.key.toLowerCase() : e.key;
    keys = keys.concat(k).slice(-KONAMI.length);
    if (keys.join() === KONAMI.join()) { keys = []; reveal("konami"); return; }
    typed = (typed + (k.length === 1 ? k : " ")).slice(-8);
    ["slums", "bustin", "henesys", "kerning", "slime"].forEach(function (w) {
      if (typed.slice(-w.length) === w) { typed = ""; reveal(w); }
    });
  });

  // a page's Slums-chan (the picture by the heading), clicked 5 times in a row
  function clicks(el, need, id) {
    var count = 0, at = 0;
    el.addEventListener("click", function () {
      var t = Date.now();
      count = t - at < 800 ? count + 1 : 1;
      at = t;
      if (count >= need) { count = 0; reveal(id); }
    });
  }
  document.querySelectorAll("img.sidekick").forEach(function (el) { clicks(el, 5, "sidekick"); });
  var sleeper = document.querySelector(".notfound img");
  if (sleeper) clicks(sleeper, 3, "wake");
  var small = document.querySelector(".site-foot span");
  if (small) clicks(small, 3, "footer");
  // her speech bubble (sections/buddy.py), clicked 5 times in a row: she turns around
  document.querySelectorAll(".bubble").forEach(function (el) { clicks(el, 5, "bubble"); });

  // all three colour dots tried within 10 seconds
  var tried = {};
  document.querySelectorAll("[data-accent-btn]").forEach(function (b) {
    var k = b.getAttribute("data-accent-btn");
    if (["green", "blue", "orange"].indexOf(k) < 0) return;
    b.addEventListener("click", function () {
      var t = Date.now();
      tried[k] = t;
      if (["green", "blue", "orange"].every(function (c) { return t - (tried[c] || 0) < 10000; })) {
        tried = {}; reveal("colours");
      }
    });
  });
  // late at night (the visitor's own clock), and 5 minutes on a page without touching it
  var h = new Date().getHours();
  if (h >= 2 && h < 5) setTimeout(function () { reveal("owl", true); }, 4000);
  var idle;
  function still() {
    clearTimeout(idle);
    idle = setTimeout(function () { if (!document.hidden) reveal("afk", true); }, 300000);
  }
  ["keydown", "pointerdown", "pointermove", "scroll", "wheel", "touchstart"].forEach(function (e) {
    window.addEventListener(e, still, { passive: true });
  });
  still();

  // signed in (a build with the sign-in button): merge this browser's cards with the account's, once per visit
  var synced = false;
  try { synced = sessionStorage.getItem("slums-cards") === "1"; } catch (err) {}
  var grid = document.getElementById("cards-grid");
  if (document.querySelector("[data-signin]") && (!synced || grid)) merge().then(function (j) {
    if (j) try { sessionStorage.setItem("slums-cards", "1"); } catch (err) {}
    if (grid) cards(grid, j);
  });

  // My cards (market/cards.html): found ones face up with the date, the rest face down with a hint
  function cards(ul, j) {
    var all = IDS.map(function (id) { return [id, SECRETS[id]]; })
      .concat(Object.keys(REWARDS).map(function (id) { return [id, REWARDS[id]]; }));
    var at = (j && j.found) || {};
    if (!j) found().forEach(function (id) { at[id] = ""; });  // no service: this browser's list
    var n = all.filter(function (x) { return x[0] in at; }).length;
    var count = document.getElementById("cards-n");
    if (count) count.textContent = n + " of " + all.length + " found" + (n === all.length ? ". That\u2019s all of them." : ".");
    ul.textContent = "";
    all.forEach(function (x, i) {
      var id = x[0], s = x[1], li = document.createElement("li"), got = id in at;
      li.className = "card" + (got ? "" : " back");
      var num = '<span class="card-n">#' + (i + 1) + "</span>";
      if (got) {
        var day = at[id] ? new Date(at[id]).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) : "";
        li.innerHTML = '<a href="' + base + "secret/" + s.src + '"><img src="' + base + "secret/cards/" + s.src +
          '" alt="" width="' + Math.round(s.w * 360 / 820) + '" height="360" loading="lazy"></a>' + num +
          "<b>" + s.name + "</b>" + (day ? '<span class="card-d">Found ' + day + "</span>" : "");
        li.querySelector("img").alt = s.alt;
      } else {
        li.innerHTML = '<span class="card-q" aria-hidden="true">?</span>' + num + "<b>Not found yet</b>" +
          '<span class="card-d">' + s.hint + "</span>";
      }
      ul.appendChild(li);
    });
    if (!j && document.getElementById("cards-off")) document.getElementById("cards-off").hidden = false;
  }

  // The Slums-chan theme, signed in: every other Slums-chan picture (beside a page's heading, the sleeping one on the
  // 404 page, the empty lists) becomes one of the player's cards. Which ones depends on the page, so it varies.
  if (root.getAttribute("data-accent") === "slumschan" && unlocked()) whoami().then(function (ok) {
    if (!ok) return;
    var got = found();
    var set = IDS.filter(function (id) { return got.indexOf(id) >= 0; }).map(function (id) { return SECRETS[id]; })
      .concat(REWARDS.theme, IDS.every(function (id) { return got.indexOf(id) >= 0; }) ? [REWARDS.all] : []);
    var start = 0;
    for (var i = 0; i < location.pathname.length; i++) start = (start * 31 + location.pathname.charCodeAt(i)) % 9973;
    document.querySelectorAll("img.sidekick, .notfound img, .empty.nap img").forEach(function (img, n) {
      var s = set[(start + n) % set.length];
      img.removeAttribute("srcset");
      img.width = Math.round(s.w * 360 / 820); img.height = 360;
      img.src = base + "secret/cards/" + s.src;
      img.alt = s.alt;
    });
  });

  var rotor = document.querySelector("[data-rotor]");
  if (!rotor) return;
  clicks(rotor, 7, "clicks");
  // The Slums-chan theme: the home pictures switch to the spicy set (signed in only, since the files need the
  // sign-in). The picture on show becomes the first of the set and the others are replaced, so the rotation (which
  // walks rotor.children) carries on; if it has already moved on, the set is added after the normal pictures.
  if (root.getAttribute("data-accent") === "slumschan" && unlocked()) whoami().then(function (ok) {
    if (!ok) return;
    var got = found();
    var set = IDS.filter(function (id) { return got.indexOf(id) >= 0; }).map(function (id) { return SECRETS[id]; })
      .concat(THEME_ONLY);
    function pic(s, img) {
      img = img || new Image();
      img.removeAttribute("srcset");
      img.width = s.w; img.height = 820;
      img.className = "mascot secret-pic" + (img.classList.contains("is-on") ? " is-on" : "");
      img.src = base + "secret/" + s.src;
      img.alt = s.alt;
      return img;
    }
    var first = rotor.children[0];
    if (first && first.classList.contains("is-on")) {
      while (rotor.children.length > 1) rotor.removeChild(rotor.lastElementChild);
      pic(set[0], first).removeAttribute("loading");
      set = set.slice(1);
    }
    set.forEach(function (s) {
      var img = pic(s);
      img.loading = "lazy";
      img.setAttribute("aria-hidden", "true");
      rotor.appendChild(img);
    });
  });
})();

/* Slums-chan's speech bubbles (sections/buddy.py) circle through their lines: the page's own, then a few for every
   page. A fade, unless the visitor asked for less motion; nothing while the tab is hidden. The lines are read again
   every time, so a page can change them (exchange.js, once the exchange is open). */
(function () {
  function read(b) {
    try { var l = JSON.parse(b.getAttribute("data-lines")); return Array.isArray(l) ? l : []; } catch (err) { return []; }
  }
  document.querySelectorAll(".bubble[data-lines]").forEach(function (b) {
    if (read(b).length < 2) return;
    var i = 0, calm = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
    b.setAttribute("aria-live", "off");
    setInterval(function () {
      if (document.hidden) return;
      var lines = read(b);
      if (!lines.length) return;
      i = (i + 1) % lines.length;
      if (calm) { b.textContent = lines[i]; return; }
      b.classList.add("swap");
      setTimeout(function () { b.textContent = lines[i]; b.classList.remove("swap"); }, 250);
    }, 7000);
  });
})();

/* Sign in: once the login service says who's signed in, the header button becomes "Signed in as X · Sign out"
   (character, or the account for a new one). Builds without sign-in have no button; without the service it stays. */
(function () {
  var a = document.querySelector("[data-signin]");
  if (!a || !window.fetch) return;
  var api = a.getAttribute("href") + "../api/login/";
  fetch(api + "me", { credentials: "same-origin" }).then(function (r) { return r.json(); }).then(function (j) {
    if (!j.signed_in) return;
    var who = document.createElement("span"), name = document.createElement("b"), out = document.createElement("button");
    who.className = "who";
    name.textContent = j.character || j.account || "a player";
    out.type = "button"; out.className = "linkbtn"; out.textContent = "Sign out";
    var line = document.createElement("span"), sep = document.createElement("span");
    sep.className = "sep"; sep.textContent = " · ";  // shown as one line where there's room, two in the header
    line.append("Signed in as ", name);
    who.append(line, sep, out);
    if (j.admin) {  // GM level 3+ (the login service decides; nginx checks again at /admin/)
      var adm = document.createElement("a"), sep2 = document.createElement("span");
      adm.href = a.getAttribute("href") + "../admin/"; adm.className = "linkbtn"; adm.textContent = "Admin";
      sep2.className = "sep"; sep2.textContent = " · ";
      who.append(sep2, adm);
    }
    out.style.justifySelf = "start";
    out.addEventListener("click", function () {
      fetch(api + "logout", { method: "POST", credentials: "same-origin" }).then(function () { location.reload(); });
    });
    a.replaceWith(who);
    bell(who);
  }).catch(function () {});

  /* The bell: fired price alerts the player hasn't seen (the "badge" the launcher shows) plus new offers on their
     Buy/Sell posts (offers_new, from the outbox's offers.json), newest first in a small card. Opening it marks them read. */
  function bell(after) {
    var mk = a.getAttribute("href") + "../api/market/";
    fetch(mk + "me", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (me) {
      if (!me) return;
      var btn = document.createElement("button"), n = document.createElement("span");
      btn.type = "button"; btn.className = "bell";
      btn.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" ' +
        'stroke-width="2" stroke-linecap="round"><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 20a2 2 0 0 0 4 0"/></svg>';
      n.className = "bell-n";
      function count(k) {
        n.textContent = k > 9 ? "9+" : String(k); n.hidden = !k;
        btn.setAttribute("aria-label", k ? k + " new notification" + (k === 1 ? "" : "s") : "Notifications");
        btn.title = btn.getAttribute("aria-label");
      }
      btn.appendChild(n);
      var unread = (me.badge || 0) + (me.offers_new || 0);
      count(unread);
      var card = null;
      btn.addEventListener("click", function (e) {
        e.stopPropagation();
        if (card) { card.remove(); card = null; return; }
        card = document.createElement("div");
        card.className = "bell-card";
        var fired = (me.alerts || []).filter(function (x) { return x.firedAt; }).map(function (x) {
          return { t: x.firedAt, isNew: x.new, text: x.name + (x.dir === "below" ? " dropped to " : " rose to ") +
            Number(x.firedPrice || x.price).toLocaleString("en-US") + " mesos" }; })
          .concat((me.offers || []).map(function (o) {
            return { t: o.t, isNew: o.new, text: o.from + " offered " + Number(o.price).toLocaleString("en-US") +
              " mesos on your " + (o.kind === "WTB" ? "WTB" : "WTS") + " " + o.name }; }))
          .sort(function (x, y) { return String(y.t).localeCompare(String(x.t)); }).slice(0, 8);
        var mine = a.getAttribute("href") + "../market/mine";
        card.innerHTML = "<b>Notifications</b>" + (fired.length ? "" : '<p class="muted">Nothing yet. Price alerts going off and offers on your Buy/Sell posts show up here.</p>');
        var ul = document.createElement("ul");
        fired.forEach(function (x) {
          var li = document.createElement("li"), link = document.createElement("a");
          link.href = mine;
          link.textContent = x.text;
          if (x.isNew) li.className = "new";
          li.appendChild(link);
          ul.appendChild(li);
        });
        if (fired.length) card.appendChild(ul);
        var all = document.createElement("a"); all.href = mine; all.className = "small"; all.textContent = "My market \u00bb";
        card.appendChild(all);
        btn.after(card);
        if (unread) {
          fetch(mk + "alerts/seen", { method: "POST", credentials: "same-origin",
            headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: "{}" })
            .then(function (r) { if (r.ok) { me.badge = 0; me.offers_new = 0; unread = 0; count(0); } }).catch(function () {});
        }
      });
      document.addEventListener("click", function (e) { if (card && !card.contains(e.target)) { card.remove(); card = null; } });
      document.addEventListener("keydown", function (e) { if (e.key === "Escape" && card) { card.remove(); card = null; } });
      after.before(btn);
    }).catch(function () {});
  }
})();

/* The home page's "Bots at peak" becomes "Online now" when World's online.json is there (it's rebuilt every couple
   of minutes). Without it, or in a build without World, the line stays as written. */
(function () {
  var box = document.querySelector("[data-online]");
  if (!box || !window.fetch) return;
  fetch("world/online.json", { cache: "no-store" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
    if (!j || typeof j.online !== "number") return;
    box.querySelector("dt").textContent = "Online now";
    box.querySelector("dd").textContent = j.online.toLocaleString("en-US");
  }).catch(function () {});
})();

/* Charts (sections/market.py area_chart): hovering a day shows a guide line and that day's numbers. Each day's
   <title> is the fallback without script. */
(function () {
  document.querySelectorAll(".chart-box").forEach(function (box) {
    var svg = box.querySelector("svg"), guide = box.querySelector(".ch-guide"), tip = box.querySelector(".ch-tip");
    if (!svg || !guide || !tip) return;
    box.querySelectorAll(".ch-hit title").forEach(function (t) { t.remove(); });  // the script tip replaces them
    svg.addEventListener("pointerover", function (e) {
      var hit = e.target.closest(".ch-hit");
      if (!hit) return;
      var x = hit.getAttribute("data-x");
      guide.setAttribute("x1", x); guide.setAttribute("x2", x); guide.hidden = false;
      tip.textContent = hit.getAttribute("data-tip");
      var vb = svg.viewBox.baseVal, scale = svg.getBoundingClientRect().width / vb.width;
      var left = Math.min(Math.max(x * scale, tip.offsetWidth / 2 || 60), box.clientWidth - (tip.offsetWidth / 2 || 60));
      tip.style.left = left + "px"; tip.hidden = false;
    });
    svg.addEventListener("pointerleave", function () { guide.hidden = true; tip.hidden = true; });
  });
})();

/* Slums Street's "Hot right now": the busiest maps, the most-sold items this week and the most-hunted monsters, from
   world/hot.json (World rebuilds it every couple of minutes). Without the file the panel stays hidden. */
(function () {
  var box = document.querySelector("[data-hot]");
  if (!box || !window.fetch) return;
  var root = box.getAttribute("data-root") || "";
  fetch(box.getAttribute("data-hot"), { cache: "no-store" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
    if (!j) return;
    var groups = [["maps", "Busiest maps", "here now", "database/maps/"], ["mobs", "Most hunted (" + (j.mobs_window || "24 h") + ")", "kills (est.)", "database/mobs/"],
                  ["items", "Most sold (7 days)", "sold", "market/prices/"]];
    var grid = box.querySelector(".hotgrid"), any = false;
    groups.forEach(function (g) {
      var rows = (j[g[0]] || []).filter(function (r) { return r && r.name; });
      if (!rows.length) return;
      any = true;
      var col = document.createElement("div"), h = document.createElement("h3"), ol = document.createElement("ol");
      h.textContent = g[1];
      rows.forEach(function (r) {
        var li = document.createElement("li"), label = document.createElement(g[0] === "items" || r.page ? "a" : "span");
        label.textContent = r.name;
        if (label.tagName === "A") label.href = root + g[3] + r.id;
        var n = document.createElement("small");
        n.textContent = " " + Number(r.count || 0).toLocaleString("en-US") + " " + g[2];
        li.append(label, n);
        ol.appendChild(li);
      });
      col.append(h, ol);
      grid.appendChild(col);
    });
    if (any) box.hidden = false;
  }).catch(function () {});
})();

/* Price charts with 7 / 14 / 30 day buttons (sections/market.py ranged_chart): the buttons show when script runs. */
(function () {
  document.querySelectorAll(".ranged").forEach(function (box) {
    var chips = box.querySelector(".range-chips");
    if (!chips) return;
    chips.hidden = false;
    chips.addEventListener("click", function (e) {
      var b = e.target.closest("[data-range]");
      if (!b) return;
      var n = b.getAttribute("data-range");
      chips.querySelectorAll("[data-range]").forEach(function (x) { x.setAttribute("aria-pressed", String(x === b)); });
      box.querySelectorAll(".range-view").forEach(function (v) { v.hidden = v.getAttribute("data-view") !== n; });
    });
  });
})();
