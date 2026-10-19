# SlumsMS handoff — state on 2026-10-19

This is state, not instructions. Start with `README.md` in this folder for the ticket list.

## Live

- **Server:** v1.2.1 runs on the LXC at 192.168.0.164, `/opt/mapleroot/MapleRoot Full Repack/Server`, unit
  `slumsms.service` (SuccessExitStatus=143).
  - Login port 8485, channel ports 7585–7592.
  - `HOST` is the public IP and `LANHOST` is 192.168.0.164.
  - The database is MySQL in container `buns-clean-db-1`, port 3309, schema `mapleroot`.
- **Deployments and backups:**
  - Receipt and rollback: `/opt/mapleroot-deployments/v1.2.1/DEPLOYMENT.md` (v1.2.0 receipt alongside). PIC is off.
  - Backups: `/opt/mapleroot/backups/pre-v1.2.*`.
- **Release rule:** test install, then the owner's in-game check, then backup, then deploy plus receipt.
  - New `config.yaml` keys may only go live together with the jar that knows them (unknown keys stop boot).
  - Live is edited only with the owner's go.
- **Domains:**
  - `slumsms.asslorde.com` is the game, Cloudflare DNS-only. LAN players arrive via router hairpin as 192.168.0.1.
  - `slumspatch.asslorde.com` goes through the owner's Caddy to nginx on 164:8090, serving `/srv/slums-patch`.
- **Patch server:**
  - Friends-only: HTTP basic auth, user `slums`; the key is in a root-only file on 164.
  - Current release: 1.2.1 = base layer `v83-2026.10.19` + client layer `1.2.0`.
  - The publisher lives at `/opt/slums-tools` (node + launcher tools); staging is at `/srv/slums-staging`.
  - Publish commands are in the launcher README.

## Repositories (all public, AGPL-3.0; tag v1.2.0)

| Repo | main | Open branches |
| --- | --- | --- |
| joshhmann/SlumsMS (server) | v1.2.0 live code | `persistence-on-v120` (bots + real-bots spec), `nav-v2` (ticket 01), `daily-checkin` |
| joshhmann/SlumsMS-Client | v1.2.0 = single `slums.dll` + `SlumsMS.exe`, DNS host | `daily-checkin` |
| joshhmann/slumsms-launcher | v1.2.1 (access key, layered releases, fast verify) | — |
| joshhmann/SoloMapling | the bot fork's history | — |

Credits and provenance: `README.md` (Credits) and `docs/PROVENANCE.md`. Every port from nutnnut/Cosmic or
Kiwiism/cosmic_agents gets recorded there.

## Test installs on 164 (never live)

- `/opt/mapleroot-dev/release-test` (schema `slums_release_test`): release smoke boots, ports 8489 / 7985+.
- `/opt/mapleroot-dev/persistence-test` (schema `mapleroot_persist`): bot boots.
  - `BOT_POPULATION_SCALE` exists for pilots.
  - Unit `slums-persistence-test` was a half-scale soak (1,924 bots: about 1.8 cores and 2.3 GB, 0 errors at start).
    Check it, then stop it.
- The test databases are in container `mapleroot-integration-db` on port 3307; root credentials are in
  `/opt/mapleroot-dev/solomapling-test/secrets/`.
- The old MapleRoot test services are stopped.

## Owner's desktop (Windows, `crims@192.168.0.186`, SSH key on the dev box)

- `Z:\MSv83\SlumsMS-dev`:
  - `client/` builds with VS 2022 via `scripts/build.ps1`, driven by `sync-build.sh` on the dev box.
  - `test-client/` is the single-DLL test client.
  - `launcher/` builds with `npm run build:win`. The friends build has the access key baked in locally; the key is
    never committed.
- `Z:\MSv83\MapleRoot Full Repack\SlumsMS Client` is the verified standalone client (23.6 GB, no junctions).
- Desktop work is read-only outside `SlumsMS-dev` unless the owner says otherwise.

## Version note

The numbers drifted on 2026-10-19:
- server 1.2.1 (live);
- client release on the patch server 1.2.2;
- launcher app 1.2.1.

The next release of anything (the bots) ships as **1.3.0** across server, client and launcher, and from then on they move together.

## Decisions to remember

- Real bots:
  - Hybrid by LOD.
  - Shared economy with per-map yield caps.
  - Server-formula combat; no changes to PacketCreator or AbstractDealDamageHandler (client DLL).
  - Stock classes first.
  - Alts as party-only companions.
- Navigation: merge nutnnut v66 (which has no learning), then add our own learning, nav lab, nightly bake and stuck report.
- Accounts: auto-register stays on, with limits (min password 6, 3 accounts per IP, 10-minute cooldown, LAN exempt).
- Packed first install: deferred.
- Daily check-in comes last.
