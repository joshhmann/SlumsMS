#!/usr/bin/env python3
"""Website QA against the TEST copy of the site (http://192.168.0.164:8192) and the TEST server's real exports.

    tools/site/test164/qa.sh [--quick] [--account ID --character NAME] [--no-browser]

Run on 187 from the repo root. Prints one PASS / FAIL / SKIP line per check, then a count; exit status 1 on any FAIL.
- @weblogin is gone (owner, 2026-10-26): one way in, the game username and password.
- Signed-in checks use short-lived sessions minted on 164 with the TEST key (the key never leaves 164, the token is
  kept in memory and never printed), for made-up account ids >= 2,000,000,000 that no game account has, except the
  Buy/Sell round trip, which needs a real TEST account and character (--account, --character).
- Password sign-in and invite sign-up need the TEST game auth API (ticket 105) and, for sign-up, a fresh invite code
  from `!invite` on TEST (QA_INVITE). The sign-in check uses QA_USER / QA_PASS. They come from the environment or
  from QA_CREDS on 164 (KEY=value lines, root 600, read over ssh into memory, never printed). Missing ones are SKIP.
- API calls go through the TEST nginx about once a second, under its limit (60 a minute), so a full run takes a few
  minutes; the board round trip waits up to 2 minutes for the TEST server (it reads the inbox every 30 s).
- Everything it writes is undone at the end: watchlist, alerts and posts of the made-up accounts, the revoke line.
Needs Playwright for the browser checks (themes, phone width); --no-browser skips them.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import check  # noqa: E402
from sections import market  # noqa: E402

HOST = "root@192.168.0.164"
BASE = "http://192.168.0.164:8192"
TEST = "/opt/mapleroot-dev/persistence-test"
SITE_DIR, BUILD = "/srv/slums-site-test", "/opt/slums-site-test"
REVOKED = "/var/lib/slums-login-test/revoked.txt"
QA_CREDS = "/etc/slums-site-test/qa.env"
OTHER_CHAR = "AutoQaOne"  # a real TEST character on another account: the server must say it isn't the poster's
# Made-up account ids, new for every run (so one run's writes never count against the next run's rate limit), one per
# limit test: the market allows 30 writes a minute per account.
_RUN = 2_000_000_000 + (int(time.time()) // 60 % 100_000) * 10
FAKE = {name: _RUN + i for i, name in enumerate(("limits", "revoke", "signout", "launcher", "alerts", "posts"))}
PAGES = ["/", "/join/", "/forgot/", "/login/", "/guide/", "/guide/getting-started.html", "/patch-notes/", "/report/", "/login/",
         "/world/", "/world/feed/", "/world/feed/megaphone.html", "/world/feed/drama.html", "/world/rankings/level.html",
         "/world/economy.html", "/robots.txt"]
GATED = ["/database/", "/market/", "/market/board.html"]
BRANDS = ["maplestory", "maple story", "nexon", "wizet", "v83"]

results = []


def report(status, name, detail=""):
    results.append(status)
    print(f"{status:4}  {name}" + (f"  ({detail})" if detail else ""), flush=True)


def ok(cond, name, detail=""):
    report("PASS" if cond else "FAIL", name, "" if cond else detail)
    return cond


def ssh(cmd, data=None):
    r = subprocess.run(["ssh", "-o", "BatchMode=yes", HOST, cmd], input=data, capture_output=True, text=True, timeout=120)
    return r.stdout


class Http:
    """Through the TEST nginx. API calls are paced to stay under its limit; pages are not limited."""

    def __init__(self):
        self.last = 0.0

    def __call__(self, method, path, body=None, token=None, headers=None, follow=False, fast=False):
        if path.startswith("/api/") and not fast:  # fast: a few calls inside nginx's burst of 20
            wait = 1.05 - (time.time() - self.last)
            if wait > 0:
                time.sleep(wait)
            self.last = time.time()
        h = dict(headers or {})
        if body is not None and headers is None:  # given headers are sent as they are (to test a missing one)
            h = {"Content-Type": "application/json", "X-Slums": "1"}
        if token:
            h["Cookie"] = f"slums_session={token}"
        req = urllib.request.Request(BASE + path, method=method, headers=h,
                                     data=json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None))
        opener = urllib.request.build_opener() if follow else urllib.request.build_opener(NoRedirect)
        try:
            r = opener.open(req, timeout=20)
        except urllib.error.HTTPError as e:
            r = e
        raw = r.read()
        try:
            js = json.loads(raw) if raw[:1] in (b"{", b"[") else None
        except ValueError:
            js = None
        return r.status, r.headers, raw.decode("utf-8", "replace"), js


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def mint(account, character, characters=None):
    """A TEST session token, made on 164 with the TEST key; the key is read there and never sent anywhere."""
    payload = json.dumps({"account_id": account, "account": f"qa{account}", "character": character,
                          "characters": characters or [character]})
    out = ssh(f"python3 -c 'import sys,json; sys.path.insert(0,\"{BUILD}/tools/site\"); import login_service as l; "
              f"k=open(\"/etc/slums-login-test/key\",\"rb\").read().strip(); print(l.Sessions(k).issue(json.load(sys.stdin)))'",
              payload).strip()
    return out if out.count(".") == 1 else None


def age_minutes(iso):
    try:
        t = datetime.fromisoformat(str(iso).replace("Z", "+00:00"))
        return (datetime.now(timezone.utc) - t).total_seconds() / 60
    except ValueError:
        return 1e9


# ── checks ──────────────────────────────────────────────────────────────────────────────────────────────────────────

def pages(http, quick):
    paths = list(PAGES)
    if not quick:  # Slums Street too: 8 random pages from each of its sections, and its own front pages
        listing = ssh(f"cd {SITE_DIR}/database && for d in items mobs maps npcs quests; do ls $d | shuf -n 8 | sed \"s|^|$d/|\"; done")
        paths += ["/database/", "/database/changes.html"] + ["/database/" + p for p in listing.split() if p.endswith(".html")]
    bad, noindex, brand = [], [], []
    for p in paths:
        st, hd, body, _ = http("GET", p, token=TOKENS["limits"] if p.startswith("/database") else None)
        if st != 200:
            bad.append(f"{p} {st}")
            continue
        low = body.lower()
        if p.endswith((".html", "/")) and check.NOINDEX not in body:
            noindex.append(p)
        brand += [f"{p}: {w}" for w in BRANDS if w in low]
    ok(not bad, f"{len(paths)} pages answer 200", "; ".join(bad[:5]))
    ok(not noindex, "every page has the noindex tag", ", ".join(noindex[:5]))
    ok(not brand, "no MapleStory / Nexon / Wizet / v83 text", "; ".join(brand[:5]))
    st, _, robots, _ = http("GET", "/robots.txt")
    ok("Disallow: /" in robots, "robots.txt turns every crawler away")
    st, _, _, _ = http("GET", "/forgot/")
    ok(st == 200, "the forgot-password page is public", str(st))
    st, _, _, _ = http("GET", "/no-such-page.html")
    ok(st == 404, "an unknown page is a 404", str(st))


def gates(http):
    for p in GATED:
        st, hd, _, _ = http("GET", p)
        ok(st == 302 and "/login/?next=" in (hd.get("Location") or ""), f"{p} sends a signed-out visitor to sign in",
           f"{st} {hd.get('Location')}")
        st, _, _, _ = http("GET", p, token=TOKENS["limits"])
        ok(st == 200, f"{p} opens when signed in", str(st))
    for p in ("/secret/peace.webp", "/secret/look.webp", "/secret/shoulder.webp"):
        st, _, _, _ = http("GET", p)
        st2, hd, _, _ = http("GET", p, token=TOKENS["limits"])
        ok(st == 401 and st2 == 200 and "image" in (hd.get("Content-Type") or ""), f"{p}: 401 signed out, image signed in",
           f"{st} / {st2}")
    st, _, _, _ = http("GET", "/api/auth/check")
    ok(st == 404, "/api/auth/check can't be called from outside (nginx internal)", str(st))


def exports(quick):
    """The TEST server's exports: shapes, freshness, and no personal information in any of them."""
    tmp = os.path.join("/tmp", f"slums-qa-{os.getpid()}")
    os.makedirs(tmp, exist_ok=True)
    subprocess.run(["rsync", "-a", f"{HOST}:{TEST}/Server/data/site", f"{HOST}:{TEST}/Server/data/market",
                    f"{HOST}:{TEST}/site-board/outbox", tmp + "/"], capture_output=True, timeout=120)
    from sections import world
    try:
        w = world.load(os.path.join(tmp, "site"))
        probs = world.validate(w)
        ok(not probs, "feed, world and leaderboards have the contract's shape", "; ".join(probs[:3]))
        ok(age_minutes(w["world"].get("generated_at")) < 10, "world.json is fresh (under 10 min)",
           f"{age_minutes(w['world'].get('generated_at')):.0f} min old")
        ok(age_minutes(w["feed"].get("generated_at")) < 30, "feed.json is fresh (under 30 min)",
           f"{age_minutes(w['feed'].get('generated_at')):.0f} min old")
        ok(not w.get("sample"), "World data is the server's, not the samples")
    except SystemExit as e:
        report("FAIL", "World exports load", str(e))
    try:
        m = market.load(os.path.join(tmp, "market"))
        probs = market.validate(m)
        ok(not probs, "market exports have the contract's shape", "; ".join(probs[:3]))
    except SystemExit as e:
        report("FAIL", "market exports load", str(e))
    leaks = []
    for dirpath, _, files in os.walk(tmp):
        for f in files:
            path = os.path.join(dirpath, f)
            rel = os.path.relpath(path, tmp)
            if os.path.splitext(f)[1].lower() not in check.TEXT_EXT:
                continue  # the ranking pictures (PNG): random bytes can look like an email
            text = open(path, encoding="utf-8", errors="replace").read()
            for pat in check.FORBIDDEN_PATTERNS[-2:]:  # the email and IP address patterns
                leaks += [f"{rel}: {m.group(0)}" for m in pat.finditer(text)][:2]
            try:
                data = json.loads(text)
            except ValueError:
                continue
            keys = set()
            stack = [data]
            while stack:
                v = stack.pop()
                if isinstance(v, dict):
                    keys |= {k.lower() for k in v}
                    stack += list(v.values())
                elif isinstance(v, list):
                    stack += v[:5000]
            leaks += [f"{rel}: field {k}" for k in sorted(keys & market.PRIVATE_FIELDS - {"account"})]
    ok(not leaks, "no personal information in any export (emails, IPs, logins, birthdays, PICs, HWIDs, chat)",
       "; ".join(leaks[:5]))
    subprocess.run(["rm", "-rf", tmp])
    age = ssh(f"echo $(( $(date +%s) - $(stat -c %Y {SITE_DIR}/world/index.html) ))").strip()
    ok(age.isdigit() and int(age) < 300, "World pages were rebuilt in the last 5 minutes (timer)", f"{age} s ago")
    age = ssh(f"echo $(( $(date +%s) - $(stat -c %Y {SITE_DIR}/market/index.html) ))").strip()
    ok(age.isdigit() and int(age) < 20 * 60, "market pages were rebuilt in the last 20 minutes (timer)", f"{age} s ago")


