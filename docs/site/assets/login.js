/* Sign in (tools/site/login_service.py): the game username and password, a new account with an invite code, or a
   code typed in game with @weblogin. Without JavaScript the page explains it needs it. The rules are checked here
   for a quick answer; the login service and the game server check them again. */
(function () {
  var box = document.getElementById("login-box");
  if (!box || !window.fetch) return;
  var startBtn = document.getElementById("login-start"), card = document.getElementById("login-code");
  var codeText = document.getElementById("login-code-text"), status = document.getElementById("login-status");
  var copyBtn = document.getElementById("login-copy"), outBtn = document.getElementById("login-out");
  var outLine = document.getElementById("login-out-line"), forms = document.getElementById("login-forms");
  var timer = null;
  function say(text, kind, el) { el = el || status; el.textContent = text; el.className = "status " + (kind || ""); }
  function next() {
    // only a path on this site, never another site
    var n = new URLSearchParams(location.search).get("next") || "";
    return /^\/(?!\/)[\w\-./]*$/.test(n) ? n : "../database/index.html";
  }
  function signedIn(who, el) { clearInterval(timer); say("Signed in as " + (who || "you") + ". Taking you there...", "ok", el); location.href = next(); }
  function asJson(r) { return r.json().then(function (j) { return { status: r.status, body: j }; }, function () { return { status: r.status, body: {} }; }); }

  // ── username and password, or an invite code (ticket 105) ──
  function rule(user, pass) {
    if (!/^[A-Za-z0-9]{4,12}$/.test(user)) return "Usernames are 4 to 12 letters or numbers.";
    if (pass.length < 6 || pass.length > 12) return "Passwords are 6 to 12 characters (the game's login box takes 12).";
    if (!/^[\x21-\x7e]+$/.test(pass)) return "Passwords can't have spaces or special symbols.";
    return null;
  }
  function wire(form, path, fields, extra) {
    var out = form.querySelector(".status"), btn = form.querySelector("button[type=submit]");
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var data = {};
      fields.forEach(function (f) { data[f] = form.elements[f].value.trim(); });
      data.pass = form.elements.pass.value;  // passwords are sent exactly as typed
      var bad = rule(data.user, data.pass) || (extra && extra(form));
      if (bad) { say(bad, "bad", out); return; }
      btn.disabled = true;
      say("Checking...", "", out);
      fetch("../api/login/" + path, { method: "POST", credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify(data) })
        .then(asJson).then(function (r) {
          btn.disabled = false;
          if (r.body.ok) { signedIn(r.body.character || r.body.account, out); return; }
          say(r.body.error || "Something went wrong. Try again in a bit.", "bad", out);
        }).catch(function () { btn.disabled = false; say("Couldn't reach the login service. Try again in a bit.", "bad", out); });
    });
  }
  wire(document.getElementById("login-pass"), "password", ["user"]);
  wire(document.getElementById("login-signup"), "signup", ["code", "user"], function (form) {
    if (!form.elements.code.value.trim()) return "Type the invite code you got from an admin.";
    return form.elements.pass.value === form.elements.pass2.value ? null : "The two passwords don't match.";
  });

  // ── a code typed in game ──
  function poll() {
    fetch("../api/login/poll", { credentials: "same-origin" }).then(asJson).then(function (r) {
      if (r.body.ok) { signedIn(r.body.character); return; }
      if (r.status === 410) { clearInterval(timer); card.hidden = true; startBtn.hidden = false; say(r.body.error, "bad"); }
    }).catch(function () {});
  }
  startBtn.addEventListener("click", function () {
    startBtn.disabled = true;
    say("Getting a code...");
    fetch("../api/login/start", { method: "POST", credentials: "same-origin" }).then(asJson).then(function (r) {
      startBtn.disabled = false;
      if (!r.body.ok) { say(r.body.error || "Something went wrong. Try again in a bit.", "bad"); return; }
      codeText.textContent = r.body.code;
      card.hidden = false; startBtn.hidden = true;
      say("Waiting for you to type it in game...");
      clearInterval(timer); timer = setInterval(poll, 3000);
    }).catch(function () { startBtn.disabled = false; say("Couldn't reach the login service. Try again in a bit.", "bad"); });
  });
  copyBtn.addEventListener("click", function () {
    var text = "@weblogin " + codeText.textContent;
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(function () { copyBtn.textContent = "Copied"; }, function () {});
  });

  fetch("../api/login/me", { credentials: "same-origin" }).then(function (r) { return r.json(); }).then(function (j) {
    if (j.signed_in) { say("You're signed in as " + (j.character || j.account || "a player") + ".", "ok"); outLine.hidden = false; }
  }).catch(function () {});
  outBtn.addEventListener("click", function () {
    fetch("../api/login/logout", { method: "POST", credentials: "same-origin" }).then(function () { say("Signed out."); outLine.hidden = true; });
  });
  forms.hidden = false;
  box.hidden = false;
})();
