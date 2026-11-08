/* Light/dark and accent switches. Without JavaScript both stay hidden: the system setting decides light or dark and
   the accent is Henesys green (tokens.css). Choices are kept in localStorage; a small inline script in <head>
   applies them before the page paints. */
(function () {
  var root = document.documentElement;
  function wire(box, attr, key, values, fallback) {
    if (!box) return;
    function current() {
      var v = root.getAttribute(attr);
      return values.indexOf(v) >= 0 ? v : fallback;
    }
    function show() {
      var v = current();
      box.querySelectorAll("button").forEach(function (b) {
        b.setAttribute("aria-pressed", String(b.getAttribute(attr + "-btn") === v));
      });
    }
    box.addEventListener("click", function (e) {
      var b = e.target.closest("button[" + attr + "-btn]");
      if (!b) return;
      var v = b.getAttribute(attr + "-btn");
      if (v === fallback) root.removeAttribute(attr); else root.setAttribute(attr, v);
      try { if (v === fallback) localStorage.removeItem(key); else localStorage.setItem(key, v); } catch (err) {}
      show();
    });
    box.hidden = false;
    show();
  }
  wire(document.querySelector("[data-theme-switch]"), "data-theme", "slums-theme", ["light", "dark"], "system");
  wire(document.querySelector("[data-accent-switch]"), "data-accent", "slums-accent", ["blue", "orange", "slumschan"], "green");
})();

/* Hero art takes turns: a new picture every 7 seconds, paused while the tab is hidden. With reduced motion asked for,
   one picture is picked per visit and stays. The list is live: the Slums-chan theme adds the secrets to it. The season
   pictures (data-months, 1-12) take part only in their months, on the visitor's own clock. */
(function () {
  var box = document.querySelector("[data-rotor]");
  if (!box) return;
  var month = String(new Date().getMonth() + 1);
  Array.prototype.slice.call(box.querySelectorAll("[data-months]")).forEach(function (img) {
    if (img.getAttribute("data-months").split(" ").indexOf(month) < 0) img.remove();
  });
  var pics = box.children, at = 0;
  function show(i) {
    pics[at].classList.remove("is-on");
    pics[at].setAttribute("aria-hidden", "true");
    at = i % pics.length;
    pics[at].classList.add("is-on");
    pics[at].removeAttribute("aria-hidden");
  }
  if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    show(Math.floor(Math.random() * pics.length));
    return;
  }
  setInterval(function () { if (!document.hidden) show(at + 1); }, 7000);
})();

/* World: "in 2 h 14 min" next to an event's start time. The time itself (PT and UTC) is already in the page. */
(function () {
  var els = document.querySelectorAll("time[data-countdown]");
  if (!els.length) return;
  function tick() {
    els.forEach(function (el) {
      var mins = Math.round((Date.parse(el.getAttribute("datetime")) - Date.now()) / 60000);
      var s = el.nextElementSibling && el.nextElementSibling.classList.contains("soon") ? el.nextElementSibling : null;
      if (!s) { s = document.createElement("span"); s.className = "soon"; el.after(s); }
      s.textContent = mins > 60 ? " · in " + Math.floor(mins / 60) + " h " + (mins % 60) + " min"
        : mins > 0 ? " · in " + mins + " min" : mins > -60 ? " · on now" : "";
    });
  }
  tick();
  setInterval(tick, 30000);
})();

/* Secrets: forty-one hidden stickers (Slums-chan's and Pia's) for signed-in players, each one a card in OnlySlums
   (market/cards.html). The Konami code; typing the words in the keydown handler below (both names' stickers) outside a
   text box; clicking the hero
   picture 7 times in a row, a page's Slums-chan 5 times or the footer line 3 times; waking the sleeping one on the 404
   page (3 clicks); clicking her speech bubble 5 times; trying all three colour dots within 10 seconds; a visit between
   2 and 5 AM or on a Sunday; 5 minutes without touching a page; or 5 clicks on a face-down card in My cards. Twenty-four
   more are polaroids in twelve "Dynamic duo" pairs (DUOS), handed out by the market service as the account finds every
   third secret: both of a pair always arrive together. The pictures live in secret/, which only the private build has and nginx serves only with a
   sign-in, so nothing loads until a signed-in player finds one; a signed-out visitor gets a hint to sign in instead
   (not for the late-night and idle ones, which would pop up uninvited). Found ones are kept in localStorage and saved
   to the account (the market service, /api/market/cards), merged both ways once per visit, so they follow the player
   to other devices. The first three (the original ones) unlock the Slums-chan accent, so nobody who had it loses it.
   Until then its dot shows locked ("?") and says how to get it. With it picked, the home pictures switch to the spicy
   set: every secret found plus two that only the theme shows (also cards: for the theme, and for finding them all). */