def market_limits(http):
    t = TOKENS["limits"]
    st, _, _, me = http("GET", "/api/market/me", token=t)
    if not ok(st == 200 and me and me.get("ok"), "/api/market/me answers a signed-in player", str(st)):
        return []
    items = [it for it in (ITEMS or [])][:22]
    undo = []
    added = 0
    for it in items[:21]:
        undo.append(("/api/market/watch", {"itemId": it, "on": False}))
    for it in items[:20]:
        added += http("POST", "/api/market/watch", {"itemId": it, "on": True}, token=t)[0] == 200
    st, _, _, js = http("POST", "/api/market/watch", {"itemId": items[20], "on": True}, token=t)
    ok(added == 20 and st == 409, "the 21st watchlist item is refused (20 max)", f"{added} added, then {st} {js}")
    ta, ids = TOKENS["alerts"], []
    for i in range(10):
        st, _, _, js = http("POST", "/api/market/alerts", {"itemId": items[i], "dir": "below", "price": 1000 + i}, token=ta)
        if js and js.get("id"):
            ids.append(js["id"])
    undo += [("/api/market/alerts/delete", {"id": a}, ta) for a in ids]
    st, _, _, js = http("POST", "/api/market/alerts", {"itemId": items[11], "dir": "above", "price": 5}, token=ta)
    ok(len(ids) == 10 and st == 409, "the 11th alert is refused (10 max)", f"{len(ids)} made, then {st}")
    # back to back: the TEST server rejects a made-up account's posts on its next inbox read (every 30 s), and a
    # rejected post no longer counts as open, so a slow run would let a 6th one in
    tp, pids = TOKENS["posts"], []
    time.sleep(1.1)
    for i in range(5):
        st, _, _, js = http("POST", "/api/market/posts", {"kind": "WTS", "itemId": items[i], "qty": 1, "price": 100,
                                                          "character": "QaPosts"}, token=tp, fast=True)
        if js and js.get("id"):
            pids.append(js["id"])
    st, _, _, js = http("POST", "/api/market/posts", {"kind": "WTS", "itemId": items[6], "qty": 1, "price": 100,
                                                      "character": "QaPosts"}, token=tp, fast=True)
    if js and js.get("id"):
        pids.append(js["id"])
    undo += [("/api/market/posts/close", {"id": p}, tp) for p in pids]
    ok(len(pids) == 5 and st == 409, "the 6th open post is refused (5 max)", f"{len(pids)} made, the last {st}")
    st, _, _, js = http("POST", "/api/market/watch", {"itemId": items[0]}, token=t, headers={"Content-Type": "application/json"})
    ok(st == 403, "a write without the X-Slums header is refused", str(st))
    st, _, _, js = http("POST", "/api/market/watch", {"itemId": items[0]}, token=t,
                        headers={"X-Slums": "1", "Origin": "https://evil.example"})
    ok(st == 403, "a write from another website is refused", str(st))
    return undo


