# Keys: what each piece needs

Owner, 2026-10-30. Every key, password and secret the project uses: who uses it, where it lives, what changing it
breaks and who owns it. This file holds **paths only, never a key**. Agents never read, print, copy or send a key
(AGENTS.md rule 3). The key console (`tools/keys/README.md`) shows ages and changes the TEST site's keys without
showing any key.

Not checked yet: where the dev key lives, and how long the shared download key's grace period is. The launcher
session knows both; add them here.

## Launcher and downloads

| Key | Where | Used by | Live / TEST | Changing it | Owner |
|---|---|---|---|---|---|
| Patch download key (user `slums`) | `/root/slums-patch-access-key.txt` on 164; nginx `/etc/nginx/slums-patch.htpasswd` | Built into players' launcher exe (also the "friends" build) to download client and patches from the Live feed (`/srv/slums-patch/`) | Live | Launchers with the old key can't download until friends get a new launcher. It isn't really secret (it can be pulled out of the exe). | launcher session, owner's go |
| Download pass key | `/etc/slums-login/pass-key` (live), `/etc/slums-login-test/pass-key` (TEST) | Website sign-in service signs a per-account pass (12 h); patch nginx checks it (`/api/auth/pass`). Replaces the shared key from 1.6.2 (ticket 106); the shared key works too during a grace period, then retires | Separate live and TEST, so a TEST pass never opens live downloads | Passes stop until the launcher's next sign-in | website session |
| Dev key | not in the docs yet | Test and RC channels (`/launcher-test/`, `/launcher-rc/`); agents' and test builds only, never players' | Test + RC | Test/RC launchers need the new key | launcher session |
| Launcher signing key | `/root/.config/slumsms-launcher/tauri-signing.key` on **187** | Signs each launcher update so installed launchers accept it | Live (and every channel) | **Never change it**: every installed launcher would stop auto-updating and need a reinstall by hand | launcher session |

## Website

Each of these has a live file (`/etc/slums-login/`, `/etc/slums-report/`, `/etc/slums-auth/`) and a separate TEST
file (`/etc/slums-login-test/`, `/etc/slums-report-test/`, `/etc/slums-auth-test/`).

| Key | Used by | Changing it | Owner |
|---|---|---|---|
| Sign-in key (`key`) | Signs every website and launcher sign-in (login and market services) | Everyone is signed out | website session |
| Address salt (`ip-salt`) and report salt (`salt`) | Scramble visitors' addresses so real ones are never stored | Nothing visible; per-address limits start over | website session |
| Game sign-in secret (`/etc/slums-auth*/secret`) | Website ↔ game: sign-ins, sign-ups, invites, password resets, exchange approvals. The game reads it **only when it starts** (`-Dslums.auth.secretFile`) | Website sign-in fails until the game restarts with the new one: change both at once | Server Dev + website |
| Bot inspect secret (`/etc/slums-inspect-test/secret`) | Staff Bots page ↔ game (`-Dslums.inspect.secretFile`); TEST only for now (live would be `/etc/slums-inspect/secret`, owner's go) | Bots page fails until the game restarts with it | Server Dev + website |
| QA account (`/etc/slums-site-test/qa.env`) | Automatic website checks on TEST; that account is GM 3 on TEST since 2026-10-30 | Change the account's password in game, then the file | website session |

## Game server

| Key | Where | Used by | Changing it | Owner |
|---|---|---|---|---|
| Database passwords | Inside each DB container (live `buns-clean-db-1`, TEST `mapleroot-integration-db`) | The game; tools use the container's own variable, never a typed password | The game must get the new one before it restarts | Server Dev, owner's go for live |
| Cloud AI keys | `/root/.config/slumsms/cloud-llm.env` (live), `/etc/slums-persistence-test/cloud-llm.env` (TEST) | Bots' cloud chat | Made on the AI company's site; bots' cloud chat stops until the game loads the new one | Server Dev |

## Monitoring and dashboard

| Key | Where | Used by | Changing it | Owner |
|---|---|---|---|---|
| Discord webhooks | `/etc/slumsms-monitor/live.env` | Live monitor posts | Made in Discord | Server Dev, owner |
| Dashboard password | `/etc/nginx/slums-dashboard-test.htpasswd` | TEST **and live** dashboard logins (one shared file) | The old password stops working for both | owner |

## Backups

| Key | Where | Used by | Changing it | Owner |
|---|---|---|---|---|
| Backup passphrase | `/root/.config/slumsms-backup/offbox.key` on 164 | Encrypts the off-box copies of the nightly backups (off-box push not switched on yet: no destination picked) | **Keep a copy outside 164** (a password manager): if 164 dies, the backups can't be opened without it. Old backups need the old passphrase, so keep every old one too | owner |

## Machine access

- SSH keys on 187 reach 164 as root and reach the owner's desktop (Silvia). Agents use them.
- Anyone logged in to 187 or 164 can reach every key above. The console's passphrase guards the page only. The real
  guard against agents is the rule that agents never read or move keys.

## Changing a key

- **TEST site keys:** the key console (http://192.168.0.164:8791/ once installed; `tools/keys/README.md`).
- **Keys shared with a game:** at a game restart, with Server Dev.
- **Live keys:** only with the owner's go for that key, at a time players won't notice.
- **The launcher signing key:** never.
- **Afterwards:** note the change (date, which key, why) in the session's report. Never record the key itself.