(function () {
  var SECRETS = {
    konami: { src: "peace.webp", w: 726, name: "Peace out", hint: "A classic code from the old days.",
              alt: "Slums-chan looks back over her shoulder with a peace sign" },
    slums: { src: "shoulder.webp", w: 568, name: "Over the shoulder", hint: "Say where you are.",
             alt: "Slums-chan peeks back over her shoulder with a grin" },
    clicks: { src: "look.webp", w: 650, name: "Bustin&rsquo; since 06", hint: "The home page picture wants attention.",
              alt: "Slums-chan looks back at you: “Bustin’ chat since 2006!”" },
    bustin: { src: "lean.webp", w: 801, name: "Big smile", hint: "What has she been doing to chat since 2006?",
              alt: "Slums-chan leans forward with a big smile" },
    sidekick: { src: "kneel.webp", w: 623, name: "Sidekick", hint: "The Slums-chan by a page&rsquo;s heading wants attention too.",
                alt: "Slums-chan kneels and looks back over her shoulder" },
    wake: { src: "lying.webp", w: 668, name: "Wide awake", hint: "Someone&rsquo;s asleep on a page that doesn&rsquo;t exist.",
            alt: "Slums-chan, wide awake now, lies back with her arms behind her head" },
    henesys: { src: "reach.webp", w: 744, name: "C&rsquo;mere", hint: "Name the town that&rsquo;s full again.",
               alt: "Slums-chan winks and reaches out to you" },
    owl: { src: "chin.webp", w: 606, name: "Night owl", hint: "She&rsquo;s up late. Are you?",
           alt: "Slums-chan rests her chin in her hands, a heart beside her" },
    kerning: { src: "bend.webp", w: 714, name: "Oops", hint: "Say the city where the thieves hang out.",
               alt: "Slums-chan bends over and smiles back at you" },
    colours: { src: "sitpeace.webp", w: 445, name: "Peace sit", hint: "Try on every colour, quickly.",
               alt: "Slums-chan sits with her knees up, winking with a peace sign" },
    afk: { src: "lounge.webp", w: 659, name: "AFK", hint: "Sit still for a while.",
           alt: "Slums-chan lies on her front, chin in her hand" },
    footer: { src: "glance.webp", w: 431, name: "Small print", hint: "Read the small print at the bottom. Then poke it.",
              alt: "Slums-chan glances back over her shoulder" },
    slime: { src: "hug.webp", w: 457, name: "Slime hug", hint: "Say her favourite pet.",
             alt: "Slums-chan hugs a blue slime and winks" },
    bubble: { src: "peek.webp", w: 750, name: "Caught you!", hint: "Keep poking what she says.",
              alt: "Slums-chan, caught looking back over her shoulder" },
    maple: { src: "hattip.webp", w: 770, name: "Hat tip", hint: "Name the leaf on her cap.",
             alt: "Slums-chan tips her cap and smiles back over her shoulder" },
    peace: { src: "wink.webp", w: 812, name: "Wink", hint: "Say what she&rsquo;s throwing.",
             alt: "Slums-chan winks and throws a peace sign" },
    heart: { src: "fheart.webp", w: 811, name: "Finger heart", hint: "Say what she&rsquo;s making.",
             alt: "Slums-chan winks and makes a finger heart" },
    shh: { src: "shh.webp", w: 709, name: "Shh!", hint: "Stop peeking at the cards.",
           alt: "Slums-chan looks back over her shoulder, a finger to her lips" },
    rebirth: { src: "plans.webp", w: 623, name: "Big plans", hint: "Say what you do when you&rsquo;ve done it all.",
               alt: "Slums-chan sits cross-legged, chin in her hand, smiling" },
    sunday: { src: "sunday.webp", w: 979, name: "Lazy Sunday", hint: "She takes it easy one day a week.",
              alt: "Slums-chan lies on her front with her boots in the air" },
    zzz: { src: "daydream.webp", w: 957, name: "Daydream", hint: "Make a sleepy sound.",
           alt: "Slums-chan lies with her chin in her hand, smiling sleepily" },
    summer: { src: "summer.webp", w: 885, name: "Summer!", hint: "Say her favourite season.",
              alt: "Slums-chan in a bikini, sunglasses on her cap, raising a cold drink" },
    // ticket 172 follow-up: 8 more Slums-chan stickers and 11 of Pia's. h, tw and th are the picture's own size
    // (the older cards are all 820 tall, the thumbnails 360); w and h are the full picture, tw and th the card one.
    hmm: { src: "hmm.webp", w: 349, h: 378, tw: 332, th: 360, name: "Hmm?", hint: "What she says while thinking.",
           alt: "Slums-chan tilts her head, thinking" },
    yay: { src: "yay.webp", w: 390, h: 342, tw: 390, th: 342, name: "Yay!", hint: "Cheer for her.",
           alt: "Slums-chan cheers with her arms up" },
    ghost: { src: "ghost.webp", w: 406, h: 342, tw: 406, th: 342, name: "Boo!", hint: "Say what hides under a sheet.",
             alt: "Slums-chan in a sheet, like a ghost" },
    love: { src: "love.webp", w: 357, h: 304, tw: 357, th: 304, name: "Heart hands", hint: "Say what her hands are making.",
            alt: "Slums-chan makes a heart with both hands" },
    pout: { src: "pout.webp", w: 349, h: 304, tw: 349, th: 304, name: "Pout", hint: "Say how she looks when annoyed.",
            alt: "Slums-chan pouts with her arms crossed" },
    ggwp: { src: "ggwp.webp", w: 314, h: 345, tw: 314, th: 345, name: "Good game", hint: "Say well played, the short way.",
            alt: "Slums-chan grins and gives a thumbs up" },
    glasses: { src: "glasses.webp", w: 265, h: 340, tw: 265, th: 340, name: "Bookworm",
               hint: "Name what&rsquo;s on her nose.", alt: "Slums-chan pushes her glasses up her nose" },
    stretch: { src: "stretch.webp", w: 372, h: 337, tw: 372, th: 337, name: "Big stretch",
               hint: "What you do after sitting too long.", alt: "Slums-chan stretches with her arms overhead" },
    news: { src: "news.webp", w: 338, h: 339, tw: 338, th: 339, name: "Big news", hint: "Say what the megaphone is for.",
            alt: "Pia holds a megaphone and grins" },
    idea: { src: "idea.webp", w: 319, h: 333, tw: 319, th: 333, name: "Bright idea",
            hint: "Name the light bulb&rsquo;s job.", alt: "Pia points at a light bulb above her head" },
    book: { src: "book.webp", w: 281, h: 338, tw: 281, th: 338, name: "Pia reads", hint: "Name what is in her hands.",
            alt: "Pia holds an open book" },
    meso: { src: "meso.webp", w: 334, h: 329, tw: 334, th: 329, name: "Meso bag", hint: "Name the coins in the bag.",
            alt: "Pia lifts a bag of meso coins" },
    bug: { src: "bug.webp", w: 286, h: 315, tw: 286, th: 315, name: "Bug report",
           hint: "Say what she is writing up on the clipboard.", alt: "Pia writes on a clipboard" },
    huh: { src: "huh.webp", w: 329, h: 329, tw: 329, th: 329, name: "Shrug", hint: "Say the sound of a shrug.",
           alt: "Pia shrugs with her palms up" },
    mail: { src: "mail.webp", w: 323, h: 338, tw: 323, th: 338, name: "You&rsquo;ve got mail",
            hint: "Say what is in the envelope.", alt: "Pia holds out an envelope" },
    music: { src: "music.webp", w: 309, h: 338, tw: 309, th: 338, name: "Double peace, Pia",
             hint: "Say what the note is for.", alt: "Pia throws two peace signs beside a music note" },
    gasp: { src: "gasp.webp", w: 390, h: 305, tw: 390, th: 305, name: "Oh!", hint: "Say how she looks when surprised.",
            alt: "Pia gasps with both hands at her cheeks" },
    hugs: { src: "hugs.webp", w: 425, h: 307, tw: 425, th: 307, name: "Hugs", hint: "Say what her open arms want.",
            alt: "Pia opens her arms for a hug" },
    hello: { src: "hello.webp", w: 358, h: 309, tw: 358, th: 309, name: "Hello!", hint: "Wave back.",
             alt: "Pia waves hello" }
  };
  var IDS = Object.keys(SECRETS), THEME = ["konami", "slums", "clicks"];
  // The original, cheekier set: the Slums-chan theme's pictures and the "every" reward stay with these (the 19 stickers
  // added later are cards only, so nobody who had the theme or "All of them" loses it).
  var ORIGINAL = 22;
  var THEME_ONLY = [{ src: "shy.webp", w: 582, alt: "Slums-chan kneels with a shy smile" },
                    { src: "dpeace.webp", w: 650, alt: "Slums-chan kneels and throws two peace signs" }];
  var REWARDS = {  // the market service hands these out itself (market_service.py REWARD_CARDS)
    theme: { src: "shy.webp", w: 582, name: "Shy", hint: "Unlock the Slums-chan theme.", alt: THEME_ONLY[0].alt },
    all: { src: "dpeace.webp", w: 650, name: "Double peace", hint: "Find the first fourteen secrets.",
           alt: THEME_ONLY[1].alt },
    every: { src: "wink2.webp", w: 797, name: "All of them", hint: "Find every secret.",
             alt: "Slums-chan winks with a peace sign, chin in her hand" }
  };
  // The twelve "Dynamic duo" polaroid pairs (ticket 172 follow-up): cap1..cap12 are Slums-chan, pia1..pia12 are Pia in
  // the same pose. The market service hands each pair out together once the account has found 3k secrets (see
  // REWARD_CARDS there); the browser never posts these. They are cards only: not in the theme's pictures.
  var DUOS = {
    cap1: { src: "cap1.webp", w: 555, h: 510, tw: 392, th: 360, name: "Dynamic duo 1: Slums-chan",
            hint: "Find 3 secrets.", alt: "Slums-chan reaching out to you, as a polaroid" },
    pia1: { src: "pia1.webp", w: 555, h: 515, tw: 388, th: 360, name: "Dynamic duo 1: Pia",
            hint: "Find 3 secrets.", alt: "Pia reaching out to you, as a polaroid" },
    cap2: { src: "cap2.webp", w: 385, h: 380, tw: 365, th: 360, name: "Dynamic duo 2: Slums-chan",
            hint: "Find 6 secrets.", alt: "Slums-chan with her chin on her hands, as a polaroid" },
    pia2: { src: "pia2.webp", w: 380, h: 375, tw: 365, th: 360, name: "Dynamic duo 2: Pia",
            hint: "Find 6 secrets.", alt: "Pia with her chin on her hands, as a polaroid" },
    cap3: { src: "cap3.webp", w: 320, h: 395, tw: 292, th: 360, name: "Dynamic duo 3: Slums-chan",
            hint: "Find 9 secrets.", alt: "Slums-chan holding a cup, as a polaroid" },
    pia3: { src: "pia3.webp", w: 345, h: 385, tw: 323, th: 360, name: "Dynamic duo 3: Pia",
            hint: "Find 9 secrets.", alt: "Pia holding a cup, as a polaroid" },
    cap4: { src: "cap4.webp", w: 345, h: 390, tw: 318, th: 360, name: "Dynamic duo 4: Slums-chan",
            hint: "Find 12 secrets.", alt: "Slums-chan looking back over her shoulder with a maple leaf, as a polaroid" },
    pia4: { src: "pia4.webp", w: 361, h: 390, tw: 333, th: 360, name: "Dynamic duo 4: Pia",
            hint: "Find 12 secrets.", alt: "Pia looking back over her shoulder with a maple leaf, as a polaroid" },
    cap5: { src: "cap5.webp", w: 345, h: 340, tw: 345, th: 340, name: "Dynamic duo 5: Slums-chan",
            hint: "Find 15 secrets.", alt: "Slums-chan hugging a cat plush, as a polaroid" },
    pia5: { src: "pia5.webp", w: 350, h: 345, tw: 350, th: 345, name: "Dynamic duo 5: Pia",
            hint: "Find 15 secrets.", alt: "Pia hugging a cat plush, as a polaroid" },
    cap6: { src: "cap6.webp", w: 310, h: 315, tw: 310, th: 315, name: "Dynamic duo 6: Slums-chan",
            hint: "Find 18 secrets.", alt: "Slums-chan tipping her glasses, as a polaroid" },
    pia6: { src: "pia6.webp", w: 315, h: 325, tw: 315, th: 325, name: "Dynamic duo 6: Pia",
            hint: "Find 18 secrets.", alt: "Pia tipping her glasses, as a polaroid" },
    cap7: { src: "cap7.webp", w: 360, h: 345, tw: 360, th: 345, name: "Dynamic duo 7: Slums-chan",
            hint: "Find 21 secrets.", alt: "Slums-chan throwing a peace sign, as a polaroid" },
    pia7: { src: "pia7.webp", w: 366, h: 340, tw: 366, th: 340, name: "Dynamic duo 7: Pia",
            hint: "Find 21 secrets.", alt: "Pia throwing a peace sign, as a polaroid" },
    cap8: { src: "cap8.webp", w: 555, h: 235, tw: 555, th: 235, name: "Dynamic duo 8: Slums-chan",
            hint: "Find 24 secrets.", alt: "Slums-chan sleeping with Zzz, as a polaroid" },
    pia8: { src: "pia8.webp", w: 560, h: 235, tw: 560, th: 235, name: "Dynamic duo 8: Pia",
            hint: "Find 24 secrets.", alt: "Pia sleeping with Zzz, as a polaroid" },
    cap9: { src: "cap9.webp", w: 338, h: 285, tw: 338, th: 285, name: "Dynamic duo 9: Slums-chan",
            hint: "Find 27 secrets.", alt: "Slums-chan holding a maple leaf with a heart bubble, as a polaroid" },
    pia9: { src: "pia9.webp", w: 333, h: 295, tw: 333, th: 295, name: "Dynamic duo 9: Pia",
            hint: "Find 27 secrets.", alt: "Pia holding a maple leaf with a heart bubble, as a polaroid" },
    cap10: { src: "cap10.webp", w: 315, h: 305, tw: 315, th: 305, name: "Dynamic duo 10: Slums-chan",
             hint: "Find 30 secrets.", alt: "Slums-chan sipping bubble tea, as a polaroid" },
    pia10: { src: "pia10.webp", w: 320, h: 290, tw: 320, th: 290, name: "Dynamic duo 10: Pia",
             hint: "Find 30 secrets.", alt: "Pia sipping bubble tea, as a polaroid" },
    cap11: { src: "cap11.webp", w: 420, h: 299, tw: 420, th: 299, name: "Dynamic duo 11: Slums-chan",
             hint: "Find 33 secrets.", alt: "Slums-chan hugging a leaf pillow, as a polaroid" },
    pia11: { src: "pia11.webp", w: 420, h: 305, tw: 420, th: 305, name: "Dynamic duo 11: Pia",
             hint: "Find 33 secrets.", alt: "Pia hugging a leaf pillow, as a polaroid" },
    cap12: { src: "cap12.webp", w: 452, h: 305, tw: 452, th: 305, name: "Dynamic duo 12: Slums-chan",
             hint: "Find 36 secrets.", alt: "Slums-chan hiding in a checked blanket, as a polaroid" },
    pia12: { src: "pia12.webp", w: 457, h: 320, tw: 457, th: 320, name: "Dynamic duo 12: Pia",
             hint: "Find 36 secrets.", alt: "Pia hiding in a checked blanket, as a polaroid" }
  };
  var root = document.documentElement;
  var me = document.querySelector('script[src$="assets/theme.js"]');
  var base = me ? me.getAttribute("src").replace(/assets\/theme\.js$/, "") : "";
  var box = null, signedIn = null;

  // Picture sizes: the cards before ticket 172's follow-up are all 820 tall with 360-tall thumbnails, so those two
  // numbers are the fallback; newer cards carry their own h (full) and tw/th (thumbnail) from the art's manifest.
  function tall(s) { return s.h || 820; }
  function wide(s) { return s.tw || Math.round(s.w * 360 / tall(s)); }
  function thumb(s) { return s.th || 360; }

  function found() {
    try { return JSON.parse(localStorage.getItem("slums-secrets") || "[]"); } catch (err) { return []; }
  }
  function keep(got) { try { localStorage.setItem("slums-secrets", JSON.stringify(got)); } catch (err) {} }
  function unlocked() { var got = found(); return THEME.every(function (id) { return got.indexOf(id) >= 0; }); }
  var api = base + "api/market/cards";
  function save(ids) {  // to the account; a failure is fine, the next visit's merge sends them again
    return fetch(api, { method: "POST", credentials: "same-origin", body: JSON.stringify({ ids: ids }),
                        headers: { "Content-Type": "application/json", "X-Slums": "1" } })
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
  }
  // once per visit (tab), signed in: the account's cards and this browser's are merged both ways
  function merge() {
    return fetch(api, { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        if (!j || !j.found) return null;
        var got = found(), mine = Object.keys(j.found).filter(function (id) { return SECRETS[id]; });
        var missing = got.filter(function (id) { return SECRETS[id] && mine.indexOf(id) < 0; });
        keep(got.concat(mine.filter(function (id) { return got.indexOf(id) < 0; })));
        if (unlocked()) showUnlock();
        return missing.length ? save(missing) : j;
      }).catch(function () { return null; });
  }
  function whoami() {  // asked once per page; no login service (or no network) counts as signed out
    if (!signedIn) signedIn = fetch(base + "api/login/me", { credentials: "same-origin" })
      .then(function (r) { return r.ok ? r.json() : {}; }).then(function (j) { return !!j.signed_in; })
      .catch(function () { return false; });
    return signedIn;
  }
  function close() { if (box) { box.remove(); box = null; } }
  function card(html) {
    close();
    box = document.createElement("div");
    box.className = "secret";
    box.setAttribute("role", "dialog");
    box.setAttribute("aria-label", "A secret");
    box.innerHTML = '<button type="button" class="secret-x" aria-label="Close">&times;</button>' + html;
    box.querySelector(".secret-x").addEventListener("click", close);
    document.body.appendChild(box);
    box.querySelector(".secret-x").focus();
    return box;
  }
  var dot = document.querySelector('[data-accent-btn="slumschan"]');
  function showUnlock() {
    if (!dot) return;
    dot.hidden = false;
    dot.classList.remove("locked");
    dot.setAttribute("title", "Slums-chan");
    dot.setAttribute("aria-label", "Slums-chan");
  }
  // locked: the dot is there with a "?", so players know there's something to find; clicking it explains
  if (dot && !unlocked()) {
    dot.hidden = false;
    dot.classList.add("locked");
    dot.setAttribute("title", "A secret theme (locked)");
    dot.setAttribute("aria-label", "A secret theme (locked)");
    dot.addEventListener("click", function (e) {
      if (unlocked()) return;
      e.stopPropagation();  // don't pick it yet
      var n = found().filter(function (g) { return IDS.indexOf(g) >= 0; }).length;
      card('<p class="secret-note"><b>There&rsquo;s a secret theme here.</b> Slums-chan has hidden ' + IDS.length +
        ' secrets around the site. Find three to unlock her theme. You&rsquo;ve found ' + n + " of " + IDS.length +
        '. <span class="muted">Hint: try a classic code, and say where you are.</span> Signed-in players only: ' +
        'see them in <a href="' + base + 'market/cards">OnlySlums</a>.</p>');
    });
  }
  function reveal(id, quiet) {  // quiet: no sign-in hint, and nothing if it's already found
    if (quiet && found().indexOf(id) >= 0) return;
    whoami().then(function (ok) {
      if (!ok && quiet) return;
      if (!ok) {
        var next = encodeURIComponent(location.pathname);
        card('<p class="secret-note"><b>Something&rsquo;s hiding here.</b> <a href="' + base + "login/?next=" + next +
          '">Sign in</a> with your game account to see it.</p>');
        return;
      }
      var s = SECRETS[id], got = found(), was = unlocked();
      if (got.indexOf(id) < 0) got.push(id);
      keep(got);
      save([id]);
      var left = IDS.length - got.filter(function (g) { return IDS.indexOf(g) >= 0; }).length;
      var note = left > 0 ? left + (left === 1 ? " more is" : " more are") + " hiding somewhere."
        : "That&rsquo;s all of them. Nice.";
      if (!was && unlocked()) note += " <b>The Slums-chan theme is unlocked:</b> pick it next to the colour dots up top.";
      var c = card('<img src="' + base + "secret/" + s.src + '" alt="" width="' + s.w + '" height="' + tall(s) + '">' +
        '<p class="secret-note"><b>You found a card: ' + s.name + (/[!?.]$/.test(s.name) ? "" : ".") + "</b> " + note +
        ' <a href="' + base + 'market/cards">OnlySlums</a></p>');
      c.querySelector("img").alt = s.alt;
      if (unlocked()) showUnlock();
    });
  }

  if (unlocked()) showUnlock();

  var KONAMI = ["ArrowUp", "ArrowUp", "ArrowDown", "ArrowDown", "ArrowLeft", "ArrowRight", "ArrowLeft", "ArrowRight", "b", "a"];
  var keys = [], typed = "";
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") { close(); return; }
    var t = e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
    var k = e.key.length === 1 ? e.key.toLowerCase() : e.key;
    keys = keys.concat(k).slice(-KONAMI.length);
    if (keys.join() === KONAMI.join()) { keys = []; reveal("konami"); return; }
    typed = (typed + (k.length === 1 ? k : " ")).slice(-8);
    ["slums", "bustin", "henesys", "kerning", "slime", "maple", "peace", "heart", "rebirth", "zzz", "summer",
     "hmm", "yay", "ghost", "love", "pout", "ggwp", "glasses", "stretch",
     "news", "idea", "book", "meso", "bug", "huh", "mail", "music", "gasp", "hugs", "hello"].forEach(function (w) {
      if (typed.slice(-w.length) === w) { typed = ""; reveal(w); }
    });
  });

  // a page's Slums-chan (the picture by the heading), clicked 5 times in a row
  function clicks(el, need, id) {
    var count = 0, at = 0;
    el.addEventListener("click", function () {
      var t = Date.now();
      count = t - at < 800 ? count + 1 : 1;
      at = t;
      if (count >= need) { count = 0; reveal(id); }
    });
  }
  document.querySelectorAll("img.sidekick").forEach(function (el) { clicks(el, 5, "sidekick"); });
  var sleeper = document.querySelector(".notfound img");
  if (sleeper) clicks(sleeper, 3, "wake");
  var small = document.querySelector(".site-foot span");
  if (small) clicks(small, 3, "footer");
  // her speech bubble (sections/buddy.py), clicked 5 times in a row: she turns around
  document.querySelectorAll(".bubble").forEach(function (el) { clicks(el, 5, "bubble"); });

  // all three colour dots tried within 10 seconds
  var tried = {};
  document.querySelectorAll("[data-accent-btn]").forEach(function (b) {
    var k = b.getAttribute("data-accent-btn");
    if (["green", "blue", "orange"].indexOf(k) < 0) return;
    b.addEventListener("click", function () {
      var t = Date.now();
      tried[k] = t;
      if (["green", "blue", "orange"].every(function (c) { return t - (tried[c] || 0) < 10000; })) {
        tried = {}; reveal("colours");
      }
    });
  });
  // late at night (the visitor's own clock), and 5 minutes on a page without touching it
  var h = new Date().getHours();
  if (h >= 2 && h < 5) setTimeout(function () { reveal("owl", true); }, 4000);
  if (new Date().getDay() === 0) setTimeout(function () { reveal("sunday", true); }, 4000);
  var idle;
  function still() {
    clearTimeout(idle);
    idle = setTimeout(function () { if (!document.hidden) reveal("afk", true); }, 300000);
  }
  ["keydown", "pointerdown", "pointermove", "scroll", "wheel", "touchstart"].forEach(function (e) {
    window.addEventListener(e, still, { passive: true });
  });
  still();

  // signed in (a build with the sign-in button): merge this browser's cards with the account's, once per visit
  var synced = false;
  try { synced = sessionStorage.getItem("slums-cards") === "1"; } catch (err) {}
  var grid = document.getElementById("cards-grid");
  if (document.querySelector("[data-signin]") && (!synced || grid)) merge().then(function (j) {
    if (j) try { sessionStorage.setItem("slums-cards", "1"); } catch (err) {}
    if (grid) cards(grid, j);
  });
  // My cards: a face-down card clicked 5 times in a row (the grid is redrawn, so listen on the grid itself)
  if (grid) {
    var peek = 0, peekAt = 0;
    grid.addEventListener("click", function (e) {
      if (!e.target.closest || !e.target.closest(".card.back")) return;
      var t = Date.now();
      peek = t - peekAt < 800 ? peek + 1 : 1;
      peekAt = t;
      if (peek >= 5) { peek = 0; reveal("shh"); }
    });
  }

  // My cards (market/cards.html): found ones face up with the date, the rest face down with a hint. The order is the
  // original 25 (22 secrets then the three rewards), then the 19 stickers added later, then the twelve duo pairs with
  // each pair's two polaroids next to each other.
  function cards(ul, j) {
    var all = IDS.slice(0, ORIGINAL).map(function (id) { return [id, SECRETS[id]]; })
      .concat(Object.keys(REWARDS).map(function (id) { return [id, REWARDS[id]]; }),
              IDS.slice(ORIGINAL).map(function (id) { return [id, SECRETS[id]]; }),
              Object.keys(DUOS).map(function (id) { return [id, DUOS[id]]; }));
    var at = (j && j.found) || {};
    if (!j) found().forEach(function (id) { at[id] = ""; });  // no service: this browser's list
    var n = all.filter(function (x) { return x[0] in at; }).length;
    var count = document.getElementById("cards-n");
    if (count) count.textContent = n + " of " + all.length + " found" + (n === all.length ? ". That\u2019s all of them." : ".");
    ul.textContent = "";
    all.forEach(function (x, i) {
      var id = x[0], s = x[1], li = document.createElement("li"), got = id in at;
      li.className = "card" + (got ? "" : " back");
      var num = '<span class="card-n">#' + (i + 1) + "</span>";
      if (got) {
        var day = at[id] ? new Date(at[id]).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) : "";
        li.innerHTML = '<a href="' + base + "secret/" + s.src + '"><img src="' + base + "secret/cards/" + s.src +
          '" alt="" width="' + wide(s) + '" height="' + thumb(s) + '" loading="lazy"></a>' + num +
          "<b>" + s.name + "</b>" + (day ? '<span class="card-d">Found ' + day + "</span>" : "");
        li.querySelector("img").alt = s.alt;
      } else {
        li.innerHTML = '<span class="card-q" aria-hidden="true">?</span>' + num + "<b>Not found yet</b>" +
          '<span class="card-d">' + s.hint + "</span>";
      }
      ul.appendChild(li);
    });
    if (!j && document.getElementById("cards-off")) document.getElementById("cards-off").hidden = false;
  }

  // The Slums-chan theme, signed in: every other Slums-chan picture (beside a page's heading, the sleeping one on the
  // 404 page, the empty lists) becomes one of the player's cards. Which ones depends on the page, so it varies.
  if (root.getAttribute("data-accent") === "slumschan" && unlocked()) whoami().then(function (ok) {
    if (!ok) return;
    var got = found();
    var set = IDS.slice(0, ORIGINAL).filter(function (id) { return got.indexOf(id) >= 0; }).map(function (id) { return SECRETS[id]; })
      .concat(REWARDS.theme, IDS.slice(0, 14).every(function (id) { return got.indexOf(id) >= 0; }) ? [REWARDS.all] : [],
              IDS.slice(0, ORIGINAL).every(function (id) { return got.indexOf(id) >= 0; }) ? [REWARDS.every] : []);
    var start = 0;
    for (var i = 0; i < location.pathname.length; i++) start = (start * 31 + location.pathname.charCodeAt(i)) % 9973;
    document.querySelectorAll("img.sidekick, .notfound img, .empty.nap img").forEach(function (img, n) {
      var s = set[(start + n) % set.length];
      img.removeAttribute("srcset");
      img.width = wide(s); img.height = thumb(s);
      img.src = base + "secret/cards/" + s.src;
      img.alt = s.alt;
    });
  });

  var rotor = document.querySelector("[data-rotor]");
  if (!rotor) return;
  clicks(rotor, 7, "clicks");
  // The Slums-chan theme: the home pictures switch to the spicy set (signed in only, since the files need the
  // sign-in). The picture on show becomes the first of the set and the others are replaced, so the rotation (which
  // walks rotor.children) carries on; if it has already moved on, the set is added after the normal pictures.
  if (root.getAttribute("data-accent") === "slumschan" && unlocked()) whoami().then(function (ok) {
    if (!ok) return;
    var got = found();
    var set = IDS.slice(0, ORIGINAL).filter(function (id) { return got.indexOf(id) >= 0; }).map(function (id) { return SECRETS[id]; })
      .concat(THEME_ONLY);
    function pic(s, img) {
      img = img || new Image();
      img.removeAttribute("srcset");
      img.width = s.w; img.height = tall(s);
      img.className = "mascot secret-pic" + (img.classList.contains("is-on") ? " is-on" : "");
      img.src = base + "secret/" + s.src;
      img.alt = s.alt;
      return img;
    }
    var first = rotor.children[0];
    if (first && first.classList.contains("is-on")) {
      while (rotor.children.length > 1) rotor.removeChild(rotor.lastElementChild);
      pic(set[0], first).removeAttribute("loading");
      set = set.slice(1);
    }
    set.forEach(function (s) {
      var img = pic(s);
      img.loading = "lazy";
      img.setAttribute("aria-hidden", "true");
      rotor.appendChild(img);
    });
  });
})();