def board_round_trip(http, account, character):
    if not account or not character:
        report("SKIP", "Buy/Sell board round trip through the TEST server", "needs --account and --character of a real TEST player")
        return []
    t = mint(account, character, [character, OTHER_CHAR])
    good_item, undo = ITEMS[0], []
    st, _, _, good = http("POST", "/api/market/posts", {"kind": "WTB", "itemId": good_item, "qty": 1, "price": 1234,
                                                        "character": character}, token=t)
    st2, _, _, bad = http("POST", "/api/market/posts", {"kind": "WTB", "itemId": good_item, "qty": 1, "price": 1234,
                                                        "character": OTHER_CHAR}, token=t)
    gid, bid = (good or {}).get("id"), (bad or {}).get("id")
    undo += [("/api/market/posts/close", {"id": p}, t) for p in (gid, bid) if p]
    if not ok(gid and bid, "two posts accepted by the site", f"{st} {good} / {st2} {bad}"):
        return undo
    shown = rejected = None
    for _ in range(24):  # the server reads the inbox every 30 s
        time.sleep(5)
        raw = ssh(f"cat {TEST}/site-board/outbox/board.json {TEST}/site-board/outbox/rejected.json 2>/dev/null")
        shown = shown or (gid in raw and gid)
        m = re.search(re.escape(bid) + r'[^}]*"reason"\s*:\s*"([^"]+)"', raw)
        rejected = rejected or (m and m.group(1))
        if shown and rejected:
            break
    ok(bool(shown), "the TEST server put the valid post on the board", "not in board.json after 2 min")
    ok(rejected == "character is not on this account", "the TEST server rejected the post as a character that isn't on the account",
       str(rejected))
    st, _, _, me = http("GET", "/api/market/me", token=t)
    mine = {p["id"]: p for p in (me or {}).get("posts", [])}
    ok(mine.get(bid, {}).get("status") == "rejected", "the site shows the player why their post was rejected",
       str(mine.get(bid)))
    return undo


