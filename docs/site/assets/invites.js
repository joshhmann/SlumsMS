/* Invite codes (1.6.1, ticket 105): "My invites" (market/invites) for players and the staff page (admin/invites).
   Alpine component xinvites(staff): list, make and cancel through the login service (/api/login/invites/*), which
   asks the game. The game decides who may do what (players: level 30+, 2 a week, the same count as @invite in game;
   staff: every code, with a life and a note). Times may come as Unix seconds or ISO text. */
(function () {
  var ROOT = (document.querySelector("[data-root]") || { dataset: { root: "../" } }).dataset.root;
  var API = ROOT + "api/login/invites/";

  function when(t) {
    if (t === null || t === undefined || t === "") return "";
    var d = new Date(typeof t === "number" ? t * 1000 : t);
    return isNaN(d) ? "" : d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
  function ms(t) { return t === null || t === undefined ? NaN : new Date(typeof t === "number" ? t * 1000 : t).getTime(); }

  function post(path, body) {
    return fetch(API + path, { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify(body || {}) })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) { j.status = r.status; return j; });
      })
      .catch(function () { return { ok: false, error: "Can't reach the server right now.", status: 0 }; });
  }

  function xinvites(staff) {
    return {
      staff: !!staff, codes: [], left: null, page: 1, more: false, state: "loading", msg: "", msgClass: "", busy: false,
      made: null, life: 14, forever: false, note: "", when: when,
      init: function () { this.load(); },
      say: function (t, ok) { this.msg = t; this.msgClass = ok === true ? "ok" : ok === false ? "bad" : ""; },
      load: function () {
        var self = this;
        return post("list", this.staff ? { page: this.page } : {}).then(function (j) {
          if (j.status === 401) { self.state = "signedOut"; return; }
          if (!j.ok) { self.state = "error"; self.say(j.error || "That didn't work.", false); return; }
          self.state = "ok";
          self.codes = Array.isArray(j.codes) ? j.codes : [];
          self.left = typeof j.left === "number" ? j.left : null;
          self.more = !!(j.more || j.next);  // pages are 1-based (ticket 105)
        });
      },
      // the game's status (open/unused, used, expired, cancelled, cancelled_by_staff); worked out when it's missing
      kind: function (c) {
        var st = typeof c.status === "string" ? c.status : "";
        if (st === "open") st = "unused";
        if (st) return st;
        if (c.usedBy || c.usedAt) return "used";
        if (c.cancelled === true || c.cancelledAt) return "cancelled";
        var end = ms(c.expiresAt);
        return !isNaN(end) && end < Date.now() ? "expired" : "unused";
      },
      status: function (c) {
        var k = this.kind(c);
        if (k === "used") return "Used" + (typeof c.usedBy === "string" && c.usedBy ? " by " + c.usedBy : "")
          + (c.usedAt ? ", " + when(c.usedAt) : "");
        return { unused: "Unused", expired: "Expired", cancelled: "Cancelled", cancelled_by_staff: "Cancelled by staff" }[k] || k;
      },
      until: function (c) { return c.expiresAt === null ? "Never expires" : when(c.expiresAt); },
      open: function (c) { return this.kind(c) === "unused"; },
      make: function () {
        var self = this, body = this.staff ? { life: this.forever ? "forever" : this.life, note: this.note } : {};
        this.busy = true; this.say("Making a code...");
        post("create", body).then(function (j) {
          self.busy = false;
          if (!j.ok) { self.say(j.error || "That didn't work.", false); return; }
          self.made = { code: j.code, expiresAt: j.expiresAt };
          if (typeof j.left === "number") self.left = j.left;
          self.say("Done. Send it to your friend: it works once.", true);
          self.note = "";
          self.load();
        });
      },
      cancel: function (c) {
        var self = this;
        this.busy = true;
        post("cancel", { code: c.code }).then(function (j) {
          self.busy = false;
          if (!j.ok) { self.say(j.error || "That didn't work.", false); return; }
          self.say("Cancelled " + c.code + ".", true);
          if (self.made && self.made.code === c.code) self.made = null;
          self.load();
        });
      },
      copy: function (text) {
        var self = this;
        function done(ok) { self.say(ok ? "Copied " + text + "." : "Couldn't copy: select it and copy by hand.", ok); }
        // Plain http (the TEST site) has no navigator.clipboard: copy from a hidden text box instead (qw06).
        function old() {
          var t = document.createElement("textarea"), ok = false;
          t.value = text; t.setAttribute("readonly", ""); t.style.position = "fixed"; t.style.opacity = "0";
          document.body.appendChild(t); t.select();
          try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
          document.body.removeChild(t);
          done(ok);
        }
        if (navigator.clipboard && window.isSecureContext) {
          navigator.clipboard.writeText(text).then(function () { done(true); }, old);
        } else old();
      },
      go: function (d) { this.page = Math.max(1, this.page + d); this.load(); }
    };
  }

  window.xinvites = xinvites;
  document.addEventListener("alpine:init", function () { window.Alpine.data("xinvites", xinvites); });
})();