/* Holidays: on the day (the visitor's own clock), the Slums-chan beside each page's heading puts on that holiday's
   sticker and says so first in her bubble. The Slums-chan theme, when it's on and signed in, still swaps in the player's
   cards. The first rule that matches wins, so a single day beats a longer stretch around it. */
(function () {
  var figs = document.querySelectorAll(".buddy");
  if (!figs.length) return;
  var me = document.querySelector('script[src$="assets/theme.js"]');
  var base = me ? me.getAttribute("src").replace(/assets\/theme\.js$/, "") : "";
  var now = new Date(), y = now.getFullYear(), m = now.getMonth() + 1, d = now.getDate();
  function nth(month, weekday, n) {  // the nth weekday (0 = Sunday) of a month
    var first = new Date(y, month - 1, 1).getDay();
    return 1 + (weekday - first + 7) % 7 + 7 * (n - 1);
  }
  function easter() {  // Gregorian (anonymous algorithm): [month, day]
    var a = y % 19, b = Math.floor(y / 100), c = y % 100, e = Math.floor(b / 4), f = b % 4, g = Math.floor((b + 8) / 25),
      h = (19 * a + b - e - Math.floor((b - g + 1) / 3) + 15) % 30, i = Math.floor(c / 4), k = c % 4,
      l = (32 + 2 * f + 2 * i - h - k) % 7, n = Math.floor((a + 11 * h + 22 * l) / 451);
    return [Math.floor((h + l - 7 * n + 114) / 31), (h + l - 7 * n + 114) % 31 + 1];
  }
  var LUNAR = { 2027: [2, 6], 2028: [1, 26], 2029: [2, 13], 2030: [2, 3] };   // Lunar New Year
  var MOON = { 2026: [9, 25], 2027: [9, 15], 2028: [10, 3], 2029: [9, 22], 2030: [9, 12] };   // Moon Festival
  function on(md) { return md && md[0] === m && md[1] === d; }
  function between(m1, d1, m2, d2) { var t = m * 100 + d; return t >= m1 * 100 + d1 && t <= m2 * 100 + d2; }
  var E = easter();
  var DAYS = [
    ["aprilfools", on([4, 1]), "Nothing I say today is true. Probably.", "Slums-chan with a fake moustache for April Fools&rsquo;"],
    ["valentine", on([2, 14]), "Happy Valentine&rsquo;s Day!", "Slums-chan hugs a heart-shaped box for Valentine&rsquo;s Day"],
    ["whiteday", on([3, 14]), "Happy White Day!", "Slums-chan holds a blue heart box for White Day"],
    ["stpatricks", on([3, 17]), "Happy St. Patrick&rsquo;s Day!", "Slums-chan in a green hat for St. Patrick&rsquo;s Day"],
    ["setsubun", on([2, 3]), "Happy Setsubun! Beans out, luck in.", "Slums-chan with an oni mask and beans for Setsubun"],
    ["lunarnewyear", on(LUNAR[y]), "Happy Lunar New Year!", "Slums-chan holds a red envelope for Lunar New Year"],
    ["easter", on(E), "Happy Easter!", "Slums-chan in bunny ears with a toy bunny for Easter"],
    ["earthday", on([4, 22]), "Happy Earth Day!", "Slums-chan hugs a little globe for Earth Day"],
    ["mothersday", m === 5 && d === nth(5, 0, 2), "Happy Mother&rsquo;s Day!", "Slums-chan holds carnations for Mother&rsquo;s Day"],
    ["fathersday", m === 6 && d === nth(6, 0, 3), "Happy Father&rsquo;s Day!", "Slums-chan holds a wrench for Father&rsquo;s Day"],
    ["summer", on([6, 21]), "First day of summer!", "Slums-chan with sunglasses and a watermelon ice pop"],
    ["july4", on([7, 4]), "Happy Fourth of July!", "Slums-chan with fireworks for the Fourth of July"],
    ["tanabata", on([7, 7]), "Happy Tanabata! Make a wish.", "Slums-chan with a bamboo branch of wishes for Tanabata"],
    ["obon", between(8, 13, 8, 15), "Happy Obon!", "Slums-chan holds a paper lantern for Obon"],
    ["moonfestival", on(MOON[y]), "Happy Moon Festival!", "Slums-chan with a mooncake and a moon bunny"],
    ["halloween", between(10, 24, 10, 31), "Happy Halloween! Trick or treat?", "Slums-chan in a witch hat with a jack-o&rsquo;-lantern"],
    ["thanksgiving", m === 11 && d === nth(11, 4, 4), "Happy Thanksgiving!", "Slums-chan serves a roast turkey for Thanksgiving"],
    ["winter", on([12, 21]), "First day of winter. Bundle up!", "Slums-chan wrapped in a scarf for winter"],
    ["christmas", between(12, 24, 12, 25), "Merry Christmas!", "Slums-chan in a Santa hat with a present"],
    ["newyear", between(1, 1, 1, 3) || on([12, 31]), "Happy New Year!", "Slums-chan holds up a 2027 sign for New Year"],
    ["school", between(9, 1, 9, 7), "Back to school! Or back to grinding.", "Slums-chan with a stack of books for back to school"],
    ["oktoberfest", between(9, 26, 10, 3), "Prost! Happy Oktoberfest!", "Slums-chan raises a beer stein for Oktoberfest"],
    ["hanami", between(3, 25, 4, 10), "Cherry blossom season!", "Slums-chan among cherry blossoms for hanami"]
  ];
  var day = null;
  for (var i = 0; i < DAYS.length; i++) if (DAYS[i][1]) { day = DAYS[i]; break; }
  if (!day) return;
  var tmp = document.createElement("span");
  function text(html) { tmp.innerHTML = html; return tmp.textContent; }
  figs.forEach(function (f) {
    var img = f.querySelector("img.sidekick"), b = f.querySelector(".bubble");
    if (img) {
      img.removeAttribute("srcset");
      img.src = base + "assets/brand/holiday-" + day[0] + "-360.webp";
      img.width = 360; img.removeAttribute("height"); img.style.height = "auto";
      img.alt = text(day[3]);
      img.classList.add("holiday");
    }
    if (b) {
      var lines = [];
      try { lines = JSON.parse(b.getAttribute("data-lines")) || []; } catch (err) {}
      b.setAttribute("data-lines", JSON.stringify([text(day[2])].concat(lines)));
      b.textContent = text(day[2]);
    }
  });
})();