def sign_out_and_revoke(http):
    t = TOKENS["signout"]
    ok(http("GET", "/api/login/me", token=t)[3].get("signed_in") is True, "a session is signed in")
    http("POST", "/api/login/logout", token=t)
    ok(http("GET", "/market/", token=t)[0] == 302, "after sign-out the same cookie no longer opens the market")
    ok(http("GET", "/api/market/me", token=t)[0] == 401, "and the market service refuses it too")
    t = TOKENS["revoke"]
    ok(http("GET", "/market/", token=t)[0] == 200, "a second session opens the market")
    ssh(f"echo {FAKE['revoke']} >> {REVOKED}")
    try:
        ok(http("GET", "/market/", token=t)[0] == 302, "listing the account in revoked.txt signs it out at once")
    finally:
        ssh(f"sed -i '/^{FAKE['revoke']}$/d' {REVOKED}")


def launcher(http):
    ua = {"User-Agent": "SlumsMS-Launcher/1.6.0"}
    st, _, _, js = http("POST", "/api/login/password", {"user": "qanobody1", "pass": "wrongpw1"},
                        headers=dict(ua, **{"X-Slums": "1", "Content-Type": "application/json"}))
    ok(st in (401, 503) and (js or {}).get("error"), "launcher: sign-in with no Origin is accepted and answered",
       f"{st} {js}")
    st, _, _, _ = http("GET", "/api/login/start", headers=ua)
    st2, _, _, _ = http("POST", "/api/login/start", headers=ua)
    ok(st == 404 and st2 == 404, "the old @weblogin code flow is gone", f"{st} / {st2}")
    t = TOKENS["launcher"]
    st, _, _, js = http("GET", "/api/login/me", token=t, headers=ua)
    ok(js and js.get("signed_in") and js.get("character") == "QaLauncher", "launcher: a stored session says who is signed in", str(js))
    st, _, _, js = http("GET", "/api/market/me", token=t, headers=ua)
    ok(st == 200 and isinstance((js or {}).get("badge"), int), "launcher: the alert badge count is readable", str(st))
    http("POST", "/api/login/logout", token=t, headers=ua)
    ok(http("GET", "/api/login/me", token=t, headers=ua)[3].get("signed_in") is False, "launcher: sign-out ends the stored session")


