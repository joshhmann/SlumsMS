#!/usr/bin/env bash
# Put the TEST copy of the site on 164: sign-in (8194), market (8195), its own nginx on 192.168.0.164:8192 and the
# rebuild timers, all reading and writing TEST folders only. Run on 187 from the repo root.
#   tools/site/test164/install.sh            install or update, then a full build and (re)start
#   tools/site/test164/install.sh --remove   stop and remove everything it made (keeps the key and the market db)
# Never touches the shared nginx on 164, live's folders or units, the TEST game server or start-test.sh.
set -euo pipefail
HOST=root@192.168.0.164
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

[ -e "$DATA/items.json" ] || { echo "missing $DATA/items.json" >&2; exit 1; }
before=$(ssh "$HOST" 'cat /run/nginx.pid; find /etc/nginx -type f -print0 | sort -z | xargs -0 md5sum | md5sum')

ssh "$HOST" "mkdir -p $DEST/tools $DEST/docs $DEST/hiddenstreet $DEST/cache /srv/slums-site-test /etc/slums-site-test \
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
  # the file behind /dl-test/ (the download pass check on TEST)
  install -d -o www-data -g www-data -m 750 /var/lib/slums-site-test-web/dl-test /var/lib/slums-site-test-web/passcache
  echo 'SlumsMS TEST download: ok' > /var/lib/slums-site-test-web/dl-test/hello.txt
  # the report form's own TEST salt and reports file, never live's /etc/slums-report or /var/lib/slums-feedback
  install -d -m 700 /etc/slums-report-test; install -d -m 750 /var/lib/slums-report-test
  [ -s /etc/slums-report-test/salt ] || (umask 077; head -c 32 /dev/urandom > /etc/slums-report-test/salt)
  chmod 600 /etc/slums-report-test/salt"
rsync -a --delete --exclude __pycache__ --exclude testdata tools/site/ "$HOST:$DEST/tools/site/"
rsync -a --delete docs/site/ "$HOST:$DEST/docs/site/"
rsync -a --delete docs/wiki/ "$HOST:$DEST/docs/wiki/"
rsync -a --delete --include '*.json' --exclude '*' "$DATA/" "$HOST:$DEST/hiddenstreet/"
# the item, mob and NPC icons (cache/site-icons on 187, 63 MB, not in git): this copy builds Slums Street on 164
[ -f cache/site-icons/manifest.json ] && rsync -a --delete cache/site-icons/ "$HOST:$DEST/cache/site-icons/" \
  || echo "no cache/site-icons here: Slums Street on TEST will show empty icon slots" >&2
ssh "$HOST" "echo $(git rev-parse --short HEAD) > $DEST/DEPLOYED_COMMIT
  cp $DEST/tools/site/test164/nginx.conf /etc/slums-site-test/nginx.conf
  cd $DEST/tools/site/test164 && cp $(echo $UNITS) /etc/systemd/system/ && systemctl daemon-reload
  nginx -t -q -c /etc/slums-site-test/nginx.conf
  cd $DEST && flock -w 300 /run/lock/slums-site-test-build.lock nice -n 19 python3 tools/site/build.py --private --world \
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
echo "TEST site at http://192.168.0.164:8192/ ($(git rev-parse --short HEAD))"