/* Slums-chan's speech bubbles (sections/buddy.py) circle through their lines: the page's own, then a few for every
   page. A fade, unless the visitor asked for less motion; nothing while the tab is hidden. The lines are read again
   every time, so a page can change them (exchange.js, once the exchange is open). */
(function () {
  function read(b) {
    try { var l = JSON.parse(b.getAttribute("data-lines")); return Array.isArray(l) ? l : []; } catch (err) { return []; }
  }
  document.querySelectorAll(".bubble[data-lines]").forEach(function (b) {
    if (read(b).length < 2) return;
    var i = 0, calm = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
    b.setAttribute("aria-live", "off");
    setInterval(function () {
      if (document.hidden) return;
      var lines = read(b);
      if (!lines.length) return;
      i = (i + 1) % lines.length;
      if (calm) { b.textContent = lines[i]; return; }
      b.classList.add("swap");
      setTimeout(function () { b.textContent = lines[i]; b.classList.remove("swap"); }, 250);
    }, 7000);
  });
})();

/* Free hints: 30 clicks in a row on a page's Slums-chan (img.sidekick), or on Pia (any .sidekick with
   data-who="pia"), each click within 3 seconds of the last, ask the market service for a hint about a card the player
   hasn't found yet: first a clue, then the page to try, then the word with some letters hidden. The answers live only
   in the service (market_service.py HINTS), never in this file, and never in the page source. A signed-out visitor
   (a 401, or no answer at all) sees nothing and gets no error. The bubble's own 5-click secret and the figure's
   5-click counter above are separate counts and stay as they are. */
