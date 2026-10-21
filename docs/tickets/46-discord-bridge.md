# 46: Discord bridge and server status

**Area:** Community
**Depends on:** 42 (alerts share the webhook plumbing)
**Status:** status message and release news shipped in v1.4.0 (branch `monitoring`, `slumsms-monitor status|news`,
webhooks only, nothing read back); `slumsms-status@live.timer` runs on live. Registration notices not done.

## What

- Server status in Discord: online/offline, players online (real players only), version, uptime.
- ~~Chat bridge~~: not wanted (owner, 2026-10-20). Webhooks only: status, alerts and news are posted, nothing is read back.
- Release news posted from the launcher news on publish.
- Optional: new-registration notices (`SLUMSMS_REGISTRATION_WEBHOOK`, ticket 27).

## Done when

Friends can see whether the server is up and what's new without opening the game, and the owner controls what is
bridged.

## Public announcements (branch `public-announce`, 2026-10-20)

The owner's only webhook is the public player channel (set as `NEWS_WEBHOOK_URL`), so it must stay quiet: only
player-facing posts. New kind `ANNOUNCE` (`ANNOUNCE_WEBHOOK_URL`, else `NEWS_WEBHOOK_URL`, never `WEBHOOK_URL`, which
becomes the private ops channel). Posts are in Cody's voice (NPC 9200000), webhook username `Cody`, with a shared rate
cap (6 an hour, 2 min apart; maintenance skips the gap). Commands `announce`, `maintenance start|end` (wired into the
ticket 44 deploy/rollback templates, soft) and `announce-feed` (world events from ticket 21's
`-Dslumsms.announceFile` feed, `install.sh --announce`). Details: `deploy/monitor/README.md`, "Public announcements".
