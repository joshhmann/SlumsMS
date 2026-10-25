/* Report form: sends the report as JSON; without JavaScript the form posts normally. */
(function () {
  var form = document.getElementById("report-form");
  if (!form || !window.fetch) return;
  var status = document.getElementById("report-status");
  var button = document.getElementById("report-send");
  function say(text, kind) {
    status.textContent = text;
    status.className = "status " + (kind || "");
  }
  form.addEventListener("submit", function (ev) {
    ev.preventDefault();
    var data = {};
    ["type", "title", "details", "character", "where", "website"].forEach(function (k) {
      data[k] = form.elements[k].value;
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
