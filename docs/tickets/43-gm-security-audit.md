# 43: GM account safety and command audit

**Area:** Security / Ops
**Depends on:** -
**Status:** closed 2026-10-25 (done in v1.4.0, branch gm-safety, migration 009).
deleted on 2026-10-20; the owner makes one with `slumsms-gm` (below). Open: the dashboard page and the ticket 42 alert
on GM level changes (both read the tables below); the `@gmauth`/known-IP idea; GM hide (bots still notice a hidden GM,
1.4.1 backlog).

## What

- **Promote/demote by tool, not SQL:** a small script on 164 (`slumsms-gm <account> <level>`) that sets the GM level on
  an account's characters, logs who/when, and refuses bot ids and the template account.
- **Reserved names:** auto-register refuses obvious targets (admin, gm, administrator, owner, staff, mod and lookalikes),
  and character creation refuses GM-looking names, so nobody can pose as staff.
- **Audit log:** every GM command (who, what, target, map, time) to a table and the dashboard; alert on GM level changes
  (ticket 42).
- **Optional hardening:** GM commands only from known IPs or after a per-session `@gmauth` code; GM characters can't
  trade or drop to players unless allowed.

## Done when

The owner can make or remove a GM with one command, a new account named "admin" is refused, and GM actions show up in
the audit log.

## Built (branch `gm-safety`)

- **`tools/gm/slumsms-gm <account> <level> [--note ..] [--force]`, `slumsms-gm --list`** (installed on 164 as
  `/usr/local/bin/slumsms-gm`). Sets `characters.gm` on every character of the account (0 = player, 2-6 GM, 6 = full).
  Refuses bot ids (>= 1000000000), the template account `SMBotBase` (and any account owning `SMTemplate`), promoting a
  banned account, an account with no characters, and a logged-in account (its save would overwrite the change;
  `--force` skips that check). Logs every change to the `gm_level_changes` table (created if missing) and
  `/var/log/slumsms-gm.log` (who = `$SUDO_USER`/`$USER`@host + SSH client IP). DB target: `--container`/`--schema` or
  `SLUMS_DB_CONTAINER`/`SLUMS_DB_SCHEMA`, default live (`buns-clean-db-1`/`mapleroot`); it runs `mysql` inside the
  container with the container's own root password and never reads or prints it.
- **Reserved names** (`client.ReservedNames`): automatic registration refuses admin, gm, administrator, owner, staff,
  mod, moderator, sysop, gamemaster, root, superuser, support, system in any case or lookalike spelling (4dm1n, m0d,
  admln, adrnin, a.d-m_i n, admin01, gm_josh, xXadminXx) with login error 5 ("not a registered ID");
  `Character.canCreateChar` (creation, name checks, name change) refuses names starting with GM (6M, 9M) or containing
  a staff word.
- **Audit** (`client.command.GmAudit`, migration `009-gm-audit`): every command above player rank, and every command a
  GM character runs, goes to the server log and to `gm_command_audit` (UTC time, account, character, GM level,
  command, args, rank, map, channel, IP, outcome ok/denied/error) through a bounded queue and one daemon writer
  thread; a full queue or a missing table drops and counts, never blocks. Tutorial-bot admin grants are audited too.
  Reader: `tools/gm/slumsms-gm-audit [--json] [--changes] [--since ..] [--character ..]`.
- **GM giving rule** (`client.GmGiving`): GM characters cannot trade with non-GMs or drop items/mesos unless the server
  runs with `-Dslumsms.gm.allowGiving=true`.
- Tunables (JVM flags): `-Dslumsms.gmAudit=false`, `-Dslumsms.gmAudit.queue=N`, `-Dslumsms.gm.allowGiving=true`.

Verified on a scratch server (test DB copy, ports 8519/8605+): new accounts admin, ADMIN, 4dm1n, gm_josh, moderator
refused, an ordinary name registered; the script promoted/demoted an account and refused the template account, a bot
id, a banned account and a logged-in one; at level 2 `!whereami` was `ok` and `!setgmlevel` `denied` in
`gm_command_audit`; migration 009 and its rollback applied twice cleanly.
