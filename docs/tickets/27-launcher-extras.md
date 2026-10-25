# 27: Launcher extras

**Area:** Launcher  
**Depends on:** -  
**Status:** closed 2026-10-25 (obsolete: Electron launcher replaced by the Tauri launcher in 1.5.2; optional items belong in a launcher backlog).

## What

**Self-update (done, 2026-10-20):** the owner ran 1.3.0 -> 1.3.1 on Windows from the private `launcher-test/` channel
on the patch server. It downloaded only `app.asar`, swapped and restarted in under 2 s, then found and verified the
existing game install. Neither Defender nor SmartScreen reacted to the unsigned exe. `launcher-test/` stays as a staging
channel.

Original spec:
- Today the launcher updates game files from the patch-server manifest, but the launcher app itself never updates;
  a launcher fix means handing friends a new zip.
- On start, the launcher checks the patch server for a newer launcher build (e.g. a `launcher` entry in
  `manifest.json` with version, URL and sha256), downloads it behind the same access key, verifies the hash, swaps
  itself and restarts.
- A failed or partial download leaves the current launcher running; never brick the install.
- The zip handed to friends with 1.3.0 is the last one anyone downloads by hand.

Optional items from the launcher grill:
- server online/offline status in the launcher (a small status endpoint);
- a 'Source code' link in the launcher UI (today it is only in the release news);
- Discord webhook for new registrations (`SLUMSMS_REGISTRATION_WEBHOOK` env on 164) if the owner wants alerts.

## Done when

- A 1.3.0 launcher, given a newer launcher build on the patch server, updates itself and restarts without the owner
  sending anything.
- Whatever else the owner picks is shipped in a launcher release.