def creds():
    out = {k: os.environ[k] for k in ("QA_USER", "QA_PASS", "QA_INVITE") if os.environ.get(k)}
    for line in ssh(f"cat {QA_CREDS} 2>/dev/null").splitlines():
        k, _, v = line.partition("=")
        if k.strip() in ("QA_USER", "QA_PASS", "QA_INVITE") and v.strip():
            out.setdefault(k.strip(), v.strip())
    return out


def accounts(http):
    """Ticket 105: password sign-in and invite sign-up, through the TEST game auth API."""
    cr = creds()
    st, _, _, js = http("POST", "/api/login/signup", {"code": "QACODE", "user": "ab", "pass": "abcdef"})
    ok(st == 400 and (js or {}).get("rule") == "user_length", "sign-up: a too-short username is refused with the rule", f"{st} {js}")
    st, _, _, js = http("POST", "/api/login/signup", {"code": "QACODE", "user": "qaabcd", "pass": "a" * 13})
    ok(st == 400 and (js or {}).get("rule") == "pass_length", "sign-up: a 13-character password is refused (the game box takes 12)",
       f"{st} {js}")
    st, _, _, js = http("POST", "/api/login/password", {"user": "", "pass": "x"})
    ok(st == 400, "sign-in: an empty username is refused without asking the game", f"{st} {js}")
    st, _, _, js = http("POST", "/api/login/password", {"user": "qaabcd", "pass": "abcdef"}, headers={"Content-Type": "application/json"})
    ok(st == 403, "sign-in without the X-Slums header is refused", str(st))
    st, _, _, js = http("POST", "/api/login/password", {"user": "qanobody1", "pass": "wrongpw1"})
    if st == 503:
        report("SKIP", "password sign-in and invite sign-up", "the TEST game auth API (ticket 105) isn't up yet")
        return
    ok(st == 401 and js.get("error") == "Wrong username or password.", "a wrong login gets the generic message", f"{st} {js}")
    # ticket 107: a reset request answers the same for any name, and can't be completed before an admin approves it
    rq = {}
    for name in ("qanobody1", cr.get("QA_USER") or "qanobody2"):
        st, hd, _, js = http("POST", "/api/login/reset/request", {"user": name, "contact": "qa run"})
        rq[name] = (st, (js or {}).get("status"), re.search(r"slums_reset=([^;]+)", hd.get("Set-Cookie") or ""))
    if all(v[0] == 503 for v in rq.values()):
        report("SKIP", "forgot password: request, wait, no early password", "the TEST game API has no /reset yet (ticket 107)")
        rq = None
    else:
        ok(len({v[:2] for v in rq.values()}) == 1 and all(v[0] == 200 and v[2] for v in rq.values()),
           "forgot password: a request answers the same for a real and a made-up name", str({k: v[:2] for k, v in rq.items()}))
    reset_checks(http, rq)
    accounts_rest(http, cr)


