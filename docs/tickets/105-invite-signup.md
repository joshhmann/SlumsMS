# 105: Invite-code sign-up and account sign-in for the site and launcher

**Area:** Server / Site / Launcher
**Kind:** FEATURE, 1.6.0 (owner 2026-10-26)
**Status:** in progress
**Related:** 104 (launcher login), docs/site/LOGIN.md in SlumsMS-site (`@weblogin`)

Source: the owner, 2026-10-26: "So our main source for login creation is only in the client login screen. Is that
enough? Or should we opt into making accounts so long as you have an invite code", then "Like creating one in the
launcher/website. Thus we can login to the launcher".

## Why

The site goes public with 1.6.0, and today anyone can make accounts from the game's login screen (any number per IP,
1 minute apart). The server should stay friends-only.

## What

1. **Sign up** on the site or in the launcher: invite code + username + password (same rules as the game: length,
   allowed characters, name not taken, not a bot's name). The game server creates the account. Neither the site nor
   the launcher writes the database.
2. **Sign in** on the site or in the launcher with the game username and password, checked by the game server
   (the same hash check as the login screen). The result is the same revocable site session `@weblogin` gives.
   `@weblogin` stays as a fallback.
3. **Login screen auto-register off** once this is live (config flag). Existing accounts log in as before.
4. **Invite codes:** single use, one account each. GMs make them in game (`!invite [note]` prints a code;
   `!invite list` shows codes, who used them and when; `!invite revoke <code>`). Codes expire after 14 days unused.
   Players don't get codes in 1.6.0.

## Shape

- The game server serves a small auth API on **127.0.0.1 only** (TEST and live each their own port), used only by
  the site's login service: `POST /signup {code, user, pass}` and `POST /verify {user, pass}`, answering
  `{ok, account, error}`. A shared secret file (root 600, made on the host, never printed) is checked on every call.
  Rate limits: per IP (passed by the login service) and overall; 5 failed sign-ins per account per 15 min locks
  sign-in for 15 min.
- Passwords travel only over HTTPS on live (Caddy). The launcher never stores a password, only the session token.
- Errors are generic to the user ("wrong username or password", "that code doesn't work").
- Logged: sign-ups (code, account, time, IP hash), failed sign-ins (count only).

## Owners

- Server (game auth API, invite codes, `!invite`, auto-register flag): main session.
- Site pages and login service calls: slumsms-site-0b.
- Launcher sign-up / sign-in screens: src-15 (with 104 level 1).

## Done when

A new friend with a code signs up in the launcher, signs in, and logs into the game with the same username and
password; a used, expired or made-up code is refused; the login screen no longer creates accounts; all of it passes
on TEST before the 1.6.0 release.
