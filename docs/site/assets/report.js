/* Report form: sends the report as JSON; without JavaScript the form posts normally. */
(function () {
  var form = document.getElementById("report-form");
  if (!form || !window.fetch) return;
  var status = document.getElementById("report-status");
  var button = document.getElementById("report-send");
  // the launcher opens this page as report/?launcher=<version>&client=<version>: those go with the report
  var q = new URLSearchParams(location.search);
  ["launcher", "client"].forEach(function (k) {
    var v = q.get(k);
    if (v && /^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$/.test(v) && form.elements[k]) form.elements[k].defaultValue = v;  // kept by form.reset()
  });
  // signed in: the account's characters to pick from (any name can still be typed)
  fetch("../api/login/me", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : {}; }).then(function (j) {
    var list = document.getElementById("f-chars"), box = form.elements.character;
    if (!j.signed_in || !list) return;
    (j.characters || []).forEach(function (c) { var o = document.createElement("option"); o.value = c; list.appendChild(o); });
    if (box && !box.value && j.character) box.value = j.character;
  }).catch(function () {});
  function say(text, kind) {
    status.textContent = text;
    status.className = "status " + (kind || "");
  }
  form.addEventListener("submit", function (ev) {
    ev.preventDefault();
    var data = {};
    ["type", "title", "details", "character", "where", "website", "launcher", "client"].forEach(function (k) {
      if (form.elements[k]) data[k] = form.elements[k].value;
    });
    button.disabled = true;
    say("Sending...", "");
    fetch(form.getAttribute("action"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    }).then(function (res) {
      return res.json().then(function (j) { return { status: res.status, body: j }; },
                             function () { return { status: res.status, body: null }; });
    }).then(function (r) {
      if (r.body && r.body.ok) {
        say("Thanks! Logged as #" + r.body.id, "ok");
        form.reset();
        return;
      }
      var msg = r.body && r.body.error;
      if (!msg) {
        msg = r.status === 429 ? "Too many reports for now. Please try again later."
          : r.status === 413 ? "That report is too big to send."
          : "Something went wrong. Please try again in a bit.";
      }
      say(msg, "bad");
      button.disabled = false;
    }).catch(function () {
      say("We could not reach the server. Please try again in a bit.", "bad");
      button.disabled = false;
    });
  });
})();
