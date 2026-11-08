"""The content atlas on the staff site (admin/atlas/; owner, 2026-11-07): browse every row of a built atlas (quests,
NPCs, items, mobs, maps, scripts, PQ and bosses) and the references the atlas could not resolve, for QA expected
outcomes and the quest/reward audit. The schema is docs/atlas/SCHEMA.md.

Staff only, like the rest of admin/: nginx serves it after the login service says yes (GM level 3+, a fresh sign-in),
nothing on the public site links here, and the only entry point is the Atlas chip in the admin tabs (ADMIN_TABS).

What build() writes into out/admin/atlas/:
  index.html   the page in the site's frame (common.layout), with the atlas's meta and counts in the header
  atlas.js     plain JavaScript, no libraries: search, one row's detail, references, the dangling list
  atlas.css    the page's own rules, in the site's tokens (light/dark and the accent follow the header)
  (the data is NOT here) data_out/<release>/ gets a copy of the atlas folder's known .json files (meta, the eight
               kinds, dangling) and search.json, which this module makes: [kind, id, name, reach] for every row, sorted by
               kind and then id. nginx serves data_out at /admin/atlas/data/, so the page's relative data/<release>/ works.

The page fetches search.json as it opens, and one kind's file only when a row of that kind is opened: items.json is
about 50 MB. The atlas folder itself stays out of git; a build only copies it, never reads the atlas builders.

No atlas folder (or none with a meta.json): the page is still built, with the empty state and no data, so a private
build without an atlas is unchanged. An unknown file in the atlas folder refuses the build: a folder that is not an
atlas build is a mistake, not data.
"""
import json
import os
import re
import shutil

from common import esc, layout, write

ATLAS = "admin/atlas"
KINDS = ("quests", "npcs", "items", "mobs", "maps", "scripts", "pq", "bosses")
KIND_FILES = {k: k + ".json" for k in KINDS}
# the atlas names a kind in the singular: a reference is {"k": "item", "id": 2010007}, dangling.json is keyed by the
# same words, and so is the page's URL hash (#item/2010007). KINDS stays the file names.
KIND_KEY = {"quests": "quest", "npcs": "npc", "items": "item", "mobs": "mob", "maps": "map",
            "scripts": "script", "pq": "pq", "bosses": "boss"}
KEY_KIND = {v: k for k, v in KIND_KEY.items()}
LABELS = {"quests": "Quest", "npcs": "NPC", "items": "Item", "mobs": "Mob", "maps": "Map", "scripts": "Script",
          "pq": "PQ", "bosses": "Boss"}
META, DANGLING, MANIFEST = "meta.json", "dangling.json", "MANIFEST.sha256"
# every file a built atlas folder may hold (docs/atlas/SCHEMA.md); MANIFEST is allowed but not copied
KNOWN = set(KIND_FILES.values()) | {META, DANGLING, MANIFEST}
# the words the page uses for a row's reach; "unobtainable" is never shown as a word (see NOTE)
REACH_LABEL = {"player": "player", "gm": "GM only", "event": "event", "unobtainable": "No known source"}
EMPTY = "No atlas built for this site build"

