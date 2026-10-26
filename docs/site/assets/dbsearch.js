/* Database search: loads database/search-index.json on first use and lists up to 30 matches.
   Without JavaScript the box stays hidden and the sidebar lists still lead everywhere. */
(function () {
  var form = document.querySelector("form.dbsearch");
  if (!form) return;
  var root = form.getAttribute("data-root") || "", input = form.querySelector("input"), list = form.querySelector(".dbhits");
  var KIND = { i: ["items", "Item"], m: ["mobs", "Monster"], p: ["maps", "Map"], n: ["npcs", "NPC"], q: ["quests", "Quest"] };
  var index = null, loading = null;
  function load() {
    if (!loading) {
      loading = fetch(root + "database/search-index.json").then(function (r) { return r.json(); })
        .then(function (rows) { index = rows.map(function (r) { return [r[0], r[1], r[2], r[2].toLowerCase()]; }); });
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
      a.href = root + "database/" + k[0] + "/" + h[1] + ".html";
      var n = document.createElement("span");
      n.textContent = h[2];
      s.textContent = k[1];
      a.appendChild(n);
      a.appendChild(s);
      li.appendChild(a);
      list.appendChild(li);
    });
    if (!hits.length) { var li = document.createElement("li"); li.className = "none"; li.textContent = "Nothing by that name."; list.appendChild(li); }
  }
  form.addEventListener("submit", function (e) { e.preventDefault(); var a = list.querySelector("a"); if (a) location.href = a.href; });
  input.addEventListener("focus", function () { load(); });
  input.addEventListener("input", function () { load().then(show); });
  form.hidden = false;
})();
