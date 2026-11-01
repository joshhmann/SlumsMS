#!/usr/bin/env bash
# Put the TEST copy of the site on the TEST host: sign-in (8194), market (8195), its own nginx on <host>:8192 and the
# rebuild timers, all reading and writing TEST folders only. Run on 187 from the repo root.
#   tools/site/test164/install.sh            install or update, then a full build and (re)start
#   tools/site/test164/install.sh --remove   stop and remove everything it made (keeps the key and the market db)
# The host is one setting, SLUMS_TEST_HOST (default 192.168.0.111, "mapletest"; TEST lived on 192.168.0.164 until
# 1.6.3). It is where the copy goes, and what the nginx "listen" and the download-pass "allow" line say: those two are
# written from @TEST_HOST@ in nginx.conf and the web unit at install time, so this file never carries a second copy.
# Never touches the shared nginx on that host, live's folders or units, the TEST game server or start-test.sh.
set -euo pipefail
TEST_HOST=${SLUMS_TEST_HOST:-192.168.0.111}
# The 1.6.3 NX desk on the TEST site (s44): off (1.6.2's NX order book), mock (fixtures) or on (the game's desk).
# It goes into the market service, the market rebuild and this install's build alike.
NX_DESK=${SLUMS_TEST_NX_DESK:-off}
case $NX_DESK in off|mock|on) ;; *) echo "SLUMS_TEST_NX_DESK must be off, mock or on, not '$NX_DESK'" >&2; exit 1 ;; esac
case $TEST_HOST in
  ""|*[!0-9.]*) echo "SLUMS_TEST_HOST must be an IPv4 address, not '$TEST_HOST'" >&2; exit 1 ;;
esac
HOST="root@$TEST_HOST"
DEST=/opt/slums-site-test
DATA=${SLUMS_HS_DATA:-data/site/hiddenstreet}
UNITS="slums-login-test.service slums-market-test.service slums-report-test.service slums-site-test-web.service slums-site-test-world.service
slums-site-test-world.timer slums-site-test-market.service slums-site-test-market.timer"
cd "$(git rev-parse --show-toplevel)"

if [ "${1:-}" = "--remove" ]; then
  ssh "$HOST" "systemctl disable --now slums-site-test-world.timer slums-site-test-market.timer slums-site-test-web \
    slums-market-test slums-login-test slums-report-test 2>/dev/null || true
    cd /etc/systemd/system && rm -f $(echo $UNITS) && systemctl daemon-reload
    rm -rf $DEST /srv/slums-site-test /etc/slums-site-test /var/lib/slums-site-test-web"
  echo "removed (kept /etc/slums-login-test/key, /etc/slums-report-test, /var/lib/slums-report-test, /var/lib/slums-login-test, /var/lib/slums-market-test, /var/log/slums-site-test)"
  exit 0
fi

# data/site/extra-icon-items.txt too (sections/dbdata.py EXTRA_ICON_ITEMS), as the live build copy does: without it the
# market's items with no Slums Street page show no icon
for f in data/site/extra-icon-items.txt "$DATA/items.json"; do
  [ -e "$f" ] || { echo "missing $f" >&2; exit 1; }
done
before=$(ssh "$HOST" 'cat /run/nginx.pid; find /etc/nginx -type f -print0 | sort -z | xargs -0 md5sum | md5sum')

ssh "$HOST" "mkdir -p $DEST/tools $DEST/docs $DEST/hiddenstreet $DEST/data $DEST/cache /srv/slums-site-test /etc/slums-site-test \
  /var/lib/slums-login-test /var/lib/slums-market-test /var/log/slums-site-test \
  /var/lib/slums-site-test-web/{body,proxy,fastcgi,uwsgi,scgi}
  touch /var/lib/slums-login-test/revoked.txt
  install -d -m 700 /etc/slums-login-test
  # a throwaway TEST-only key, never the live one; nobody prints or copies it
  [ -s /etc/slums-login-test/key ] || (umask 077; head -c 48 /dev/urandom > /etc/slums-login-test/key)
  chmod 600 /etc/slums-login-test/key
  # the salt for hashing visitors' addresses before they go to the game server (ticket 105), TEST only
  [ -s /etc/slums-login-test/ip-salt ] || (umask 077; head -c 32 /dev/urandom > /etc/slums-login-test/ip-salt)
  chmod 600 /etc/slums-login-test/ip-salt
  # ticket 106: a throwaway TEST download-pass key, separate from the session key; never printed
  [ -s /etc/slums-login-test/pass-key ] || (umask 077; head -c 48 /dev/urandom > /etc/slums-login-test/pass-key)
  chmod 600 /etc/slums-login-test/pass-key
  # 1.6.2 ticket 157: the TEST bot inspect secret, shared with the TEST game (-Dslums.inspect.secretFile); text, never printed
  install -d -m 700 /etc/slums-inspect-test
  [ -s /etc/slums-inspect-test/secret ] || (umask 077; head -c 32 /dev/urandom | base64 | tr -d '=+/\n' > /etc/slums-inspect-test/secret)
  chmod 600 /etc/slums-inspect-test/secret
  # the file behind /dl-test/ (the download pass check on TEST)
  install -d -o www-data -g www-data -m 750 /var/lib/slums-site-test-web/dl-test /var/lib/slums-site-test-web/passcache
  echo 'SlumsMS TEST download: ok' > /var/lib/slums-site-test-web/dl-test/hello.txt
  # the report form's own TEST salt and reports file, never live's /etc/slums-report or /var/lib/slums-feedback
  install -d -m 700 /etc/slums-report-test; install -d -m 750 /var/lib/slums-report-test
  [ -s /etc/slums-report-test/salt ] || (umask 077; head -c 32 /dev/urandom > /etc/slums-report-test/salt)
  chmod 600 /etc/slums-report-test/salt"