CSS = r"""/* The content atlas (admin/atlas/), in the site's tokens: light and dark come from the header, like every page. */
.at { display: grid; gap: 16px; }
.at-meta { display: grid; gap: 10px; }
.at-facts { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 14px; margin: 0;
  font: 500 var(--data-size)/var(--data-line) var(--f-mono); }
.at-facts dt { color: var(--muted); }
.at-facts dd { margin: 0; overflow-wrap: anywhere; }
.at-counts { display: flex; flex-wrap: wrap; gap: 4px 18px; margin: 0; font-size: .9rem; color: var(--muted); }
.at-counts b { color: var(--ink); font-family: var(--f-mono); }
.at-find { display: flex; flex-wrap: wrap; gap: 10px 14px; align-items: end; }
.at-find label { display: grid; gap: 4px; font: 600 .78rem var(--f-body); color: var(--muted); }
.at-find input, .at-find select { font: var(--data-size) var(--f-body); color: var(--ink); background: var(--surface);
  border: var(--border) solid var(--edge); border-radius: var(--radius-s); padding: 6px 10px; min-width: 0; }
.at-find input[type=search] { min-width: 240px; }
.at-results { margin-top: 12px; }
.at-results .t td { white-space: nowrap; }
.at-results .t td.at-tn { white-space: normal; overflow-wrap: anywhere; }
.at-badge { display: inline-block; padding: 1px 8px; border: 1.5px solid var(--hair); border-radius: 999px;
  font: 600 .74rem var(--f-mono); color: var(--muted); white-space: nowrap; }
.at-badge.at-player { border-color: var(--up); color: var(--up); }
.at-badge.at-gm { border-color: var(--change); color: var(--change); }
.at-badge.at-event { border-color: var(--sky); color: var(--sky); }
.at-badge.at-unobtainable { border-style: dashed; }
.at-note { font-size: .78rem; color: var(--muted); }
.at-name { margin: 0 0 6px; font: 700 1.3rem var(--f-display); }
.at-id { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; margin: 0 0 10px; }
.at-fields { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 6px 16px; margin: 10px 0; }
.at-fields dt { padding-top: 3px; font: 600 .74rem var(--f-mono); letter-spacing: .06em; text-transform: uppercase;
  color: var(--muted); }
.at-fields dd { margin: 0; min-width: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.at-fields .at-fields { margin: 0; }
.at-list { margin: 0; padding-left: 18px; }
.at-list li { margin: 2px 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.at-src { margin: 0; padding-left: 18px; font: var(--data-size)/1.5 var(--f-mono); overflow-wrap: anywhere; }
.at-none { color: var(--muted); }
.at-miss { color: var(--bad); font-weight: 600; }
.at-ref { font-family: var(--f-mono); }
.at-ids { columns: 2; margin: 0; padding-left: 18px; font-family: var(--f-mono); }
.at-dgroup { margin-bottom: 14px; }
.at-dgroup h3 { margin: 0 0 4px; font: 700 .95rem var(--f-display); }
.at-refs { margin-top: 14px; padding-top: 10px; border-top: 1.5px solid var(--hair); }
@media (max-width: 640px) {
  .at-facts, .at-fields { grid-template-columns: minmax(0, 1fr); }
  .at-find input[type=search] { min-width: 0; width: 100%; }
  .at-ids { columns: 1; }
  .at-results .t td, .at-results .t th { padding: 4px 8px; }
}
"""

