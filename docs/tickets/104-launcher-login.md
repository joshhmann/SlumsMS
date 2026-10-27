# 104: Launcher login: explore signing in once and auto-logging into the game

**Area:** Launcher / Client / Server / Site
**Kind:** EXPLORE (a write-up and a recommendation, no code yet)
**Effort:** S for the exploration; the build itself is M-L across three tracks
**Depends on:** the site sign-in (`@weblogin`, docs/site/LOGIN.md in SlumsMS-site), which ships with 1.6.0
**Status:** in progress. Owner 2026-10-26: level 1 pulled into 1.6.0 (launcher session src-15, against the site login service); level 3 later, only if typing the passphrase still bothers people.

Source: the owner, 2026-10-26: "Should launcher be a login too? Which can auto login a user", then "Wanna add a
ticket to that launcher login? Just to explore if it makes sense".

## The question

Should the launcher sign a player in, and then put them straight into the game without the game's login screen?
Find out whether it's worth it, what it would take, and what the safe version looks like. Decide nothing; bring the
owner a recommendation.

## Levels to weigh

1. **The launcher knows who you are** (no auto-login). The launcher signs in with the same `@weblogin` code as the
   website, or opens the website sign-in, and shows "Signed in as Wolf4life". It could also show your Slums Market
   watchlist alerts or World news. Launcher work plus the existing login service. No client or server changes.
2. **The launcher fills in the username only.** It remembers the account name and passes it to the client, so the
   player types only their passphrase. That needs a small client hook. The launcher stores no password.
3. **Real auto-login.** You're signed in once, press Play and land on character select. Safe shape to evaluate:
   - The launcher holds a revocable sign-in token, not a password.
   - At Play it asks the server for a **one-time game pass**: short-lived (about 60 s), single use, tied to the
     account.
   - The launcher hands the pass to the client, via the command line or a file it deletes after reading.
   - `slums.dll` puts the pass in the login packet in place of the password.
   - The login server accepts a valid pass once and logs it.

   This touches the server (issuing and checking passes), the client (a login hook) and the launcher (token storage
   and the pass handoff).

## Rules for any version

- **Never store game passwords** in the launcher, its config or Windows Credential Manager. That shortcut gets
  accounts stolen from a compromised PC.
- **Revocation:** a ban, or a "sign out everywhere" (`revoked.txt` in the login service), must kill launcher tokens
  too.
- **The normal login screen keeps working.** Auto-login is optional and can be switched off in `config.ini` like the
  other client patches.
- **Clear separation between Live and RC/Test:** a Live launcher's pass only works on live, and an RC launcher's only
  on the test server.
- Owner's go before anything touches live (AGENTS.md rule 1). The Live patch feed needs the owner too (rule 6).

## What to find out

- [ ] What the v83 login packet carries and where `slums.dll` could swap the password for a pass. Read the stock
      bytes from the exe copy (AGENTS.md section 4). Does the client have a `-user`/`-pass` style start option, or
      does it need a hook?
- [ ] Where the server checks logins (the Cosmic login handler), and how a one-time pass would fit beside the
      passphrase check and auto-register.
- [ ] How the Tauri launcher would keep a token safely on Windows. Check what launcher 2.0 already stores.
- [ ] Whether level 1 or 2 alone gets most of the benefit for 5 to 15 friends, given that typing a passphrase is a
      few seconds.
- [ ] Rough effort per track, and the real-client tests it would need on Silvia (a copy under
      `Z:\MSv83\SlumsMS-dev\`, never the owner's installs).

## Done when

A short write-up in this ticket covers:
- each level's effort per track;
- the risks;
- a recommendation (for example "do level 1 now, level 3 later" or "not worth it").

The owner picks a level or drops the idea.
