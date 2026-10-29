/* Staff Reports (admin/reports; owner, 2026-10-29): every bug report and idea, from the website's form and from @bug /
   @idea in game. Alpine component xreports: list, filter and read through the report service's staff reader
   (/api/report/staff/*, GM level 3+, behind the same guard as /admin/). Read only. The reader never sends IP hashes,
   e-mails or user agents; everything here is shown as text (x-text), never as HTML. #r<id> opens a report. */
(function () {
  var ROOT = (document.querySelector("[data-root]") || { dataset: { root: "../" } }).dataset.root;
  var API = ROOT + "api/report/staff/";

  function get(path) {
    return fetch(API + path, { credentials: "same-origin", headers: { "X-Slums": "1" } })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) { j.status = r.status; return j; });
      })
      .catch(function () { return { ok: false, error: "Can't reach the report service right now.", status: 0 }; });
  }
  function when(t) {
    var d = new Date(t);
    return isNaN(d) ? "" : d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
  function pretty(v) { try { return JSON.stringify(v, null, 2); } catch (e) { return ""; } }

  function xreports() {
    return {
      type: "", source: "", q: "", rows: [], total: 0, found: 0, more: false, next: null, state: "loading", msg: "",
      cur: null, ctx: null, opening: false, when: when, pretty: pretty,
      init: function () {
        var self = this;
        this.load();
        this.fromHash();
        window.addEventListener("hashchange", function () { self.fromHash(); });
      },
      query: function (before) {
        var p = [];
        if (this.type) p.push("type=" + this.type);
        if (this.source) p.push("source=" + this.source);
        if (this.q.trim()) p.push("q=" + encodeURIComponent(this.q.trim()));
        if (before) p.push("before=" + before);
        return "list" + (p.length ? "?" + p.join("&") : "");
      },
      load: function (append) {
        var self = this;
        if (!append) this.state = "loading";
        return get(this.query(append ? this.next : null)).then(function (j) {
          if (!j.ok) { self.state = "error"; self.msg = j.error || "That didn't work."; return; }
          self.state = "ok";
          self.rows = append ? self.rows.concat(j.reports || []) : (j.reports || []);
          self.total = j.total || 0; self.found = j.found || 0; self.more = !!j.more; self.next = j.next;
        });
      },
      fromHash: function () {
        var m = /^#r(\d{1,9})$/.exec(location.hash);
        if (m && (!this.cur || this.cur.id !== +m[1])) this.open(+m[1]);
      },
      open: function (id) {
        var self = this;
        this.opening = true;
        if (location.hash !== "#r" + id) history.replaceState(null, "", "#r" + id);
        get(String(id)).then(function (j) {
          self.opening = false;
          if (!j.ok) { self.cur = null; self.ctx = null; self.msg = j.error || "That report didn't load."; return; }
          self.cur = j.report; self.ctx = j.contextFile; self.msg = "";
          self.$nextTick(function () { var e = document.getElementById("rp-read"); if (e) e.scrollIntoView({ block: "start" }); });
        });
      },
      close: function () { this.cur = null; this.ctx = null; history.replaceState(null, "", location.pathname); },
      label: function (r) { return r.ref || "#" + r.id; },
      kind: function (r) { return r.type === "feature" ? "Idea" : "Bug"; },
      from: function (r) { return r.source === "ingame" ? "In game" : "Website"; },
      vers: function (r) {
        var v = r && r.versions || {}, out = [];
        [["server", "server"], ["server_commit", "commit"], ["site", "site"], ["launcher", "launcher"],
         ["client", "client"], ["clientBuild", "build"]].forEach(function (k) { if (v[k[0]]) out.push(k[1] + " " + v[k[0]]); });
        return out.join(" · ") || "none recorded";
      },
      who: function (c) {
        var ch = c && c.character;
        if (!ch) return "";
        return [ch.name, ch.level ? "Lv " + ch.level : "", ch.job && ch.job.name, ch.map && ch.map.name ? ch.map.name + " (" + ch.map.id + ")" : "",
                ch.x !== undefined ? "x " + ch.x + ", y " + ch.y : ""].filter(Boolean).join(" · ");
      },
      events: function (c) { return (c && c.trail && Array.isArray(c.trail.events)) ? c.trail.events.slice(-30) : []; },
      logs: function (c) { return c && c.clientLogs && c.clientLogs.sections ? Object.keys(c.clientLogs.sections) : []; }
    };
  }

  window.xreports = xreports;
  document.addEventListener("alpine:init", function () { window.Alpine.data("xreports", xreports); });
})();
