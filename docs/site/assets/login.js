/* Sign in (tools/site/login_service.py): the game username and password, or a new account with an invite code.
   Without JavaScript the page explains it needs it. The sign-up rules are checked here for a quick answer; the login
   service and the game server check them again. Sign-in only checks the fields aren't empty: older accounts may
   break the sign-up rules, so the game decides. */
(function () {
  var forms = document.getElementById("login-forms");
  if (!forms || !window.fetch) return;
  var status = document.getElementById("login-status"), outBtn = document.getElementById("login-out");
  var outLine = document.getElementById("login-out-line");
  function say(text, kind, el) { el = el || status; el.textContent = text; el.className = "status " + (kind || ""); }
  function next() {
    // only a path on this site, never another site
    var n = new URLSearchParams(location.search).get("next") || "";
    return /^\/(?!\/)[\w\-./]*$/.test(n) ? n : "../database/";
  }
  function signedIn(who, el) { say("Signed in as " + (who || "you") + ". Taking you there...", "ok", el); location.href = next(); }
  function asJson(r) { return r.json().then(function (j) { return { status: r.status, body: j }; }, function () { return { status: r.status, body: {} }; }); }

  function rule(user, pass) {
    if (!/^[A-Za-z0-9]{4,12}$/.test(user)) return "Usernames are 4 to 12 letters or numbers.";
    if (pass.length < 6 || pass.length > 23) return "Passwords are 6 to 23 characters (the game's login box takes 23).";
    if (!/^[\x21-\x7e]+$/.test(pass)) return "Passwords can't have spaces or special symbols.";
    return null;
  }
  function wire(form, path, fields, check) {
    var out = form.querySelector(".status"), btn = form.querySelector("button[type=submit]");
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var data = {};
      fields.forEach(function (f) { data[f] = form.elements[f].value.trim(); });
      data.pass = form.elements.pass.value;  // passwords are sent exactly as typed
      var bad = check(form, data);
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
  wire(document.getElementById("login-pass"), "password", ["user"], function (form, data) {
    return data.user && data.pass ? null : "Type your game username and password.";
  });
  wire(document.getElementById("login-signup"), "signup", ["code", "user"], function (form, data) {
    if (!data.code) return "Type the invite code you got from an admin.";
    return rule(data.user, data.pass) ||
      (form.elements.pass.value === form.elements.pass2.value ? null : "The two passwords don't match.");
  });

  // signed in already: no forms, just where to go and the sign-out line; signing out brings the forms back.
  // ?again=1 (the exchange or /admin/, when the sign-in is over 12 h old) keeps the forms: a fresh sign-in is the point.
  var go = document.getElementById("login-go");
  var again = new URLSearchParams(location.search).get("again") === "1";
  fetch("../api/login/me", { credentials: "same-origin" }).then(function (r) { return r.json(); }).then(function (j) {
    if (j.signed_in && again) {
      say("You're signed in as " + (j.character || j.account || "a player")
        + ", but that page needs a sign-in from the last 12 hours. Sign in again below.", "");
    } else if (j.signed_in && new URLSearchParams(location.search).get("next")) {
      // already signed in and sent here on the way somewhere (e.g. the guide's FAQ to My invites, b194): go on
      signedIn(j.character || j.account);
    } else if (j.signed_in) {
      say("You're signed in as " + (j.character || j.account || "a player") + ".", "ok");
      // the links are added here: a public page never links to the signed-in sections (check.py)
      go.innerHTML = '<a class="btn primary" href="../database/">Slums Street</a><a class="btn" href="../market/">Slums Market</a>';
      outLine.hidden = false; go.hidden = false; forms.hidden = true;
    }
  }).catch(function () {});
  outBtn.addEventListener("click", function () {
    fetch("../api/login/logout", { method: "POST", credentials: "same-origin" }).then(function () {
      say("Signed out."); outLine.hidden = true; go.hidden = true; forms.hidden = false;
    });
  });
  forms.hidden = false;
})();