(function () {
  var me = document.querySelector('script[src$="assets/theme.js"]');
  var base = me ? me.getAttribute("src").replace(/assets\/theme\.js$/, "") : "";
  var NEED = 30, GAP = 3000, HOLD = 8000;
  function ask(el, who) {
    var count = 0, at = 0, busy = false;
    el.addEventListener("click", function () {
      var t = Date.now();
      count = t - at <= GAP ? count + 1 : 1;   // a longer pause than GAP starts the count again
      at = t;
      if (count < NEED) return;
      count = 0;                               // those 30 clicks are spent, whether a request goes out or not
      if (busy) return;
      busy = true;
      fetch(base + "api/market/hint", { method: "POST", credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: JSON.stringify({ who: who }) })
        .then(function (r) {                   // signed out (401) and errors: no line, no noise
          if (r.status === 401) return null;
          return r.json().then(function (j) {
            return r.ok ? j : (r.status === 429 && j.error ? { line: j.error } : null);
          });
        })
        .then(function (j) {
          if (!j || !j.line) return;
          var fig = el.closest(".buddy"), b = fig && fig.querySelector(".bubble");
          if (!b) return;
          var was = b.textContent;
          b.textContent = j.line;
          setTimeout(function () { if (b.textContent === j.line) b.textContent = was; }, HOLD);
        })
        .catch(function () {})
        .then(function () { busy = false; });
    });
  }
  document.querySelectorAll('img.sidekick, .sidekick[data-who="pia"]').forEach(function (el) {
    ask(el, el.getAttribute("data-who") === "pia" ? "pia" : "cap");
  });
})();

