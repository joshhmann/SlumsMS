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
