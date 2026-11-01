# The TEST copy of the site (192.168.0.111)

For testing every site feature against the TEST server before launch: sign in with a TEST account (or sign up with
an invite code), Slums Street, the market (watchlist, alerts, Buy/Sell posts the TEST server checks), World from the TEST
exports, the secrets. Not for players. LAN only: **http://192.168.0.111:8192/**. The host is one setting,
`SLUMS_TEST_HOST` (default `192.168.0.111`, "mapletest"); TEST was on 192.168.0.164 until 1.6.3, same ports and paths.

`tools/site/test164/install.sh` (run on 187) installs or updates it; `--remove` takes it away. It writes nginx's
`listen <host>:8192` and the download pass's `allow <host>` from `SLUMS_TEST_HOST`, so there is no second copy of the
address in `nginx.conf`.

| Part | Where | Notes |
|---|---|---|
| Sign-in | `slums-login-test`, 127.0.0.1:8194 | key `/etc/slums-login-test/key` (throwaway, TEST only, root 600) and `ip-salt`; checks passwords through the TEST game auth API on 127.0.0.1:8196 (`/etc/slums-auth-test/secret`) |
| Market | `slums-market-test`, 127.0.0.1:8195 | db `/var/lib/slums-market-test/market.db`; board folders are the TEST server's `site-board/{inbox,outbox}` |
| Web | `slums-site-test-web`: its own nginx (`nginx -c /etc/slums-site-test/nginx.conf`) on 192.168.0.111:8192 | never the shared nginx that serves live |
| Pages | `/srv/slums-site-test`, from `/opt/slums-site-test` (this branch) | World every 2 min, market every 15 min, from `/opt/mapleroot-dev/persistence-test/Server/data/{site,market}` |

Differences from the real site, all because it is plain http on a port: no
Secure cookie flag, and nginx passes `Host` with the port. Logs: `journalctl -u slums-login-test -u slums-market-test`,
`/var/log/slums-site-test/`.

QA: `tools/site/test164/qa.sh` (automatic, PASS/FAIL list, same `SLUMS_TEST_HOST`) and `OWNER-CHECKLIST.md` (what needs a person in game).
