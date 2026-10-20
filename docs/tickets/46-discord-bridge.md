# 46: Discord bridge and server status

**Area:** Community
**Depends on:** 42 (alerts share the webhook plumbing)
**Status:** open

## What

- Server status in Discord: online/offline, players online (real players only), version, uptime.
- Optional chat bridge for one channel (e.g. megaphones or a `@discord` command) both ways, with the same filters as
  in-game chat; bots stay in character and never post as "bots".
- Release news posted from the launcher news on publish.
- Optional: new-registration notices (`SLUMSMS_REGISTRATION_WEBHOOK`, ticket 27).

## Done when

Friends can see whether the server is up and what's new without opening the game, and the owner controls what is
bridged.
