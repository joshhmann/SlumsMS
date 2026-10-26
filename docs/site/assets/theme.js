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

/* Secrets: three hidden Slums-chan stickers for signed-in players. The Konami code, typing "slums" outside a text
   box, or clicking the hero picture 7 times in a row. The pictures live in secret/, which only the private build has
   and nginx serves only with a sign-in, so nothing loads until a signed-in player finds one; a signed-out visitor
   gets a hint to sign in instead. Found ones are counted in localStorage. All three unlock the Slums-chan accent,
   which also puts the secrets into the home picture rotation. */
(function () {
  var SECRETS = {
    konami: { src: "peace.webp", w: 726, alt: "Slums-chan looks back over her shoulder with a peace sign" },
    slums: { src: "shoulder.webp", w: 568, alt: "Slums-chan peeks back over her shoulder with a grin" },
    clicks: { src: "look.webp", w: 650, alt: "Slums-chan looks back at you: “Bustin’ chat since 2006!”" }
  };
  var IDS = Object.keys(SECRETS);
  var root = document.documentElement;
  var me = document.querySelector('script[src$="assets/theme.js"]');
  var base = me ? me.getAttribute("src").replace(/assets\/theme\.js$/, "") : "";
  var box = null, signedIn = null;

  function found() {
    try { return JSON.parse(localStorage.getItem("slums-secrets") || "[]"); } catch (err) { return []; }
  }
  function unlocked() { var got = found(); return IDS.every(function (id) { return got.indexOf(id) >= 0; }); }
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
  function showUnlock() {
    var btn = document.querySelector('[data-accent-btn="slumschan"]');
    if (btn) btn.hidden = false;
  }
  function reveal(id) {
    whoami().then(function (ok) {
      if (!ok) {
        var next = encodeURIComponent(location.pathname);
        card('<p class="secret-note"><b>Something&rsquo;s hiding here.</b> <a href="' + base + "login/?next=" + next +
          '">Sign in</a> with your game account to see it.</p>');
        return;
      }
      var s = SECRETS[id], got = found(), was = unlocked();
      if (got.indexOf(id) < 0) got.push(id);
      try { localStorage.setItem("slums-secrets", JSON.stringify(got)); } catch (err) {}
      var left = IDS.length - got.filter(function (g) { return IDS.indexOf(g) >= 0; }).length;
      var note = left > 0 ? left + (left === 1 ? " more is" : " more are") + " hiding somewhere."
        : was ? "You&rsquo;ve found them all." : "That&rsquo;s all of them. <b>The Slums-chan theme is unlocked:</b> pick it next to the colour dots up top.";
      var c = card('<img src="' + base + "secret/" + s.src + '" alt="" width="' + s.w + '" height="820">' +
        '<p class="secret-note"><b>You found a secret.</b> ' + note + "</p>");
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
    typed = (typed + (k.length === 1 ? k : " ")).slice(-5);
    if (typed === "slums") { typed = ""; reveal("slums"); }
  });

  var rotor = document.querySelector("[data-rotor]"), n = 0, last = 0;
  if (!rotor) return;
  rotor.addEventListener("click", function () {
    var now = Date.now();
    n = now - last < 800 ? n + 1 : 1;
    last = now;
    if (n >= 7) { n = 0; reveal("clicks"); }
  });
  // The Slums-chan theme: the secrets join the home rotation (signed in only, since the files need the sign-in).
  if (root.getAttribute("data-accent") === "slumschan" && unlocked()) whoami().then(function (ok) {
    if (!ok) return;
    IDS.forEach(function (id) {
      var img = new Image(SECRETS[id].w, 820);
      img.className = "mascot secret-pic";
      img.src = base + "secret/" + SECRETS[id].src;
      img.alt = SECRETS[id].alt;
      img.loading = "lazy";
      img.setAttribute("aria-hidden", "true");
      rotor.appendChild(img);
    });
  });
})();
