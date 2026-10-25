# Installing the report form receiver (host 164, run by the owner)

The form page (`/report/`) ships with the normal site build. The receiver is a separate small service that listens on
127.0.0.1:8093; nginx on port 8092 forwards `POST /api/report` to it. Until the service is installed the page
loads but sending shows "We could not reach the server". Reports are one JSON line each in
`/var/lib/slums-feedback/reports.jsonl`. Nothing here touches the game server.

Files (from the `site` branch): `tools/site/report_service.py`, `report_export.py`, `slums-report.service`,
`slums-report.nginx.conf`.

## Install

Run as root on 164.

```
# 1. user and folders
useradd --system --no-create-home --shell /usr/sbin/nologin slums-report
install -d -o slums-report -g slums-report -m 0750 /var/lib/slums-feedback
install -d -o root -g slums-report -m 0750 /etc/slums-report /opt/slums-report

# 2. the code (copy from the repo checkout, adjust the source path)
install -m 0644 tools/site/report_service.py tools/site/report_export.py /opt/slums-report/

# 3. salt for hashing IPs: random, readable only by root and the service
head -c 32 /dev/urandom | base64 > /etc/slums-report/salt
chown root:slums-report /etc/slums-report/salt && chmod 0640 /etc/slums-report/salt

# 4. systemd
install -m 0644 tools/site/slums-report.service /etc/systemd/system/slums-report.service
systemctl daemon-reload && systemctl enable --now slums-report
systemctl status slums-report --no-pager

# 5. nginx: add the two parts of tools/site/slums-report.nginx.conf (part 1 in the http block, e.g.
#    /etc/nginx/conf.d/slums-report.conf; part 2 inside the 8092 server block), then
cp /etc/nginx/sites-available/slums-site /root/slums-site.nginx.bak     # backup before editing
nginx -t && systemctl reload nginx

# 6. publish the new site build (docs/site/DEPLOY.md) so /report/ exists
```

## Check

```
curl -s http://127.0.0.1:8093/api/report/health              # {"ok": true, "reports": 0}
curl -s http://127.0.0.1:8092/api/report/health              # same, through nginx (local only)
curl -s -X POST http://127.0.0.1:8092/api/report -H 'Content-Type: application/json' \
  -d '{"type":"bug","title":"install test","details":"ignore me"}'     # {"ok": true, "id": 1}
tail -n 1 /var/lib/slums-feedback/reports.jsonl               # has ip_hash, no raw address
```

That test report is real: note its id and tell the developers to ignore it (or delete the line while the service is
stopped). Then try the form at https://theslumsms.asslorde.com/report/ .

Visitor addresses rely on Caddy: it must send the visitor in `X-Forwarded-For` (Caddy's `reverse_proxy` does by
default and replaces anything the visitor sent). If another proxy is ever added in front of Caddy, set
`Environment=SLUMS_REPORT_PROXY_HOPS=2` in the unit.

## Read the reports

```
python3 /opt/slums-report/report_export.py --since 0       # JSON lines, oldest first, no hashed IPs
```

Pass the last id you already copied as `--since`.

## Roll back

```
systemctl disable --now slums-report
rm /etc/systemd/system/slums-report.service && systemctl daemon-reload
# remove the two nginx parts (or restore /root/slums-site.nginx.bak and delete conf.d/slums-report.conf), then
nginx -t && systemctl reload nginx
```

The reports file is kept; remove `/var/lib/slums-feedback`, `/opt/slums-report`, `/etc/slums-report` and the
`slums-report` user only when you are sure you do not want the data. Re-publishing the site without the report page
removes the form (the nav link comes from the build).
