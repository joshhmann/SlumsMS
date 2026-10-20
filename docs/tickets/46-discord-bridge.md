# 46: Discord bridge and server status

**Area:** Community
**Depends on:** 42 (alerts share the webhook plumbing)
**Status:** status message and release news built on branch `monitoring` (`slumsms-monitor status|news`, webhooks only, nothing read back), waiting for the owner's webhook URLs and the live install. Registration notices not done.

## What

- Server status in Discord: online/offline, players online (real players only), version, uptime.
- ~~Chat bridge~~: not wanted (owner, 2026-10-20). Webhooks only: status, alerts and news are posted, nothing is read back.
- Release news posted from the launcher news on publish.
- Optional: new-registration notices (`SLUMSMS_REGISTRATION_WEBHOOK`, ticket 27).

## Done when

Friends can see whether the server is up and what's new without opening the game, and the owner controls what is
bridged.
