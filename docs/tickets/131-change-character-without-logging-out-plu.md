# 131: Change character without logging out, plus launcher direct-login

**Area:** client + server (NEEDS SERVER)
**Rank:** 3 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a "Change Character" button returns you to character select in a few seconds, with no password
  re-entry. The launcher can also start the client **straight into a chosen character** (it skips login, world and
  channel select).
- **Evidence:** "[GoBackToLogin] Sending RETURN_TO_CHAR_SELECT widget command...", "Missing relog token",
  "Preparing relog...", "Switching character", "Character switch timed out", widget `extras/changeCharacter`;
  "[LauncherAutoLogin] ... auto_login.dat ... Deleted auto_login.dat after reading", "Ignoring incomplete direct
  character id", "Direct character select dispatched.", "Saved world/channel button ids". `KuroClient.exe`:
  "autologin: launcher credentials read (characterId=%d, channel=%d)".
- **How:** the server gives out a one-time **relog token** and moves the session back to the login server, and the
  client replays login with the token, then selects the character. For direct login, the launcher writes a short-lived
  credential/token file that the DLL reads once and deletes, then drives the native login steps.
- **Size:** M. Client: the login-flow driver (the hard part is waiting until each native stage is ready). Server: token
  issue/consume, return-to-login handler. Launcher: pass token, world, channel and character id. Use a token, never a
  stored password.