def reset_checks(http, rq):
    if not rq:
        return
    hdr = {"X-Slums": "1", "Content-Type": "application/json"}
    states = {}
    for name, (_, _, m) in rq.items():
        js = http("POST", "/api/login/reset/status", {}, headers=dict(hdr, Cookie="slums_reset=" + m.group(1)))[3] if m else None
        states[name] = (js or {}).get("status")
    ok(len(set(states.values())) == 1 and "pending" in states.values(),
       "forgot password: a real and a made-up name both wait as pending (the status can't tell which accounts exist)",
       str(states))
    real = list(rq.values())[-1][2]
    st, _, _, js = http("POST", "/api/login/reset/complete", {"pass": "qanewpw1"},
                        headers=dict(hdr, Cookie="slums_reset=" + real.group(1)) if real else hdr)
    ok(st == 409, "forgot password: a new password can't be set before approval", f"{st} {js}")


def login_call(http, path, body):
    """Sign-in calls: the login service allows 10 a minute per address, and this run makes more than that from 187."""
    got = http("POST", path, body)
    if got[0] == 429:
        time.sleep(61)
        got = http("POST", path, body)
    return got


def accounts_rest(http, cr):
    user, pw = cr.get("QA_USER"), cr.get("QA_PASS")
    if user and pw:
        st, hd, _, js = login_call(http, "/api/login/password", {"user": user, "pass": pw})
        tok = re.search(r"slums_session=([^;]+)", hd.get("Set-Cookie") or "")
        ok(st == 200 and tok and http("GET", "/market/", token=tok.group(1))[0] == 200,
           "password sign-in gives a session that opens the market", str(st))
        if tok:
            me = http("GET", "/api/login/me", token=tok.group(1))[3] or {}
            ok(me.get("account", "").lower() == user.lower(), "the session knows the account", str(me))
            if not me.get("character"):   # a new account: no character yet, so nothing to post as
                st2, _, _, js2 = http("POST", "/api/market/posts", {"kind": "WTB", "itemId": ITEMS[0], "qty": 1, "price": 10},
                                      token=tok.group(1))
                ok(st2 == 400, "an account with no character yet can't post on the board", f"{st2} {js2}")
            http("POST", "/api/login/logout", token=tok.group(1))
    else:
        report("SKIP", "password sign-in with a real TEST account", f"no QA_USER / QA_PASS in {QA_CREDS} or the environment")
    code = cr.get("QA_INVITE")
    if code:
        name = "qa" + time.strftime("%m%d%H%M")
        st, _, _, js = login_call(http, "/api/login/signup", {"code": code, "user": name, "pass": "qapass12"})
        if st == 403:  # the code is single use: an earlier run spent it
            report("SKIP", "invite sign-up", f"QA_INVITE was already used; put a fresh code from !invite in {QA_CREDS}")
            return
        ok(st == 200 and js.get("account") == name, f"sign-up with an invite code makes {name}", f"{st} {js}")
        st, _, _, js = login_call(http, "/api/login/signup", {"code": code, "user": name + "x", "pass": "qapass12"})
        ok(st == 403, "the same invite code can't be used twice", f"{st} {js}")
        st, _, _, js = login_call(http, "/api/login/password", {"user": name, "pass": "qapass12"})
        ok(st == 200, "the new account can sign in with its password", f"{st} {js}")
    else:
        report("SKIP", "invite sign-up", f"no fresh QA_INVITE in {QA_CREDS} or the environment")