# Plain JavaScript, no libraries. Every string from the data goes in with textContent: a name that looks like markup is
# shown as text and can never run. The part above `document` is the search, which the tests run under node.
SCRIPT = r"""/* The content atlas (admin/atlas/): search every row, open one, follow its references, list the dangling ones.
   Plain JavaScript, no libraries and nothing loaded from another site. Everything the data says goes in with
   textContent, never as HTML, so a name that looks like markup is shown as text. */
(function () {
  "use strict";

  /* kinds are named the way the atlas names them, in the singular: a reference is {"k": "item", "id": 2010007} and so
     is dangling.json and the URL hash (#item/2010007). The files and the header counts use the plural. */
  var KINDS = ["quest", "npc", "item", "mob", "map", "script", "pq", "boss"];
  var FILE = {quest: "quests.json", npc: "npcs.json", item: "items.json", mob: "mobs.json", map: "maps.json",
              script: "scripts.json", pq: "pq.json", boss: "bosses.json"};
  var LABEL = {quest: "Quest", npc: "NPC", item: "Item", mob: "Mob", map: "Map", script: "Script",
               pq: "PQ", boss: "Boss"};
  var REACH = {player: "player", gm: "GM only", event: "event", unobtainable: "No known source"};
  // owner-approved wording (docs/site/ADMIN-ATLAS.md): the flag is about what the data shows, not about the game
  var NOTE = "no drop, shop, quest reward, script or cash-shop source was found in the data; not proof it cannot be obtained";
  var PAGE = 200;       /* rows a search shows before "show more" */
  var MAX_ROWS = 200;   /* rows of one list inside a row's detail */

  /* Search the index: id exact, name as a case-insensitive substring; kind and reach filter when given. */
  function searchRows(index, q, kind, reach) {
    var text = String(q === undefined || q === null ? "" : q).trim().toLowerCase();
    var hits = [];
    for (var i = 0; i < index.length; i++) {
      var row = index[i];
      if (kind && row[0] !== kind) continue;
      if (reach && row[3] !== reach) continue;
      if (text && String(row[1]) !== text && String(row[2] || "").toLowerCase().indexOf(text) < 0) continue;
      hits.push(row);
    }
    return hits;
  }

  var api = {searchRows: searchRows, REACH: REACH, NOTE: NOTE};
  if (typeof document === "undefined") {                    /* node: the tests import the search from here */
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    return;
  }

  var root = document.getElementById("atlas");
  if (!root) return;
  var BASE = root.getAttribute("data-root");
  var detail = document.getElementById("at-detail");
  var detailTitle = document.getElementById("at-detail-title");
  var detailBody = document.getElementById("at-detail-body");
  var results = document.getElementById("at-results");
  var message = document.getElementById("at-msg");
  var dangling = document.getElementById("dangling");
  var danglingBody = document.getElementById("dangling-body");
  var form = document.getElementById("at-find");
  var qBox = document.getElementById("at-q");
  var kindBox = document.getElementById("at-kind");
  var reachBox = document.getElementById("at-reach");

  var index = null, indexWait = null, files = {}, names = {}, hits = [], shown = 0, refresh = false;

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }
  function say(text) { if (message) message.textContent = text || ""; }
  function key(kind, id) { return kind + "/" + String(id); }
  function has(kind, id) { return Object.prototype.hasOwnProperty.call(names, key(kind, id)); }
  function label(kind) { return LABEL[kind] || kind; }

  function badge(reach) {
    var shown = REACH[reach] || (reach ? String(reach) : "not given");
    var node = el("span", "at-badge" + (REACH[reach] ? " at-" + reach : ""), shown);
    if (reach === "unobtainable") node.title = NOTE;
    return node;
  }

  function isRef(v) {
    if (!v || typeof v !== "object" || Array.isArray(v)) return false;
    var keys = Object.keys(v);
    return keys.length === 2 && typeof v.k === "string" && keys.indexOf("id") >= 0;
  }

  /* a {"k","id"} reference: a link to that row, marked when the atlas has no row for it */
  function refLink(ref) {
    var kind = String(ref.k || ""), box = el("span", "at-one");
    if (!FILE[kind]) { box.appendChild(el("span", "at-ref", kind + " " + ref.id)); return box; }
    var link = el("a", "at-ref", label(kind) + " " + ref.id);
    link.href = "#" + kind + "/" + encodeURIComponent(String(ref.id));
    box.appendChild(link);
    if (index) { if (!has(kind, ref.id)) box.appendChild(el("span", "at-miss", " missing (dangling)")); }
    else refresh = true;
    return box;
  }

  function table(rows, depth) {
    var cols = [];
    rows.slice(0, MAX_ROWS).forEach(function (row) {
      Object.keys(row).forEach(function (k) { if (cols.indexOf(k) < 0) cols.push(k); });
    });
    var wrap = el("div", "tbl"), t = el("table", "t"), head = el("tr");
    cols.forEach(function (c) { head.appendChild(el("th", null, c)); });
    var thead = el("thead"); thead.appendChild(head); t.appendChild(thead);
    var tbody = el("tbody");
    rows.slice(0, MAX_ROWS).forEach(function (row) {
      var tr = el("tr");
      cols.forEach(function (c) {
        var td = el("td");
        td.appendChild(c in row ? value(row[c], depth + 1) : el("span", "at-none", ""));
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    t.appendChild(tbody); wrap.appendChild(t);
    if (rows.length <= MAX_ROWS) return wrap;
    var box = el("div");
    box.appendChild(wrap);
    box.appendChild(el("p", "small", (rows.length - MAX_ROWS) + " more rows not shown"));
    return box;
  }

  /* any value of a row: scalars, lists, objects and typed references */
  function value(v, depth) {
    if (v === null || v === undefined) return el("span", "at-none", "-");
    if (Array.isArray(v)) {
      if (!v.length) return el("span", "at-none", "empty");
      var objects = v.every(function (x) {
        return x && typeof x === "object" && !Array.isArray(x) && !isRef(x) && Object.keys(x).length > 1;
      });
      if (objects) return table(v, depth);
      var ul = el("ul", "at-list");
      v.slice(0, MAX_ROWS).forEach(function (x) {
        var li = el("li"); li.appendChild(value(x, depth + 1)); ul.appendChild(li);
      });
      if (v.length > MAX_ROWS) ul.appendChild(el("li", "at-none", (v.length - MAX_ROWS) + " more not shown"));
      return ul;
    }
    if (typeof v === "object") {
      if (isRef(v)) return refLink(v);
      if (depth > 6) return el("span", "at-none", "(nested deeper)");
      var dl = el("dl", "at-fields");
      Object.keys(v).forEach(function (k) {
        dl.appendChild(el("dt", null, k));
        var dd = el("dd"); dd.appendChild(value(v[k], depth + 1)); dl.appendChild(dd);
      });
      return dl;
    }
    if (typeof v === "boolean") return el("span", null, v ? "yes" : "no");
    return el("span", null, String(v));
  }

  /* where the row comes from: "path:line" for a file, table/key/snapshot for a database export */
  function sources(list) {
    var ul = el("ul", "at-src");
    list.forEach(function (s) {
      var text;
      if (!s || typeof s !== "object") text = String(s);
      else if (s.kind === "db-export") {
        text = "db export: table " + (s.table || "?") + " · key " + (s.key === undefined || s.key === null ? "?" : s.key) +
               " · snapshot " + (s.snapshot || "?") + (s.date ? " (" + s.date + ")" : "");
      } else {
        text = (s.path || s.kind || "?") + ":" + (s.line === null || s.line === undefined ? "line unknown" : s.line);
      }
      ul.appendChild(el("li", null, text));
    });
    return ul;
  }

  var ORDER = ["type", "lang", "street", "area", "parent", "kind", "path", "lines", "price", "req_level", "req_job",
               "level", "hp", "mp", "exp", "boss", "requirements", "rewards", "chain", "text", "desc", "header",
               "drops", "global_drops", "reactor_drops", "shops", "quest_reward", "quest_require", "quests_start",
               "quests_end", "scripts", "recipes", "stats", "maps", "npcs", "portals", "plife", "calls", "mentions"];
  var HEAD = {id: 1, name: 1, name_note: 1, reach: 1, reach_note: 1, src: 1};

  function render(kind, id, row) {
    clear(detailBody);
    if (!row) {
      detailBody.appendChild(el("p", "status bad", "This row is missing (dangling): the atlas has no row for " +
        label(kind) + " " + id + ", only references to it."));
      detailBody.appendChild(refsButton(kind, id));
      return;
    }
    var head = el("div", "at-row");
    head.appendChild(el("h3", "at-name", row.name || "(no name)"));
    var line = el("p", "at-id");
    line.appendChild(badge(row.reach));
    if (row.name_note) line.appendChild(el("span", "small", row.name_note));
    if (row.reach === "unobtainable") line.appendChild(el("span", "at-note", NOTE));
    else if (row.reach_note) line.appendChild(el("span", "at-note", row.reach_note));
    if (row.reach === "unobtainable" && row.reach_note) line.appendChild(el("span", "at-note", "· " + row.reach_note));
    head.appendChild(line);
    detailBody.appendChild(head);

    var keys = [];
    ORDER.forEach(function (k) { if (k in row && !HEAD[k]) keys.push(k); });
    Object.keys(row).forEach(function (k) { if (!HEAD[k] && keys.indexOf(k) < 0) keys.push(k); });
    var dl = el("dl", "at-fields");
    keys.forEach(function (k) {
      dl.appendChild(el("dt", null, k));
      var dd = el("dd");
      dd.appendChild(value(row[k], 1));
      dl.appendChild(dd);
    });
    if (dl.childNodes.length) detailBody.appendChild(dl);
    if (Array.isArray(row.src) && row.src.length) {
      detailBody.appendChild(el("h4", "at-sub", "Where this row comes from"));
      detailBody.appendChild(sources(row.src));
    }
    detailBody.appendChild(refsButton(kind, id));
    detail.hidden = false;
  }

  function refsButton(kind, id) {
    var box = el("div", "at-refs"), out = el("div");
    var button = el("button", "btn", "Referenced by");
    button.type = "button";
    button.addEventListener("click", function () {
      button.disabled = true;
      button.textContent = "Scanning every kind...";
      clear(out);
      scan(kind, id).then(function (found) {
        button.disabled = false;
        button.textContent = "Scan again";
        if (!found.length) { out.appendChild(el("p", "small", "Nothing in the atlas refers to it.")); return; }
        out.appendChild(el("p", "small", found.length + " row(s) refer to it."));
        var ul = el("ul", "at-list");
        found.forEach(function (hit) {
          var li = el("li"), a = el("a", null, label(hit.k) + " " + hit.id +
            (names[key(hit.k, hit.id)] ? " · " + names[key(hit.k, hit.id)] : ""));
          a.href = "#" + hit.k + "/" + encodeURIComponent(String(hit.id));
          li.appendChild(a); ul.appendChild(li);
        });
        out.appendChild(ul);
      }).catch(function (e) {
        button.disabled = false;
        button.textContent = "Referenced by";
        out.appendChild(el("p", "status bad", "The scan stopped: " + e.message));
      });
    });
    box.appendChild(button);
    box.appendChild(out);
    return box;
  }

  function walk(v, hit) {
    if (!v || typeof v !== "object") return;
    if (isRef(v)) { hit(v); return; }
    if (Array.isArray(v)) { for (var i = 0; i < v.length; i++) walk(v[i], hit); return; }
    Object.keys(v).forEach(function (k) { walk(v[k], hit); });
  }

  /* back references are not in the atlas (docs/atlas/SCHEMA.md): this loads every kind once and looks */
  function scan(kind, id) {
    return Promise.all(KINDS.map(function (k) {
      return loadFile(k).then(function (f) { return f; }, function () { return null; });
    })).then(function (loaded) {
      var found = [], seen = {}, want = String(id);
      KINDS.forEach(function (k, i) {
        var file = loaded[i];
        if (!file) return;
        Object.keys(file).forEach(function (rowId) {
          if (k === kind && String(rowId) === want) return;
          walk(file[rowId], function (ref) {
            if (ref.k !== kind || String(ref.id) !== want) return;
            if (seen[key(k, rowId)]) return;
            seen[key(k, rowId)] = 1;
            found.push({k: k, id: rowId});
          });
        });
      });
      return found;
    });
  }

  function loadFile(kind) {
    if (!files[kind]) {
      files[kind] = fetch(BASE + FILE[kind]).then(function (r) {
        if (!r.ok) throw new Error(FILE[kind] + ": HTTP " + r.status);
        return r.json();
      });
    }
    return files[kind];
  }

  function loadIndex() {
    if (!indexWait) {
      indexWait = fetch(BASE + "search.json").then(function (r) {
        if (!r.ok) throw new Error("search.json: HTTP " + r.status);
        return r.json();
      }).then(function (rows) {
        index = rows;
        rows.forEach(function (r) { names[key(r[0], r[1])] = r[2]; });
        if (refresh) { refresh = false; route(); }
        return rows;
      });
      indexWait.catch(function (e) { say("The search index did not load: " + e.message); });
    }
    return indexWait;
  }

  function run() {
    if (!index) { say("The search index is still loading."); return; }
    say("");
    var q = qBox ? qBox.value : "";
    var kind = kindBox ? kindBox.value : "";
    var reach = reachBox ? reachBox.value : "";
    if (!String(q).trim() && !kind && !reach) { hits = []; clear(results); return; }
    shown = 0;
    hits = searchRows(index, q, kind, reach);
    draw();
  }

  function draw() {
    clear(results);
    shown = Math.min(shown + PAGE, hits.length);
    if (!hits.length) { results.appendChild(el("p", "empty", "No row matches.")); return; }
    var wrap = el("div", "tbl"), t = el("table", "t"), head = el("tr");
    ["Kind", "Id", "Name", "Reach"].forEach(function (h) { head.appendChild(el("th", null, h)); });
    var thead = el("thead"); thead.appendChild(head); t.appendChild(thead);
    var tbody = el("tbody");
    hits.slice(0, shown).forEach(function (r) {
      var tr = el("tr"), id = el("td"), name = el("td", "at-tn"), reach = el("td");
      var href = FILE[r[0]] ? "#" + r[0] + "/" + encodeURIComponent(String(r[1])) : null;
      var idLink = el(href ? "a" : "span", "at-ref", String(r[1])), nameLink = el(href ? "a" : "span");
      if (href) { idLink.href = href; nameLink.href = href; }
      nameLink.textContent = r[2] === null || r[2] === undefined ? "" : String(r[2]);
      id.appendChild(idLink);
      tr.appendChild(el("td", null, label(r[0])));
      tr.appendChild(id);
      name.appendChild(nameLink);
      tr.appendChild(name);
      reach.appendChild(badge(r[3]));
      tr.appendChild(reach);
      tbody.appendChild(tr);
    });
    t.appendChild(tbody); wrap.appendChild(t); results.appendChild(wrap);
    results.appendChild(el("p", "small", shown + " of " + hits.length + " shown"));
    if (hits.length > shown) {
      var more = el("button", "btn", "Show more");
      more.type = "button";
      more.addEventListener("click", draw);
      results.appendChild(more);
    }
  }

  function showDangling() {
    detail.hidden = true;
    dangling.hidden = false;
    clear(danglingBody);
    danglingBody.appendChild(el("p", "small", "Loading..."));
    fetch(BASE + "dangling.json").then(function (r) {
      if (!r.ok) throw new Error("dangling.json: HTTP " + r.status);
      return r.json();
    }).then(function (d) {
      clear(danglingBody);
      danglingBody.appendChild(el("p", "small", "Ids the atlas refers to but has no row for, by kind. Open one to see " +
        "what an audit needs; the id links to what refers to it below."));
      var kinds = Object.keys(d);
      if (!kinds.length) { danglingBody.appendChild(el("p", "empty", "No dangling references.")); return; }
      kinds.forEach(function (k) {
        var ids = d[k] || [], sec = el("div", "at-dgroup");
        sec.appendChild(el("h3", null, label(k) + " (" + ids.length + ")"));
        var ul = el("ul", "at-ids");
        ids.forEach(function (id) {
          var li = el("li");
          if (FILE[k]) {
            var a = el("a", null, String(id));
            a.href = "#" + k + "/" + encodeURIComponent(String(id));
            li.appendChild(a);
          } else { li.textContent = String(id); }
          ul.appendChild(li);
        });
        sec.appendChild(ul);
        danglingBody.appendChild(sec);
      });
    }).catch(function (e) {
      clear(danglingBody);
      danglingBody.appendChild(el("p", "status bad", "The dangling list did not load: " + e.message));
    });
  }

  function show(kind, id) {
    dangling.hidden = true;
    detail.hidden = false;
    detailTitle.textContent = label(kind) + " " + id;
    clear(detailBody);
    detailBody.appendChild(el("p", "small", "Loading..."));
    loadFile(kind).then(function (file) {
      render(kind, id, Object.prototype.hasOwnProperty.call(file, id) ? file[id] : null);
    }).catch(function (e) {
      clear(detailBody);
      detailBody.appendChild(el("p", "status bad", "That file did not load: " + e.message));
    });
  }

  function route() {
    var hash = location.hash.replace(/^#/, "");
    if (!hash) { detail.hidden = true; dangling.hidden = true; return; }
    if (hash === "dangling") { showDangling(); return; }
    var cut = hash.indexOf("/"), kind = cut < 0 ? hash : hash.slice(0, cut), id = cut < 0 ? "" : hash.slice(cut + 1);
    try { id = decodeURIComponent(id); } catch (e) { /* a broken id is no row */ }
    if (FILE[kind] && id) { show(kind, id); return; }
    detail.hidden = true;
    dangling.hidden = true;
  }

  if (form) form.addEventListener("submit", function (e) { e.preventDefault(); run(); });
  if (qBox) qBox.addEventListener("input", run);
  if (kindBox) kindBox.addEventListener("change", run);
  if (reachBox) reachBox.addEventListener("change", run);
  var close = document.getElementById("at-detail-close");
  if (close) close.addEventListener("click", function () { location.hash = ""; });
  var dclose = document.getElementById("dangling-close");
  if (dclose) dclose.addEventListener("click", function () { location.hash = ""; });
  window.addEventListener("hashchange", route);
  loadIndex();
  route();
})();
"""


