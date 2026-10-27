# 106: Per-account download pass for the launcher

**Area:** Launcher / Patch hosting / Site login service
**Kind:** FEATURE
**Status:** open, 1.6.1 (owner 2026-10-26)
**Depends on:** 105 (invite sign-up and sign-in) live and settled

Source: the owner, 2026-10-26: "should that also tie into a secret key for the launcher", agreed to 1.6.1 ("Sure").

## Why

The launcher downloads the client and patches with one shared patch key built into the exe. Anyone with the launcher
has it, and it can be pulled out of the exe, so it identifies no one. Once 105 makes the account the real gate, the
download should follow the account too.

## What

- After sign-in, the login service gives the launcher a short-lived, revocable download pass tied to the account.
- Patch hosting accepts the pass instead of the shared key. A banned or revoked account can't download updates.
- The shared key is retired after a grace period, during which both work, so nobody is locked out mid-update.
- New friends sign up (105) first, then download.

## Rules

- No secret inside the launcher exe.
- Live and Test passes are separate.
- The 23 GB first install and resume must keep working: test a full fresh install and an interrupted one on Silvia
  under `Z:\MSv83\SlumsMS-dev\` before release.
- Owner's go before patch hosting changes on live.

## Not in 1.6.0

Changing how the client and patches are delivered on launch night is too risky; 1.6.0 keeps the shared key.