def browser(quick):
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        report("SKIP", "light/dark themes and phone width", "Playwright isn't installed")
        return
    paths = ["/", "/world/", "/world/feed/", "/login/", "/guide/getting-started.html", "/market/", "/database/"]
    with sync_playwright() as p:
        b = p.chromium.launch()
        problems, same = [], []
        for scheme in ("light", "dark"):
            for w in (1280, 390):
                ctx = b.new_context(viewport={"width": w, "height": 900}, color_scheme=scheme)
                ctx.add_cookies([{"name": "slums_session", "value": TOKENS["limits"], "url": BASE}])
                pg = ctx.new_page()
                errs = []
                pg.on("pageerror", lambda e: errs.append(str(e)))
                for path in paths:
                    pg.goto(BASE + path)
                    if pg.evaluate("document.documentElement.scrollWidth > innerWidth + 1"):
                        problems.append(f"{path} scrolls sideways at {w}px ({scheme})")
                    if w == 1280 and path == "/":
                        same.append(pg.evaluate("getComputedStyle(document.body).backgroundColor"))
                problems += [f"{scheme} {w}px: {e}" for e in errs]
                ctx.close()
        b.close()
    ok(not problems, "light and dark, desktop and phone (390 px): no sideways scroll, no script errors", "; ".join(problems[:4]))
    ok(len(set(same)) == 2, "dark mode really changes the page", str(same))


def undo_all(http, undo):
    for item in undo:
        path, body = item[0], item[1]
        http("POST", path, body, token=item[2] if len(item) > 2 else TOKENS["limits"])


TOKENS, ITEMS = {}, []


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="fewer pages, no board round trip")
    ap.add_argument("--account", type=int, help="a real TEST account id, for the board round trip")
    ap.add_argument("--character", help="a character on that account")
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()
    http = Http()
    print(f"website QA against {BASE} ({ssh(f'cat {BUILD}/DEPLOYED_COMMIT').strip()}), "
          f"{datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC", flush=True)
    for name, acct in FAKE.items():
        TOKENS[name] = mint(acct, {"limits": "QaLimits", "launcher": "QaLauncher", "posts": "QaPosts"}.get(name, "QaPlayer"))
    if not ok(all(TOKENS.values()), "test sessions made on 164 with the TEST key"):
        return 1
    st, _, _, idx = http("GET", "/database/search-index.json", token=TOKENS["limits"])
    untradeable = set(http("GET", "/market/untradeable.json", token=TOKENS["limits"])[3] or [])
    rows = idx if isinstance(idx, list) else []  # [["i", itemId, name], ...] (items; other kinds are mobs, maps...)
    ITEMS[:] = [r[1] for r in rows if isinstance(r, list) and len(r) > 1 and r[0] == "i" and isinstance(r[1], int)
                and r[1] not in untradeable and 1_000_000 <= r[1] < 5_000_000][:40]
    undo = []
    try:
        pages(http, args.quick)
        gates(http)
        exports(args.quick)
        undo += market_limits(http)
        if not args.quick:
            undo += board_round_trip(http, args.account, args.character)
        sign_out_and_revoke(http)
        launcher(http)
        accounts(http)
        if not args.no_browser:
            browser(args.quick)
    finally:
        time.sleep(61)  # the market service allows 30 writes a minute per account; the clean-up needs a fresh minute
        undo_all(http, undo)
        left = []
        for name in ("limits", "alerts", "posts"):
            me = http("GET", "/api/market/me", token=TOKENS[name])[3] or {}
            left += me.get("watch", []) + me.get("alerts", []) + [p for p in me.get("posts", []) if p["status"] == "open"]
        ok(not left, "clean-up: the test watchlist, alerts and posts are gone", str(left)[:120])
    n = {s: results.count(s) for s in ("PASS", "FAIL", "SKIP")}
    print(f"\n{n['PASS']} PASS, {n['FAIL']} FAIL, {n['SKIP']} SKIP")
    return 1 if n["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