def safe(name):
    """A release's folder name: it comes from meta.json and becomes a path."""
    return re.sub(r"[^A-Za-z0-9._-]+", "-", str(name)).strip(".-") or "atlas"


def read_meta(atlas_dir, files):
    if META not in files:
        return {}
    with open(os.path.join(atlas_dir, META), encoding="utf-8") as f:
        meta = json.load(f)
    return meta if isinstance(meta, dict) else {}


def index_rows(atlas_dir, files):
    """The search index: [[kind, id, name, reach], ...] for every row, sorted by kind and then id."""
    out = []
    for kind in KINDS:
        name = KIND_FILES[kind]
        if name not in files:
            continue
        with open(os.path.join(atlas_dir, name), encoding="utf-8") as f:
            rows = json.load(f)
        if not isinstance(rows, dict):
            raise ValueError(f"{os.path.join(atlas_dir, name)}: not an object of rows (see docs/atlas/SCHEMA.md)")
        for key, row in rows.items():
            row = row if isinstance(row, dict) else {}
            row_name = row.get("name")
            if not isinstance(row_name, str) or not row_name:
                row_name = str(row.get("id") or key)
            reach = row.get("reach")
            out.append([KIND_KEY[kind], int(key) if str(key).isdigit() else str(key), row_name,
                        reach if isinstance(reach, str) else ""])
    out.sort(key=lambda e: (e[0], 0, e[1], "") if isinstance(e[1], int) else (e[0], 1, 0, str(e[1])))
    return out


