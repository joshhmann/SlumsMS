/* Sign in with a code typed in game (tools/site/login_service.py). Without JavaScript the page explains it needs it. */
(function () {
  var box = document.getElementById("login-box");
  if (!box || !window.fetch) return;
  var startBtn = document.getElementById("login-start"), card = document.getElementById("login-code");
  var codeText = document.getElementById("login-code-text"), status = document.getElementById("login-status");
  var copyBtn = document.getElementById("login-copy"), outBtn = document.getElementById("login-out");
  var outLine = document.getElementById("login-out-line");
  var timer = null;
  function say(text, kind) { status.textContent = text; status.className = "status " + (kind || ""); }
  function next() {
    // only a path on this site, never another site
    var n = new URLSearchParams(location.search).get("next") || "";
    return /^\/(?!\/)[\w\-./]*$/.test(n) ? n : "../database/index.html";
  }
  function poll() {
    fetch("../api/login/poll", { credentials: "same-origin" }).then(function (r) {
      return r.json().then(function (j) { return { status: r.status, body: j }; });
    }).then(function (r) {
      if (r.body.ok) { clearInterval(timer); say("Signed in as " + (r.body.character || "you") + ". Taking you there...", "ok"); location.href = next(); return; }
      if (r.status === 410) { clearInterval(timer); card.hidden = true; startBtn.hidden = false; say(r.body.error, "bad"); }
    }).catch(function () {});
  }
  startBtn.addEventListener("click", function () {
    startBtn.disabled = true;
    say("Getting a code...");
    fetch("../api/login/start", { method: "POST", credentials: "same-origin" }).then(function (r) {
      return r.json().then(function (j) { return { status: r.status, body: j }; });
    }).then(function (r) {
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
    if (j.signed_in) { say("You're signed in as " + (j.character || "a player") + ".", "ok"); outLine.hidden = false; }
  }).catch(function () {});
  outBtn.addEventListener("click", function () {
    fetch("../api/login/logout", { method: "POST", credentials: "same-origin" }).then(function () { say("Signed out."); outLine.hidden = true; });
  });
  box.hidden = false;
})();
