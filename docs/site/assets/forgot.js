/* Forgot password (tools/site/login_service.py, ticket 107): ask for a reset, wait for an admin to approve it in
   game, then set a new password. The request is kept in an HttpOnly cookie, so this page asks the service where it
   stands; it checks every 10 seconds while it waits. */
(function () {
  var app = document.getElementById("fp-app");
  if (!app || !window.fetch) return;
  var ask = document.getElementById("fp-ask"), wait = document.getElementById("fp-wait"), set = document.getElementById("fp-set");
  var waitStatus = document.getElementById("fp-wait-status");
  var timer = null;
  function say(el, text, kind) { el.textContent = text; el.className = "status " + (kind || ""); }
  function call(step, body) {
    return fetch("../api/login/reset/" + step, { method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json().then(function (j) { return { status: r.status, body: j }; }, function () { return { status: r.status, body: {} }; }); });
  }
  function show(state) {
    clearInterval(timer);
    ask.hidden = !(state === "none" || state === "denied" || state === "expired");
    wait.hidden = state !== "pending";
    set.hidden = state !== "approved";
    if (state === "pending") timer = setInterval(check, 10000);
    if (state === "denied") say(ask.querySelector(".status"), "An admin turned that request down. Talk to an admin, or ask again.", "bad");
    if (state === "expired") say(ask.querySelector(".status"), "That request ran out. Ask again.", "bad");
  }
  function check() {
    call("status").then(function (r) {
      if (r.body.ok) { show(r.body.status); return; }
      say(waitStatus, r.body.error || "Couldn't check just now; trying again.", "bad");
    }).catch(function () { say(waitStatus, "Couldn't reach the login service; trying again.", "bad"); });
  }
  ask.addEventListener("submit", function (e) {
    e.preventDefault();
    var out = ask.querySelector(".status"), user = ask.elements.user.value.trim(), contact = ask.elements.contact.value.trim();
    if (!user || contact.length < 2) { say(out, "Type your game username and how an admin can reach you.", "bad"); return; }
    say(out, "Sending...");
    call("request", { user: user, contact: contact }).then(function (r) {
      if (r.body.ok) { say(out, ""); show("pending"); return; }
      say(out, r.body.error || "Something went wrong. Try again in a bit.", "bad");
    }).catch(function () { say(out, "Couldn't reach the login service. Try again in a bit.", "bad"); });
  });
  document.getElementById("fp-again").addEventListener("click", function () { show("none"); });
  set.addEventListener("submit", function (e) {
    e.preventDefault();
    var out = set.querySelector(".status"), pass = set.elements.pass.value;
    if (pass.length < 6 || pass.length > 23 || !/^[\x21-\x7e]+$/.test(pass)) { say(out, "Passwords are 6 to 23 characters, no spaces.", "bad"); return; }
    if (pass !== set.elements.pass2.value) { say(out, "The two passwords don't match.", "bad"); return; }
    say(out, "Setting it...");
    call("complete", { pass: pass }).then(function (r) {
      if (r.body.ok) { say(out, "Done. You're signed in as " + (r.body.character || r.body.account) + ". Use the new password in the game too.", "ok");
        setTimeout(function () { location.href = "../database/"; }, 2500); return; }
      if (r.status === 410) { show("none"); say(ask.querySelector(".status"), r.body.error, "bad"); return; }
      say(out, r.body.error || "Something went wrong. Try again in a bit.", "bad");
    }).catch(function () { say(out, "Couldn't reach the login service. Try again in a bit.", "bad"); });
  });
  app.hidden = false;
  check();
})();
