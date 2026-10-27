# 109: TEST on its own LXC, a backup LXC, and a Windows test VM

**Area:** Ops / QA
**Kind:** INFRA, 1.6.1 (owner 2026-10-26)
**Status:** in progress. The owner has made the Windows VM and two LXCs (`mapletest`, `maplebackup`, Ubuntu 24.04, upgrading).
**Related:** 108 (live visibility: the read-only feed key), 106 (launcher download pass, tested on the VM), roadmap
`docs/roadmap/1.6.1.md`.

## Why

Live and TEST share one box (LXC 125 "maplestory", 192.168.0.164: 12 cores, 48 GB). A soak, a full auto-QA run or a
stuck test server takes CPU, memory and disk from live, and one wrong path, container or web-server restart can hit
live. Live has no backups off the box. Client and launcher tests run on the owner's own desktop (Silvia).

## The three machines

### `mapletest` (LXC): TEST moves here; live stays on 164

- Size: 6-8 cores, 24 GB RAM, 150-200 GB disk. Ubuntu 24.04 (same as 164), Docker, OpenJDK 21.
- Holds: the TEST game server (now `/opt/mapleroot-dev/persistence-test`, unit `slums-persistence-test`), its DB
  container (`mapleroot-integration-db`, schema `mapleroot_persist`), the test site stack (slums-login-test,
  slums-market-test, slums-site-test-web on :8192, TEST secrets made fresh there), the TEST staff dashboard (:8091) and
  its map-image service, the social-trace and bot-watch outputs.
- Claude has full access here (it is all TEST).

Move plan:
1. Owner: network (fixed IP via DHCP reservation), root SSH key for 187 (below).
2. Claude: install Docker and Java, restore a dump of `mapleroot_persist` into a new container, copy
   `persistence-test` (jar, scripts, data, cache/bot-nav), start-test.sh and the unit, the site test stack (its
   `tools/site/test164/install.sh`, pointed at the new host), the dashboard. New TEST secrets (auth, login key, IP
   salt), never copied from 164.
3. Repoint: `tools/ops/deploy-test`, `autoqa-run`, `test-status` and the skills (host variable instead of the
   hard-coded 192.168.0.164), the launcher's Test/RC feed if it points at TEST, the site's TEST URLs, Caddy if any
   TEST route goes through it.
4. Run both side by side for a day (the new one on its own ports first), compare a full auto-QA run.
5. Stop TEST on 164, keep its files a week, then remove. 164 keeps only live, its DB and the live web pieces.

### `maplebackup` (LXC): live backups off the box

- Size: 2 cores, 2-4 GB RAM, a few hundred GB disk, ideally on a different Proxmox disk than 164's.
- Holds: nightly live DB dumps (`buns-clean-db-1`, schema `mapleroot`), live `config.yaml`, jars, the patch feed and
  site files. Keep 14 dailies, 8 weeklies, 6 monthlies.
- Push, not pull: live gets a key that can only drop files into its folder on `maplebackup` (forced command, no
  shell, append-only); `maplebackup` cannot log into live, so a compromised live can't delete old backups.
- A monthly restore test loads the newest dump into a throwaway container on `maplebackup` and checks row counts.
- The nightly job on live needs the owner's go (it touches live).

### Windows test VM

- On the TEST side only (it never reaches live). Needs: its IP, OpenSSH Server on with 187's key, auto-logon to a
  test user so a console session exists for the client.
- Day one: install the TEST client and check it renders without a GPU (dgVoodoo2 output set to the D3D11 WARP software
  renderer) at 15-20+ FPS. If it does, it takes client checks off the owner's desktop; if not, it still covers the
  rest.
- Uses: fresh-install and interrupted-download tests (snapshot, test, roll back), Defender on new exes, launcher
  builds and sign-in end to end, ticket 106's download pass, real-client checks (bot attack poses for PQ and boss
  fights), several clients for party/PQ tests.

## History is kept, not deleted (owner 2026-10-27)

The owner wants history kept as history. Rule for every live data source: a short working window stays on live
(7-30 days at full detail; daily summaries forever), and older data is compressed and archived to `maplebackup`
nightly and kept indefinitely instead of deleted:
- nav learning (`cache/bot-nav/players/moves-<day>.jsonl`, today pruned after 30 days / 64 MB) and the `observed/`
  overlays;
- server logs (the journal export, trades.log, gachapon.log, chat logs) before rotation;
- economy snapshots, the dashboard series, and the 1.6.1 live feed past 90 days;
- DB dumps: 14 daily, 8 weekly, 6 monthly, plus one dump per month kept forever.
Private data never goes into the archive in the clear: no passwords or hashes, IPs (only salted hashes), chat text of
real players, secrets.

## Owner checklist (what Claude needs)

1. Each machine's IP (DHCP reservation so it never changes).
2. 187's key in root's `~/.ssh/authorized_keys` on `mapletest` and `maplebackup` (and the VM's OpenSSH admin keys
   file, `C:\ProgramData\ssh\administrators_authorized_keys`, or the test user's):
   `ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPriQtGOH70uMDOgT46Tj77n2wqBuK2P0X3OH4n7zFHP tai-hermes-gestalt-access`
   (the key 187 already uses for 164).
3. On the VM: Settings > Optional features > OpenSSH Server, start the `sshd` service and set it to Automatic, allow
   port 22 in the firewall for the LAN, set auto-logon for the test user.
4. Tell Claude when they're reachable. Everything after that is Claude's, except the backup job on live (owner's go).

## Done when

TEST runs on `mapletest` with a full auto-QA pass and the test site working; 164 runs only live; a night's live backup
lands on `maplebackup` and a restore test passes; the VM runs the TEST client (or is documented as non-rendering) and
does a fresh launcher install.