/* Sign in: once the login service says who's signed in, the header button becomes "Signed in as X · Sign out"
   (character, or the account for a new one). Builds without sign-in have no button; without the service it stays. */
(function () {
  var a = document.querySelector("[data-signin]");
  if (!a || !window.fetch) return;
  var api = a.getAttribute("href") + "../api/login/";
  fetch(api + "me", { credentials: "same-origin" }).then(function (r) { return r.json(); }).then(function (j) {
    if (!j.signed_in) return;
    var who = document.createElement("span"), name = document.createElement("b"), out = document.createElement("button");
    who.className = "who";
    name.textContent = j.character || j.account || "a player";
    out.type = "button"; out.className = "linkbtn"; out.textContent = "Sign out";
    var line = document.createElement("span"), sep = document.createElement("span");
    sep.className = "sep"; sep.textContent = " · ";  // shown as one line where there's room, two in the header
    line.append("Signed in as ", name);
    who.append(line, sep, out);
    if (j.admin) {  // GM level 3+ (the login service decides; nginx checks again at /admin/)
      var adm = document.createElement("a"), sep2 = document.createElement("span");
      adm.href = a.getAttribute("href") + "../admin/"; adm.className = "linkbtn"; adm.textContent = "Admin";
      sep2.className = "sep"; sep2.textContent = " · ";
      who.append(sep2, adm);
    }
    out.style.justifySelf = "start";
    out.addEventListener("click", function () {
      fetch(api + "logout", { method: "POST", credentials: "same-origin" }).then(function () { location.reload(); });
    });
    a.replaceWith(who);
    bell(who);
  }).catch(function () {});

  /* The bell: fired price alerts the player hasn't seen (the "badge" the launcher shows) plus new offers on their
     Buy/Sell posts (offers_new, from the outbox's offers.json), newest first in a small card. Opening it marks them read. */
  function bell(after) {
    var mk = a.getAttribute("href") + "../api/market/";
    fetch(mk + "me", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (me) {
      if (!me) return;
      var btn = document.createElement("button"), n = document.createElement("span");
      btn.type = "button"; btn.className = "bell";
      btn.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" ' +
        'stroke-width="2" stroke-linecap="round"><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 20a2 2 0 0 0 4 0"/></svg>';
      n.className = "bell-n";
      function count(k) {
        n.textContent = k > 9 ? "9+" : String(k); n.hidden = !k;
        btn.setAttribute("aria-label", k ? k + " new notification" + (k === 1 ? "" : "s") : "Notifications");
        btn.title = btn.getAttribute("aria-label");
      }
      btn.appendChild(n);
      var unread = (me.badge || 0) + (me.offers_new || 0);
      count(unread);
      var card = null;
      btn.addEventListener("click", function (e) {
        e.stopPropagation();
        if (card) { card.remove(); card = null; return; }
        card = document.createElement("div");
        card.className = "bell-card";
        var fired = (me.alerts || []).filter(function (x) { return x.firedAt; }).map(function (x) {
          return { t: x.firedAt, isNew: x.new, text: x.name + (x.dir === "below" ? " dropped to " : " rose to ") +
            Number(x.firedPrice || x.price).toLocaleString("en-US") + " mesos" }; })
          .concat((me.offers || []).map(function (o) {
            return { t: o.t, isNew: o.new, text: o.from + " offered " + Number(o.price).toLocaleString("en-US") +
              " mesos on your " + (o.kind === "WTB" ? "WTB" : "WTS") + " " + o.name }; }))
          .sort(function (x, y) { return String(y.t).localeCompare(String(x.t)); }).slice(0, 8);
        var mine = a.getAttribute("href") + "../market/mine";
        card.innerHTML = "<b>Notifications</b>" + (fired.length ? "" : '<p class="muted">Nothing yet. Price alerts going off and offers on your Buy/Sell posts show up here.</p>');
        var ul = document.createElement("ul");
        fired.forEach(function (x) {
          var li = document.createElement("li"), link = document.createElement("a");
          link.href = mine;
          link.textContent = x.text;
          if (x.isNew) li.className = "new";
          li.appendChild(link);
          ul.appendChild(li);
        });
        if (fired.length) card.appendChild(ul);
        var all = document.createElement("a"); all.href = mine; all.className = "small"; all.textContent = "My market \u00bb";
        card.appendChild(all);
        btn.after(card);
        if (unread) {
          fetch(mk + "alerts/seen", { method: "POST", credentials: "same-origin",
            headers: { "Content-Type": "application/json", "X-Slums": "1" }, body: "{}" })
            .then(function (r) { if (r.ok) { me.badge = 0; me.offers_new = 0; unread = 0; count(0); } }).catch(function () {});
        }
      });
      document.addEventListener("click", function (e) { if (card && !card.contains(e.target)) { card.remove(); card = null; } });
      document.addEventListener("keydown", function (e) { if (e.key === "Escape" && card) { card.remove(); card = null; } });
      after.before(btn);
    }).catch(function () {});
  }
})();