def chips():
    """The admin tab row, with the links back up to admin/ (this page is a folder of its own)."""
    from sections import admin
    return admin.tabs("atlas", up="../")


def facts(meta, counts):
    rows = [("Release", meta.get("release")), ("Schema", meta.get("schema_version")),
            ("Server", str(meta.get("server_commit") or "")[:7]), ("Built", meta.get("built_at"))]
    dl = "".join(f'<div><dt>{esc(k)}</dt><dd>{esc(str(v) if v else "not given")}</dd></div>' for k, v in rows)
    sums = " ".join(f"<span><b>{n:,}</b> {esc(k)}</span>"
                    for k, n in ((k, counts.get(k, 0)) for k in KINDS) if n)
    return (f'<dl class="at-facts">{dl}</dl><p class="at-counts">'
            f'{sums or "no rows"} · <b>{sum(counts.values()):,}</b> in all</p>')


def body(meta, counts, root):
    head = (f'<h1>Atlas</h1><p class="id">Every row of the content atlas: quests, NPCs, items, mobs, maps, scripts, '
            f'PQ and bosses, with its requirements, rewards, drops, references and where the row comes from. Staff '
            f'only, read only.</p>\n'
            f'<details class="help"><summary>What this page shows</summary><p class="id">Expected outcomes for the QA '
            f'suite and the quest/reward audit. A row opens from a search hit or from any reference inside another '
            f'row; the reference a row is missing shows as missing (dangling). Nothing here changes the atlas.</p>'
            f'</details>\n')
    if not root:
        return (head + '<section class="panel"><h2 class="bar">Atlas</h2><div class="in">'
                f'<p class="empty">{EMPTY}</p><p class="small">Build the site with <code>--atlas-dir DIR</code> '
                'pointing at a built atlas folder (docs/site/ADMIN-ATLAS.md); the folder is not in git.</p></div></section>')
    kinds = "".join(f'<option value="{esc(KIND_KEY[k])}">{esc(LABELS[k])}</option>' for k in KINDS)
    reach = "".join(f'<option value="{k}">{esc(v)}</option>' for k, v in REACH_LABEL.items())
    return head + f"""<link rel="stylesheet" href="atlas.css">
<div class="at" id="atlas" data-root="{esc(root)}">
<section class="panel"><h2 class="bar">Atlas <small>release {esc(str(meta.get('release')))}</small></h2>
<div class="in at-meta">{facts(meta, counts)}</div></section>
<section class="panel"><h2 class="bar">Find <small>at most 200 at a time</small></h2>
<form class="in at-find" id="at-find" role="search">
<label>Kind <select id="at-kind"><option value="">All</option>{kinds}</select></label>
<label>Reach <select id="at-reach"><option value="">Any</option>{reach}</select></label>
<label>Id or name <input type="search" id="at-q" maxlength="120" placeholder="2010007 or a part of a name"></label>
<button class="btn primary" type="submit">Search</button></form>
<div class="at-results" id="at-results"></div>
<p class="status bad" id="at-msg" role="status" aria-live="polite"></p></section>
<section class="panel" id="at-detail" hidden><h2 class="bar"><span id="at-detail-title">Row</span>
<button class="btn" type="button" id="at-detail-close">Close</button></h2>
<div class="in" id="at-detail-body"></div></section>
<section class="panel" id="dangling" hidden><h2 class="bar"><span>Dangling references</span>
<button class="btn" type="button" id="dangling-close">Close</button></h2>
<div class="in" id="dangling-body"></div></section>
<p class="small"><a href="#dangling">Dangling references</a>: ids the atlas refers to but has no row for.</p>
</div>
<script src="atlas.js" defer></script>"""


