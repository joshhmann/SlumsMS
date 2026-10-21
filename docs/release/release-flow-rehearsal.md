# Release-flow rehearsal (tools/release/release on scratch copies)

- When: 2026-10-21 19:53 - 19:58 UTC, workstation openclaw -> root@192.168.0.164. **Result: PASS**
- Scratch: server copy `/opt/mapleroot-dev/release-flow-rehearsal/Server` (old = 1.4.0 runtime, jar `218111b2bd784074`), schema `rh_flow` from the newest nightly dump
  (test DB 3307), unit `slumsms-rh-flow` (8519/8885+), feed `/opt/mapleroot-dev/release-flow-rehearsal/feed` (live manifest + layer manifests), staging
  `/opt/mapleroot-dev/release-flow-rehearsal/staging`, deploy roots `/opt/mapleroot-dev/release-flow-rehearsal/deployments/v<ver>`, fake monitor (posts recorded). Live and /srv/slums-patch untouched.
- New version: 1.4.1 jar `4f2d879c8e3dc179` + scripts/data staged via gen-deploy and templates/deploy.body.sh (the --phase code);
  client: `/srv/slums-staging/client-1.4.1` published as a new layer per scenario into the scratch feed with the real host publisher.
- release --dry-run: **PASS** (plan printed; manifest, posts and backup dir unchanged)

## publish-fail (`1.4.2-rhpublish10211953`): PASS

- ok: release exit 1 (expected 1)
- ok: server running
- ok: maintenance off
- ok: launcher-test mirrors manifest.json
- ok: old jar 218111b2bd784074 running
- ok: manifest.json restored to the pre-release one
- ok: the publish did write manifest.json before failing (worst case)
- ok: new version never applied (no .apply-start.started)
- ok: old server restarted by deploy.sh --phase restart-old
- ok: posts: going-down, back-up (no client line)

```
== release 1.4.2-rhpublish10211953: FAILED at step 3; old server + old client. Layer client/1.4.2-rhpublish10211953 may be published but unreferenced: use a new client version next time ==
  19:54:22 1. pre-flight (changes nothing)
  19:54:36 2. deploy.sh --phase stop-backup (maintenance on + going-down post, stop, backup)
  19:54:58 3. publish client 1.4.2-rhpublish10211953 + release manifest 1.4.2-rhpublish10211953 (server DOWN)
  19:55:28 publish FAILED: the new server will not be started
  19:55:28 restore the previous release manifest (/opt/mapleroot-dev/release-flow-rehearsal/staging/manifest-before-1.4.2-rhpublish10211953.json)
  19:55:29 manifest.json restored (players get the old client on Play)
  19:55:34 old version back up (old client, old server)
  log: /root/work/SlumsMS-release-flow/dist/release-runs/release-1.4.2-rhpublish10211953-20261021T195422Z.log
```

## start-fail (`1.4.2-rhstart10211953`): PASS

- ok: release exit 1 (expected 1)
- ok: server running
- ok: maintenance off
- ok: launcher-test mirrors manifest.json
- ok: old jar 218111b2bd784074 running (rollback.sh)
- ok: manifest.json restored to the pre-release one
- ok: manifest restored BEFORE rollback.sh
- ok: rollback receipt written
- ok: posts: going-down, back-up (rollback's; no client line)

```
== release 1.4.2-rhstart10211953: FAILED at step 4; rolled back to the old server + old client ==
  19:55:36 1. pre-flight (changes nothing)
  19:55:49 2. deploy.sh --phase stop-backup (maintenance on + going-down post, stop, backup)
  19:56:08 3. publish client 1.4.2-rhstart10211953 + release manifest 1.4.2-rhstart10211953 (server DOWN)
  19:56:36 client published: manifest.json 7369e6e2094d2259
  19:56:36 4. deploy.sh --phase apply-start (migrations, config, install, start)
  19:56:52 server steps FAILED after the client publish: old client first, then rollback.sh
  19:56:53 restore the previous release manifest (/opt/mapleroot-dev/release-flow-rehearsal/staging/manifest-before-1.4.2-rhstart10211953.json)
  19:56:53 manifest.json restored (players get the old client on Play)
  19:57:01 rollback.sh done: old server back (its 'back up' post)
  log: /root/work/SlumsMS-release-flow/dist/release-runs/release-1.4.2-rhstart10211953-20261021T195536Z.log
```

## ok (`1.4.2-rhok10211953`): PASS

- ok: release exit 0 (expected 0)
- ok: server running
- ok: maintenance off
- ok: launcher-test mirrors manifest.json
- ok: new jar 4f2d879c8e3dc179 running
- ok: manifest.json = release 1.4.2-rhok10211953 with client 1.4.2-rhok10211953
- ok: deploy receipt PASS
- ok: posts: going-down + ONE back-up with the client
- ok: server stopped -> manifest written -> new server started (stop1=1792612655 manifest=1792612686 start=1792612687 ok=1)

```
== release 1.4.2-rhok10211953: PASS ==
  19:57:02 1. pre-flight (changes nothing)
  19:57:18 2. deploy.sh --phase stop-backup (maintenance on + going-down post, stop, backup)
  19:57:38 3. publish client 1.4.2-rhok10211953 + release manifest 1.4.2-rhok10211953 (server DOWN)
  19:58:06 client published: manifest.json 9b203be7af8c3cd7
  19:58:06 4. deploy.sh --phase apply-start (migrations, config, install, start)
  19:58:13 4b. deploy.sh --phase smoke
  19:58:34 5. maintenance off + one Cody post
  19:58:34 done: server 1.4.2-rhok10211953 up, client 1.4.2-rhok10211953 in the launcher
  log: /root/work/SlumsMS-release-flow/dist/release-runs/release-1.4.2-rhok10211953-20261021T195702Z.log
```

Full output: `dist/release-flow-rehearsal/` on the workstation (not in git).