/* The home page's "Bots at peak" becomes "Online now" when World's online.json is there (it's rebuilt every couple
   of minutes). Without it, or in a build without World, the line stays as written. */
(function () {
  var box = document.querySelector("[data-online]");
  if (!box || !window.fetch) return;
  fetch("world/online.json", { cache: "no-store" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
    if (!j || typeof j.online !== "number") return;
    box.querySelector("dt").textContent = "Online now";
    box.querySelector("dd").textContent = j.online.toLocaleString("en-US");
  }).catch(function () {});
})();

/* Charts (sections/market.py area_chart): hovering a day shows a guide line and that day's numbers. Each day's
   <title> is the fallback without script. */
(function () {
  document.querySelectorAll(".chart-box").forEach(function (box) {
    var svg = box.querySelector("svg"), guide = box.querySelector(".ch-guide"), tip = box.querySelector(".ch-tip");
    if (!svg || !guide || !tip) return;
    box.querySelectorAll(".ch-hit title").forEach(function (t) { t.remove(); });  // the script tip replaces them
    svg.addEventListener("pointerover", function (e) {
      var hit = e.target.closest(".ch-hit");
      if (!hit) return;
      var x = hit.getAttribute("data-x");
      guide.setAttribute("x1", x); guide.setAttribute("x2", x); guide.hidden = false;
      tip.textContent = hit.getAttribute("data-tip");
      var vb = svg.viewBox.baseVal, scale = svg.getBoundingClientRect().width / vb.width;
      var left = Math.min(Math.max(x * scale, tip.offsetWidth / 2 || 60), box.clientWidth - (tip.offsetWidth / 2 || 60));
      tip.style.left = left + "px"; tip.hidden = false;
    });
    svg.addEventListener("pointerleave", function () { guide.hidden = true; tip.hidden = true; });
  });
})();

