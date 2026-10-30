/* My stamps (market/stamps; 1.6.2, b193): this account's Slums Stamps from the market service (/api/market/stamps),
   which reads the game's private stamps.json (every 15 minutes) and sends only the signed-in account's own row. */
(function () {
  var ROOT = (document.querySelector("[data-root]") || { dataset: { root: "../" } }).dataset.root;

  function when(t) {
    var d = new Date(t);
    return isNaN(d) ? "" : d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }

  function xstamps() {
    return {
      state: "loading", stamps: null, generated: null, when: when,
      init: function () {
        var self = this;
        fetch(ROOT + "api/market/stamps", { credentials: "same-origin", headers: { "X-Slums": "1" } })
          .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j.status = r.status; return j; }); })
          .catch(function () { return { status: 0 }; })
          .then(function (j) {
            if (j.status === 401) { self.state = "signedOut"; return; }
            if (!j.ok) { self.state = "error"; return; }
            self.stamps = typeof j.stamps === "number" ? j.stamps : null;
            self.generated = j.generated || null;
            self.state = self.stamps === null ? "unknown" : "ok";
          });
      }
    };
  }

  window.xstamps = xstamps;
  document.addEventListener("alpine:init", function () { window.Alpine.data("xstamps", xstamps); });
})();
