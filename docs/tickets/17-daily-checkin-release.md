# 17: Ship Daily Check-In

**Area:** Addons  
**Depends on:** -  
**Status:** in review (server `daily-checkin-2`, client `daily-checkin`)

## What

Server branch `daily-checkin-2` (off test-rc2; migration 004, `DAILY_CHECKIN_ENABLED`) and client branch `daily-checkin`.

- Rewards: NX Credit every day plus items, weekly milestones (7/14/21/28) with bigger NX and +4/+8 inventory slots
  (capped at 96; slots that do not fit pay 500 NX each). The table is a DRAFT for the owner: built into
  `server/checkin/DailyCheckinRewards.java` and mirrored in
  `src/main/java/soloMapling/DailyCheckin/daily-checkin-rewards.yaml`, which the server reads at start (edit and
  restart, no rebuild; an invalid file logs an error and the built-in table is used).
- Bots never get the window, the login push or a claim.
- Window art: in the client's `src/Custom.wz` as `UI/UIWindowEx.img/DailyCheckin/backgrnd`, built on Linux by
  `tools/wz/add_daily_checkin_art.py`.

## Owner steps

1. Approve or edit the reward table (the yaml).
2. Check the 2x EXP coupon (5211046) has an `nxcoupons` row, or it grants an item that does nothing:
   `SELECT * FROM nxcoupons WHERE couponid = 5211046;`
3. Build the client branch on Windows, hash-diff against the standalone client (expected changes: `slums.dll`,
   `Custom.wz`), and check the window on a real client.
4. With the game stopped and a backup taken, apply `database/migrations/004-daily-checkin.sql`, then set
   `DAILY_CHECKIN_ENABLED: true` under `server:` in config.yaml.

## Done when

The window shows the art, claims grant the real rewards, enabled live.
