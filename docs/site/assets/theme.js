/* Light/dark switch. Without JavaScript the switch stays hidden and the system setting decides (tokens.css).
   The choice is kept in localStorage; a small inline script in <head> applies it before the page paints. */
(function () {
  var KEY = "slums-theme", root = document.documentElement, box = document.querySelector("[data-theme-switch]");
  if (!box) return;
  function current() {
    var t = root.getAttribute("data-theme");
    return t === "light" || t === "dark" ? t : "system";
  }
  function show() {
    var t = current();
    box.querySelectorAll("button").forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.getAttribute("data-theme-btn") === t));
    });
  }
  box.addEventListener("click", function (e) {
    var b = e.target.closest("button[data-theme-btn]");
    if (!b) return;
    var t = b.getAttribute("data-theme-btn");
    if (t === "system") root.removeAttribute("data-theme"); else root.setAttribute("data-theme", t);
    try { if (t === "system") localStorage.removeItem(KEY); else localStorage.setItem(KEY, t); } catch (err) {}
    show();
  });
  box.hidden = false;
  show();
})();
