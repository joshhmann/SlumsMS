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