def build(out, atlas_dir=None, data_out=None):
    """Write admin/atlas/. atlas_dir: a built atlas folder (meta.json + one file per kind); none builds the page's
    empty state. data_out: where the data goes, OUTSIDE the site tree (nginx serves it at /admin/atlas/data/ behind the
    same staff gate): it is raw game data, not site copy, so it must not be in the folder check.py reads. Returns what
    went in, for the build's print."""
    files = []
    if atlas_dir and os.path.isdir(atlas_dir):
        files = sorted(os.listdir(atlas_dir))
        unknown = [n for n in files if n not in KNOWN]
        if unknown:
            raise ValueError(f"{atlas_dir}: not an atlas folder: unexpected {', '.join(unknown)} "
                             f"(known: {', '.join(sorted(KNOWN))})")
    if files and not data_out:
        raise ValueError("an atlas folder needs data_out (--atlas-data-out): the data is served from outside the site build")
    meta = read_meta(atlas_dir, files) if files else {}
    release = str(meta.get("release") or "").strip()
    kinds = [k for k in KINDS if KIND_FILES[k] in files]
    head = chips()
    if not release or not kinds:  # no atlas in this build: the page says so and loads nothing
        write(out, f"{ATLAS}/index.html", layout("Atlas", head + body({}, {}, None), "../../", "admin",
                                                 "The content atlas, for staff."))
        return {"release": None, "rows": 0, "kinds": {}}
    folder, root = safe(release), f"data/{safe(release)}/"
    rows = index_rows(atlas_dir, files)
    counts = {k: 0 for k in KINDS}
    for row in rows:
        counts[KEY_KIND[row[0]]] += 1
    target = os.path.join(data_out, folder)
    os.makedirs(target, exist_ok=True)
    for name in [META, DANGLING] + [KIND_FILES[k] for k in KINDS]:
        if name in files:  # byte for byte: the page reads them as they were built, MANIFEST is not copied
            shutil.copyfile(os.path.join(atlas_dir, name), os.path.join(target, name))
    with open(os.path.join(target, "search.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    write(out, f"{ATLAS}/index.html", layout("Atlas", head + body(meta, counts, root), "../../", "admin",
                                             "The content atlas, for staff."))
    write(out, f"{ATLAS}/atlas.css", CSS)
    write(out, f"{ATLAS}/atlas.js", SCRIPT)
    return {"release": release, "rows": len(rows), "kinds": counts}
