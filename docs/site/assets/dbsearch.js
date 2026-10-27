/* Search boxes: load their index (data-index, default database/search-index.json) on first use and list up to 30
   matches. Slums Street searches everything; the Market searches items with a price page ("$" rows).
   Without JavaScript the box stays hidden and the sidebar lists still lead everywhere. */
(function () {
  var form = document.querySelector("form.dbsearch");
  if (!form) return;
  var root = form.getAttribute("data-root") || "", input = form.querySelector("input"), list = form.querySelector(".dbhits");
  var KIND = { i: ["database/items", "Item"], m: ["database/mobs", "Monster"], p: ["database/maps", "Map"],
               n: ["database/npcs", "NPC"], q: ["database/quests", "Quest"], $: ["market/prices", "Price"] };
  var index = null, loading = null;
  function load() {
    if (!loading) {
      loading = fetch(root + (form.getAttribute("data-index") || "database/search-index.json")).then(function (r) { return r.json(); })
        .then(function (rows) { index = rows.map(function (r) { return [r[0], r[1], r[2], r[2].toLowerCase(), r[3]]; }); });
    }
    return loading;
  }
  function show() {
    var q = input.value.trim().toLowerCase();
    list.textContent = "";
    if (!q || !index) return;
    var hits = [];
    for (var i = 0; i < index.length && hits.length < 200; i++) if (index[i][3].indexOf(q) >= 0) hits.push(index[i]);
    hits.sort(function (a, b) { return (a[3].indexOf(q) === 0 ? 0 : 1) - (b[3].indexOf(q) === 0 ? 0 : 1) || a[3].length - b[3].length; });
    hits.slice(0, 30).forEach(function (h) {
      var k = KIND[h[0]], li = document.createElement("li"), a = document.createElement("a"), s = document.createElement("small");
      a.href = root + k[0] + "/" + h[1] + ".html";
      var n = document.createElement("span");
      n.textContent = h[2];
      s.textContent = h[4] || k[1];  // a Market row shows its median price instead of the kind
      a.appendChild(n);
      a.appendChild(s);
      li.appendChild(a);
      list.appendChild(li);
    });
    if (!hits.length) { var li = document.createElement("li"); li.className = "none"; li.textContent = form.getAttribute("data-index") ? "No sales of that yet." : "Nothing by that name."; list.appendChild(li); }
  }
  form.addEventListener("submit", function (e) { e.preventDefault(); var a = list.querySelector("a"); if (a) location.href = a.href; });
  input.addEventListener("focus", function () { load(); });
  input.addEventListener("input", function () { load().then(show); });
  form.hidden = false;
})();
