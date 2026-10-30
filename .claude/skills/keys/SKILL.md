---
name: keys
description: Which SlumsMS piece needs which key (launcher patch key, download pass, dev key, signing key, website sign-in and game secrets, DB, cloud AI, webhooks, dashboard, backup passphrase), what changing each breaks, and how to change one safely (the key console for TEST site keys, a game restart for shared ones, the owner's go for live). Use when the owner asks about keys, secrets, passwords or "does X need a key", when a task needs a new secret, or before changing, adding or moving any key.
---

1. Read `docs/ops/keys.md` (the table of every key: where, used by, what a change breaks, owner) and AGENTS.md rule 3.
   Never read, print, copy, log or send a key, not even to another session. Use paths only. Load an env file
   with `set -a; . file; set +a` in the same command that uses it.
2. **Answering "what needs which key":** answer from `docs/ops/keys.md`, in plain words: what the key lets that piece
   do, live vs TEST/RC, and what breaks if it changes. Say which facts the doc marks as not checked yet.
3. **A task needs a new secret** (a new service, a new game switch):
   - make a separate one for live and for TEST;
   - store it as a file read by path (root 600, or group-readable by the service user);
   - make it from random bytes, or base64 text with `= + /` stripped if it goes in a header;
   - add it to `docs/ops/keys.md` and to `tools/keys/registry.py` (locked unless only TEST site services read it).
4. **Changing a key:**
   - **TEST site keys** (`test-login-key`, `test-ip-salt`, `test-pass-key`, `test-report-salt`): the owner uses the
     key console (`tools/keys/README.md`). Agents don't type the passphrase or set it.
   - **A key the game reads at start** (game sign-in secret, inspect secret): with Server Dev, at a game restart; tell
     the owner first if auto-QA is running.
   - **Live keys:** only with the owner's go for that exact key. Say first who is affected (e.g. "everyone is signed
     out", "old launchers can't download").
   - **Never:** the launcher signing key. The backup passphrase only together with keeping the old one.
5. If a permission check refuses an action on a key or the console, stop and tell the owner (AGENTS.md rule 4).
6. **Report:** which key, when (PT and UTC), why, and what was checked afterwards. Never the key itself.
