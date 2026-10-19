# Live baseline handoff — October 19, 2026

The accepted `baseline-20261018-r1` server is deployed. This document records the
deployment; the tag remains the immutable source reference. Later playerbot work
from the owner's enhanced fork has not been merged or enabled.

## Runtime and source

- SSH: `root@192.168.0.164`.
- Live source and runtime: `/opt/mapleroot/MapleRoot Full Repack/Server`.
  **Edits here affect the live installation.** Use a separate checkout for new work.
- Private server repository: `https://github.com/joshhmann/SlumsMS`.
- Private client repository: `https://github.com/joshhmann/SlumsMS-Client`.
- Matching source tag in both repositories: `baseline-20261018-r1`.
- Server source commit: `0a31673677140db8d4c53d3712b09a07596098ed`.
- Client source commit: `d82253c083e92143a966517831409fbd750a0b8d`.
- Deployed JAR SHA-256:
  `b12996a75b3d528fcce1c46d898c0a7976420f5bcd726f87bcb605524b2193b1`.
- Login port: `8485`; channel ports: `7585`–`7592`.
- Private database configuration is retained in `config.yaml`, outside Git.
  Live database is `mapleroot` at local port `3309`; disposable integration schemas
  use port `3307`. Never replace live players with a test database.

## Service

**Use `slumsms.service` now.** It replaces the stopped transient
`mapleroot-control.service`. The new unit is persistent under
`/etc/systemd/system/slumsms.service` and enabled at boot. Reboot itself has not
been tested. The service retains the previous root runtime identity.

```bash
systemctl status slumsms.service --no-pager
journalctl -u slumsms.service -n 100 --no-pager
```

Windowed cash shop and weather are enabled. SoloMapling hooks are included in the
source, but `solomapling.enabled`, automatic population, and tutorial admin mode
are explicitly false. There is no dedicated live bot template configured. Do not
enable population merely to test a new bot change.

The live database remains hosted by the existing `buns-clean-db-1` container;
this deployment did not move or replace that database service. Its restart policy
was changed from `no` to `unless-stopped` so it can return with Docker at boot;
this did not restart the database. Reboot remains untested.

## Included behavior and validation

Arcane remains the main hub. Classic FM and rooms, town FM return routing, and
Arcane access are supplied by the matched assets/scripts. Ledger migrations
`001-preserve-arcane-for-classic-fm` and `002-classic-fm-return` are applied.
Live account, character, inventory, equipment, skill, keymap and saved-location
row counts were unchanged by migration. These migrations relocate original
Arcane map references; they do not import disposable test characters.

The client includes the classic UI, resolution/quickslot compatibility, movement
bindings, windowed cash shop, visual weather/time, FM portal animation correction,
and accepted multipart mount preview/name fixes. The live launcher is:

`Z:\MSv83\MapleRoot Full Repack\Integration\LiveClient\Kaentake.exe`

Its full Data tree is a junction to the already accepted WeatherWork Data tree;
all 95 packaged overlay hashes were checked. Retain that target or copy the full
Data tree before moving the launcher. The previous test launcher still points
to port 8488 and is not the live launcher.

Local builds passed 2,055 Java tests and seven native checks; both baseline CI
builds passed. The deployment package passed independent startup before rollout.
After rollout the live JAR hash, clean tagged source, cash-shop catalogue (1,839
rows), disabled bots, successful startup, and v83 handshake on login plus all
eight channels were verified. No restart loop or startup error was observed.
The owner confirmed entry after the Parsec workaround below. Final SHOP/FM/Arcane
checks are pending.

### Parsec startup workaround

In this Windows/Parsec virtual-display session the client reported `-2147467259`
during `IWzGr2D::Initialize`, before login. The previously accepted test launcher
failed at the same call. 800×600 startup, per-app GPU preference and native
DirectX trials did not resolve it; all trial settings and the original wrapper
were restored. No Windows driver or display device was reset.

**Working sequence: disconnect Parsec, launch the game, then reconnect.** The
owner confirmed getting in after this sequence with the original checkpoint
runtime. Treat this as an observed workaround for this machine/session, rather
than a confirmed explanation of the underlying display-driver problem.


## Backup and rollback

Fresh stopped-service database and installation backup:

`/opt/mapleroot/backups/pre-live-baseline-20261019T070109Z/`

Verified off-server copy:

`Z:\MSv83\backups\pre-live-baseline-20261019T070109Z\`

The dump uses table locks to cover InnoDB and MyISAM, and both archives and file
hashes were verified. These private backups contain credentials/player data and
must remain outside Git. The original server directory is also retained at:

`/opt/mapleroot-deployments/baseline-20261018-r1/PreviousServer`

Deployment receipts are under that deployment directory and locally under
`Integration/Evidence/live-deployment-20261019.json`. The first attempts hit a
transient-unit naming conflict and a unit path quoting error; both automatically
restored the prior database/runtime before the final successful deployment.

For rollback, first stop `slumsms.service` and take another private backup if any
new player progress exists. Restore the previous runtime **and its matching
client**, then restore the predeployment database (which discards subsequent
progress) or review the reverse migrations in order 002 then 001. Do not point
the old JAR at migrated map references without reviewing the matching assets.
Recreate the old launch configuration from the saved service-unit backup;
the old unit was transient and must not be assumed to survive reboot. Validate
the service file before starting and check readiness beyond its active status.

## Next developer's starting point

Develop the enhanced playerbot fork in an isolated checkout/database, merge it
against this tag, and preserve MapleRoot packet widths, avatar/equipment data,
EXP behavior, and custom skill handling. The earlier headless drop fix remains
deferred and is not part of the running baseline. Bot lifecycle, trade, drops,
party transitions and shutdown still need broader testing before live rollout.
Do not change existing live settings or start the old test services as part of
the merge. Never commit private config, database dumps, logs, or game Data.
