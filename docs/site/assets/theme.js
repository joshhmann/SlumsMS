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
  wire(document.querySelector("[data-accent-switch]"), "data-accent", "slums-accent", ["blue", "orange"], "green");
})();

/* Hero art takes turns: a new picture every 7 seconds, paused while the tab is hidden. With reduced motion asked for,
   one picture is picked per visit and stays. */
(function () {
  var box = document.querySelector("[data-rotor]");
  if (!box) return;
  var pics = Array.prototype.slice.call(box.children), at = 0;
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

/* Secrets: three hidden Slums-chan stickers that only show when someone goes looking. Nothing loads until one is found.
   The Konami code, typing "slums" outside a text box, or clicking the hero picture 7 times in a row. Found ones are
   counted in localStorage, so the note can say how many are left. */
(function () {
  var SECRETS = {
    konami: { src: "secret-peace.webp", w: 726, alt: "Slums-chan looks back over her shoulder with a peace sign" },
    slums: { src: "secret-shoulder.webp", w: 568, alt: "Slums-chan peeks back over her shoulder with a grin" },
    clicks: { src: "secret-look.webp", w: 650, alt: "Slums-chan looks back at you: “Bustin’ chat since 2006!”" }
  };
  var me = document.querySelector('script[src$="assets/theme.js"]');
  var base = me ? me.getAttribute("src").replace(/theme\.js$/, "brand/") : "assets/brand/";
  var box = null;

  function found() {
    try { return JSON.parse(localStorage.getItem("slums-secrets") || "[]"); } catch (err) { return []; }
  }
  function close() { if (box) { box.remove(); box = null; } }
  function reveal(id) {
    var s = SECRETS[id], got = found();
    if (got.indexOf(id) < 0) got.push(id);
    try { localStorage.setItem("slums-secrets", JSON.stringify(got)); } catch (err) {}
    close();
    box = document.createElement("div");
    box.className = "secret";
    box.setAttribute("role", "dialog");
    box.setAttribute("aria-label", "A secret");
    var left = Object.keys(SECRETS).length - got.length;
    box.innerHTML = '<button type="button" class="secret-x" aria-label="Close">&times;</button>' +
      '<img src="' + base + s.src + '" alt="" width="' + s.w + '" height="820">' +
      '<p class="secret-note"><b>You found a secret.</b> ' +
      (left > 0 ? left + (left === 1 ? " more is" : " more are") + " hiding somewhere." : "That's all of them. Nice.") + "</p>";
    box.querySelector("img").alt = s.alt;
    box.querySelector(".secret-x").addEventListener("click", close);
    document.body.appendChild(box);
    box.querySelector(".secret-x").focus();
  }

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
  if (rotor) rotor.addEventListener("click", function () {
    var now = Date.now();
    n = now - last < 800 ? n + 1 : 1;
    last = now;
    if (n >= 7) { n = 0; reveal("clicks"); }
  });
})();
