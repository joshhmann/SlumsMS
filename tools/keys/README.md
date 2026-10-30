# Key console

Owner, 2026-10-30. A page on 164 that shows every server key (where it lives, how old, what a change breaks) and
changes the TEST ones. Keys themselves are never shown, sent or logged.

- **Page:** http://192.168.0.164:8791/ (home network only; never behind the public website's nginx).
- **Passphrase:** the owner sets it once, themself: `ssh -t root@192.168.0.164 slums-keys passphrase`. Only an
  scrypt hash is kept (`/root/.config/slums-keys/owner`, root 600). No agent sets, reads or types it.
- **A change** needs the passphrase and the key's id typed in. Five wrong passphrases lock changes for 15 minutes.
- **What happens:** `slums-keys-remote rotate <id>` backs the file up (`<file>.bak-<UTC>`, newest 5 kept), writes a
  new random key with the right permissions, restarts the services that read it and checks their health URLs. If a
  check fails, it puts the old key back and restarts again. "Put the previous one back" restores the newest backup.
  Every action goes to `/var/log/slums-keys.log` (time, id, action, result; never a key).

## Which keys

`registry.py` is the list, for both the page and the 164 script; the script only accepts an id from it.

| Can change now | Shown, locked |
|---|---|
| `test-login-key`, `test-ip-salt`, `test-pass-key`, `test-report-salt` (the TEST site's own keys) | TEST keys shared with the TEST game (`test-auth-secret`, `test-inspect-secret`): the game reads them only at start, so a change needs a TEST game restart at the same time; to add with Server Dev |
| | `test-dashboard-login`: live's dashboard uses the same file |
| | every live key, until the owner has tried TEST and says go |
| | never here: the launcher signing key (breaks every installed launcher's updates), the backup passphrase (old backups need it), cloud AI keys and Discord webhooks (made on those sites) |

## Install, test

    tools/keys/install.sh                 # copies to /usr/local/lib/slums-keys on 164, starts slums-keys.service
    tools/keys/install.sh --remove
    python3 -m unittest discover -s tools/keys -p 'test_*.py'