# testdata stays home, except the NX desk fixtures that SLUMS_TEST_NX_DESK=mock serves (live's copy never needs them)
rsync -a --delete --exclude __pycache__ --include testdata/ --include 'testdata/nx-desk/***' --exclude 'testdata/*' \
  tools/site/ "$HOST:$DEST/tools/site/"
rsync -a --delete docs/site/ "$HOST:$DEST/docs/site/"
rsync -a --delete docs/wiki/ "$HOST:$DEST/docs/wiki/"
rsync -a --delete --exclude hiddenstreet/ data/site/ "$HOST:$DEST/data/site/"
rsync -a --delete --include '*.json' --exclude '*' "$DATA/" "$HOST:$DEST/hiddenstreet/"
# the item, mob and NPC icons (cache/site-icons on 187, 63 MB, not in git): this copy builds Slums Street on the host
[ -f cache/site-icons/manifest.json ] && rsync -a --delete cache/site-icons/ "$HOST:$DEST/cache/site-icons/" \
  || echo "no cache/site-icons here: Slums Street on TEST will show empty icon slots" >&2
ssh "$HOST" "echo $(git rev-parse --short HEAD) > $DEST/DEPLOYED_COMMIT
  sed 's/@TEST_HOST@/$TEST_HOST/g' $DEST/tools/site/test164/nginx.conf > /etc/slums-site-test/nginx.conf
  chmod 0644 /etc/slums-site-test/nginx.conf
  for u in $(echo $UNITS); do sed -e 's/@TEST_HOST@/$TEST_HOST/g' -e 's/@NX_DESK@/$NX_DESK/g' $DEST/tools/site/test164/\$u > /etc/systemd/system/\$u
    chmod 0644 /etc/systemd/system/\$u; done
  systemctl daemon-reload
  nginx -t -q -c /etc/slums-site-test/nginx.conf
  cd $DEST && flock -w 300 /run/lock/slums-site-test-build.lock nice -n 19 env SLUMS_MARKET_NX_DESK=$NX_DESK python3 tools/site/build.py --private --world \
    --out /srv/slums-site-test --no-tar --hs-data $DEST/hiddenstreet \
    --site-data /opt/mapleroot-dev/persistence-test/Server/data/site \
    --market-data /opt/mapleroot-dev/persistence-test/Server/data/market \
    --dashboard-web /srv/slums-dashboard-test/web | tail -1
  systemctl enable -q slums-login-test slums-market-test slums-report-test slums-site-test-web slums-site-test-world.timer slums-site-test-market.timer
  systemctl restart slums-login-test slums-market-test slums-report-test slums-site-test-web
  systemctl restart slums-site-test-world.timer slums-site-test-market.timer
  systemctl start --no-block slums-site-test-world.service slums-site-test-market.service
  sleep 2; systemctl is-active slums-login-test slums-market-test slums-report-test slums-site-test-web | tr '\n' ' '; echo"
after=$(ssh "$HOST" 'cat /run/nginx.pid; find /etc/nginx -type f -print0 | sort -z | xargs -0 md5sum | md5sum')
[ "$before" = "$after" ] && echo "shared nginx untouched (same pid and config)" || { echo "WARNING: shared nginx pid or config changed" >&2; exit 1; }
echo "TEST site at http://$TEST_HOST:8192/ ($(git rev-parse --short HEAD))"
