# SlumsMS <version> release checklist

Copied into `/opt/mapleroot-deployments/v<version>/CHECKLIST.md` by `tools/release/stage`. Tick as you go; anything
left unticked goes into the release doc as "not done". Tools: `tools/release/` in the SlumsMS repo.

## 1. Before staging (workstation)
- [ ] Branches merged into the release branch; `docs/release/<version>.md` written (what is in it, migrations, config
      keys, flags, rollback). Ticket status lines updated.
- [ ] `ServerConstants.SLUMSMS_VERSION = "<version>"` committed.
- [ ] `docs/release/<version>.conf` written from `tools/release/templates/release.conf.example` (migrations, ledger,
      config keys/worlds block, unit edits, pre-checks, smoke lines).
- [ ] `tools/release/build` green: suite count and jar sha in `dist/release-build/BUILD.txt`.
- [ ] Owner's test server (persistence-test, 8489) runs this jar and the owner's in-game check passed.

## 2. Stage and rehearse (agent)
- [ ] `tools/release/stage <version>` -> `/opt/mapleroot-deployments/v<version>/` (staging verified on the host).
- [ ] `tools/release/rehearse <version>` PASS on a fresh copy of live -> `REHEARSAL.md` (timings, 0 errors, player rows
      unchanged after rollback). If the live dump is not available to the agent, rehearse with
      `--source nightly` and say so in the release doc.
- [ ] `bash /opt/mapleroot-deployments/v<version>/deploy.sh --dry-run` against live: every pre-check passes (owner or
      agent; it changes nothing).
- [ ] Expected downtime from the rehearsal written in `DEPLOY-NOTES.md`.

## 3. Deploy (owner)
- [ ] Quiet moment (the dry run prints players online).
- [ ] `bash /opt/mapleroot-deployments/v<version>/deploy.sh` -> `RECEIPT.md` says PASS.
- [ ] In-game check: login screen, characters and items, channel change, bots in Henesys/FM, FM shops.
- [ ] If not: `bash /opt/mapleroot-deployments/v<version>/rollback.sh` (`--dry-run` first).

## 4. Client and launcher (only if they changed)
- [ ] Client staging tree on 164 under `/srv/slums-staging/`, overlay derived from a hash diff, never from addon lists.
- [ ] `tools/release/publish-client <client-version> --source <dir> --dry-run`: HASH-DIFF PASS (reference client and
      the release players have now: 0 unexpected missing/different/extra/removed).
- [ ] `tools/release/publish-client <client-version> --source <dir> --release <version> --news <news.json>`
      (layer first, `manifest.json` last).
- [ ] Launcher: version bumped in `package.json`, built with the access key; `tools/release/publish-launcher <dir>
      --dry-run`, then for real (`launcher.json` last). Try it on `/srv/slums-patch/launcher-test` first.
- [ ] Owner presses Play on the desktop and gets the new version.

## 5. Announce and record
- [ ] News entry (launcher "Latest updates", `--news`; <= 10 entries, title <= 120 chars, body <= 1000).
- [ ] Guide/wiki updated for player-facing changes (commands, maps, bot behaviour).
- [ ] Tags `v<version>` in all three repos: SlumsMS, SlumsMS-Client, slumsms-launcher (same version, even if a repo
      did not change), pushed. Release branch merged to `main`.
- [ ] `docs/release/<version>.md` gets the receipt summary (downtime, jar sha, ledger) and status "LIVE".
- [ ] `docs/ops/lxc125-architecture.md` updated if paths, ports, units or versions changed.
- [ ] Rehearsal scratch left behind: none (`systemctl list-units 'slumsms-rh-*'`, test DB schemas `rh_*`).
