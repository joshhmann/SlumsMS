# Site stack: proposal (no migration yet)

Owner, 2026-10-27: "we're getting pretty into it and maybe frameworks would greatly benefit". This is a proposal to
decide on, not work in progress. Nothing moves until the owner says go.

## Today

- **Pages:** generated ahead of time by `tools/site/build.py` (Python standard library): about 18,000 static files.
  World and market rebuild every 2 minutes on 164. Fast and cheap, and nothing runs per visitor.
- **Live bits:** plain JavaScript, about 900 lines in all (theme and secrets, bell, charts, search, market forms).
- **Services:** `login_service.py` (sign-in, sign-up, resets) and `market_service.py` (watchlist, alerts, board,
  offers), standard library `http.server` behind nginx with `auth_request`, plus SQLite. 89 tests.

What hurts as it grows: hand-written request parsing and validation in the services, every endpoint wiring its own
checks, and pages that only change when a timer rebuilds them.

## Proposal

| Part | Recommendation | Why |
|---|---|---|
| Pages | **Stay generated (static).** No server-rendered templates. | They're fast, cache well and can't break under load. Personal data already comes from the APIs. |
| Live bits | **Alpine.js** (one script, no build step) for FM now (filter and sort), the board and My market. Plain script for self-updating pages (World, bell). | Keeps lists, filters and forms in sync without hand-written DOM code. htmx needs server-rendered HTML fragments, and we serve JSON. |
| Services | **Flask 3** (Ubuntu's `python3-flask`), with a small validation layer, one module per area. | It's an Ubuntu package on 164 (24.04 has 3.0.2 with security updates via apt), so there's no venv or pip on the server, and it's simple to read. |
| Alternative | FastAPI, only if typed request models matter more than upkeep. | Better validation (Pydantic) and auto docs, but 24.04's apt version is 0.101 on Pydantic 1, so in practice it's a venv with pinned pip packages (about 10) to keep patched. |
| Contracts | **Unchanged:** the same URLs, the `slums_session` cookie format and key file, nginx `auth_request`, the SQLite schema, the inbox/outbox files, and the game auth API. | The site pages, the launcher and the game server don't change. |

## Cost

- **Dependencies:** Flask via apt is 6 small packages (Werkzeug, Jinja2, itsdangerous, click, blinker, markupsafe),
  patched by Ubuntu with `unattended-upgrades`. FastAPI means a venv with pinned versions, `pip-audit` now and then,
  and someone bumping them. Alpine.js is one pinned file served from our own server (no CDN), updated by hand.
- **Supply chain:** apt packages are signed and reviewed by Ubuntu. A venv trusts PyPI for every package. That's the
  main reason for Flask via apt.
- **Tests:** the current HTTP tests start the services and call them over HTTP, so most can stay as contract tests.
  Flask's test client replaces the server start-up in about a third of them.
- **Effort:** porting the two services is about 1-2 days with the tests. Alpine for FM now and the board is about
  1 day. The rest is unchanged.

## Timing

- **Not in 1.6.0.** Launch is days away, and every flow (sign-up, resets, the board, the bell) has passed QA on TEST
  as it is. A port now re-opens all of it for no visible gain.
- **1.6.1 branch, started right after the 1.6.0 launch** (not tonight: site-160-theme still gets launch fixes and two
  lines would conflict). Order:
  1. Self-updating live pages and bell: plain script, can even go out as a 1.6.0.x site update.
  2. FM now with Alpine (listings already export every 2 minutes).
  3. Port `market_service.py` to Flask with the same URLs and cookie, with the existing tests green before and after.
     Then `login_service.py`.
  4. Build the NX-meso exchange new on the Flask services (validation, sessions, audit log), the main reason to move.
- **Rollback:** each service port ships behind the same nginx paths and port, so rolling back means starting the old
  unit.

## Decide

1. Flask via apt (recommended) or FastAPI via venv.
2. OK to add Alpine.js for the data-heavy market pages.
3. Start the 1.6.1 branch after launch night.
