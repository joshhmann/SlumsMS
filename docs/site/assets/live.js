/* Live panels: the Alpine.js pattern for parts of a page that keep themselves up to date (the market's live parts first,
   then the NX-meso exchange). Load this before Alpine:

     <script src="../assets/live.js"></script>
     <script defer src="../assets/vendor/alpine-3.14.1.min.js"></script>

   and give a panel x-data="live(url, seconds)":

     <section x-data="live('../api/market/board', 30)">
       <p x-show="state === 'signedOut'">Sign in to see this.</p>
       <p x-show="error" x-text="error"></p>
       <template x-for="p in (data && data.posts) || []" :key="p.id"><li x-text="p.name"></li></template>
       <small x-show="data">Updated <span x-text="ago"></span></small>
     </section>

   What every panel gets:
   - data      the last good answer (kept while a refresh is in flight or fails, so the panel never blanks)
   - state     "loading" (first fetch), "ok", "error" (the last fetch failed: retried with a growing wait, up to
               5 minutes) or "signedOut" (a 401: it stops asking until the page is reloaded)
   - error     a line to show the player, or ""
   - ago       "just now", "2 min ago" ... since the last good answer
   - refresh() fetch now
   - send(path, body)  a write: POST JSON with the X-Slums header (the services refuse writes without it), then a
               refresh. Resolves to the service's answer; a refusal ({ok: false, error}) lands in error.
   It pauses while the tab is hidden and refreshes as soon as it is shown again. seconds = 0 means fetch once. */
(function () {
  var MAX_WAIT = 300;

  function ago(t) {
    if (!t) return "";
    var s = Math.round((Date.now() - t) / 1000);
    if (s < 45) return "just now";
    var m = Math.round(s / 60);
    return m < 60 ? m + " min ago" : Math.round(m / 60) + " h ago";
  }

  function live(url, seconds) {
    var every = seconds === undefined ? 30 : seconds;
    return {
      data: null, state: "loading", error: "", at: 0, now: Date.now(), wait: every, timer: null, busy: false,
      get ago() { return this.now && ago(this.at); },

      init: function () {
        var self = this;
        this.onShow = function () { if (!document.hidden) self.refresh(); else self.stop(); };
        document.addEventListener("visibilitychange", this.onShow);
        this.tick = setInterval(function () { self.now = Date.now(); }, 15000);
        this.refresh();
      },
      destroy: function () {
        this.stop();
        clearInterval(this.tick);
        document.removeEventListener("visibilitychange", this.onShow);
      },
      stop: function () { clearTimeout(this.timer); this.timer = null; },
      later: function () {
        var self = this;
        this.stop();
        if (every > 0 && this.state !== "signedOut" && !document.hidden)
          this.timer = setTimeout(function () { self.refresh(); }, this.wait * 1000);
      },

      refresh: function () {
        var self = this;
        if (this.busy || this.state === "signedOut") return Promise.resolve();
        this.busy = true;
        return fetch(url, { credentials: "same-origin", cache: "no-store" }).then(function (r) {
          if (r.status === 401) { self.state = "signedOut"; self.error = ""; return; }
          return r.json().then(function (j) {
            if (!r.ok || (j && j.ok === false)) throw new Error((j && j.error) || "The server said no (" + r.status + ").");
            self.data = j; self.at = self.now = Date.now(); self.state = "ok"; self.error = ""; self.wait = every;
          });
        }).catch(function (e) {
          self.state = "error";
          self.error = e && e.message && !/fetch|network|JSON/i.test(e.message) ? e.message
            : "Can't reach the server right now. Trying again soon.";
          self.wait = Math.min(MAX_WAIT, Math.max(self.wait || 0, 5) * 2);
        }).then(function () { self.busy = false; self.later(); });
      },

      send: function (path, body) {
        var self = this;
        return fetch(path, { method: "POST", credentials: "same-origin",
          headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify(body || {}) })
          .then(function (r) {
            if (r.status === 401) { self.state = "signedOut"; return { ok: false, error: "Sign in first." }; }
            return r.json().catch(function () { return { ok: false, error: "The server said no (" + r.status + ")." }; });
          })
          .catch(function () { return { ok: false, error: "Can't reach the server right now." }; })
          .then(function (j) {
            self.error = j.ok === false ? j.error || "That didn't work." : "";
            if (j.ok !== false) self.refresh();
            return j;
          });
      }
    };
  }

  window.live = live;
  document.addEventListener("alpine:init", function () { window.Alpine.data("live", live); });
})();
