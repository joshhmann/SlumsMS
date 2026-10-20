# 43: GM account safety and command audit

**Area:** Security / Ops
**Depends on:** -
**Status:** open (the stock `admin` account was deleted from live on 2026-10-20; live has no GM until the owner makes one)

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