/* Slums Street's "Hot right now": the busiest maps, the most-sold items this week and the most-hunted monsters, from
   world/hot.json (World rebuilds it every couple of minutes). Without the file the panel stays hidden. */
(function () {
  var box = document.querySelector("[data-hot]");
  if (!box || !window.fetch) return;
  var root = box.getAttribute("data-root") || "";
  fetch(box.getAttribute("data-hot"), { cache: "no-store" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
    if (!j) return;
    var groups = [["maps", "Busiest maps", "here now", "street/maps/"], ["mobs", "Most hunted (" + (j.mobs_window || "24 h") + ")", "kills (est.)", "street/mobs/"],
                  ["items", "Most sold (7 days)", "sold", "market/prices/"]];
    var grid = box.querySelector(".hotgrid"), any = false;
    groups.forEach(function (g) {
      var rows = (j[g[0]] || []).filter(function (r) { return r && r.name; });
      if (!rows.length) return;
      any = true;
      var col = document.createElement("div"), h = document.createElement("h3"), ol = document.createElement("ol");
      h.textContent = g[1];
      rows.forEach(function (r) {
        var li = document.createElement("li"), label = document.createElement(g[0] === "items" || r.page ? "a" : "span");
        label.textContent = r.name;
        if (label.tagName === "A") label.href = root + g[3] + r.id;
        var n = document.createElement("small");
        n.textContent = " " + Number(r.count || 0).toLocaleString("en-US") + " " + g[2];
        li.append(label, n);
        ol.appendChild(li);
      });
      col.append(h, ol);
      grid.appendChild(col);
    });
    if (any) box.hidden = false;
  }).catch(function () {});
})();

/* Price charts with 7 / 14 / 30 day buttons (sections/market.py ranged_chart): the buttons show when script runs. */
(function () {
  document.querySelectorAll(".ranged").forEach(function (box) {
    var chips = box.querySelector(".range-chips");
    if (!chips) return;
    chips.hidden = false;
    chips.addEventListener("click", function (e) {
      var b = e.target.closest("[data-range]");
      if (!b) return;
      var n = b.getAttribute("data-range");
      chips.querySelectorAll("[data-range]").forEach(function (x) { x.setAttribute("aria-pressed", String(x === b)); });
      box.querySelectorAll(".range-view").forEach(function (v) { v.hidden = v.getAttribute("data-view") !== n; });
    });
  });
})();

/* The Slums Exchange line on Slums Street and Price check item pages (1.6.4, ticket 270): the page has a hidden
   <p data-xc-item> for any scroll, use or etc item; the market service says which ticker trades it (or none), and the
   line shows Buy and Sell links to that ticker on the exchange page (#s2040805-sell opens the form on Sell). */
(function () {
  var el = document.querySelector("[data-xc-item]");
  if (!el || !window.fetch) return;
  var root = el.getAttribute("data-root") || "";
  fetch(root + "api/market/exchange/item?id=" + encodeURIComponent(el.getAttribute("data-xc-item")),
        { credentials: "same-origin", cache: "no-store" })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      if (!j || !j.ticker) return;
      var at = root + "market/exchange.html#" + j.ticker.toLowerCase();
      el.textContent = "On the Slums Exchange: ";
      [["Buy", "-buy", "buy"], ["Sell", "-sell", "sell"]].forEach(function (b, i) {
        if (i) el.appendChild(document.createTextNode(" \u00b7 "));
        var a = document.createElement("a");
        a.href = at + b[1]; a.className = "xc-onex-" + b[2]; a.textContent = b[0];
        el.appendChild(a);
      });
      el.hidden = false;
    })
    .catch(function () { /* no exchange line */ });
})();
