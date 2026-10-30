"""The keys the key console knows (owner, 2026-10-30): where each one lives, what reads it, what changing it breaks.

One list for both halves: the console on 187 shows it, and slums-keys-remote on 164 does only what an entry here allows
(it never takes a path, a command or a value for a generated key from the console, only an id). Nothing here holds a
key; only paths.

Fields:
    id        what the owner types to confirm a change
    env       "test" or "live"
    title     what it is, in plain words
    path      the file on 164
    make      how a new one is made: "bytes48" / "bytes32" (random bytes), "text" (random base64 text, header-safe),
              "htpasswd:<user>" (the owner types the new password; bcrypt), or None (can't be made here)
    mode, owner, group   the file's permissions after a change
    restart   systemd units on 164 to restart after a change, in order
    health    URLs on 164 that must answer 200 afterwards (else the old key is put back)
    breaks    what a change does to people, shown before the owner confirms
    locked    None when the console may change it, else why not (shown instead of the buttons)
"""

LIVE_LOCK = "Live: shown only. It unlocks after the owner has tried the console on TEST and says go."
GAME_LOCK = ("Shared with the TEST game, which reads it only when it starts: a change needs a TEST game restart at the "
             "same moment (it stops auto-QA). Not in this version; ask Server Dev.")

KEYS = [
    # --- TEST: the website's own keys (only the TEST site's services read them) ---
    dict(id="test-login-key", env="test", title="TEST website sign-in key (signs every session cookie)",
         path="/etc/slums-login-test/key", make="bytes48", mode=0o600, owner="root", group="root",
         restart=["slums-login-test", "slums-market-test"],
         health=["http://127.0.0.1:8194/api/login/health"],
         breaks="Everyone on the TEST site is signed out and signs in again.", locked=None),
    dict(id="test-ip-salt", env="test", title="TEST address salt (hashes visitors' addresses for the game's sign-in limits)",
         path="/etc/slums-login-test/ip-salt", make="bytes32", mode=0o600, owner="root", group="root",
         restart=["slums-login-test"], health=["http://127.0.0.1:8194/api/login/health"],
         breaks="Nothing visible. Sign-in try counts per address start over.", locked=None),
    dict(id="test-pass-key", env="test", title="TEST download-pass key (signs the launcher's download passes)",
         path="/etc/slums-login-test/pass-key", make="bytes48", mode=0o600, owner="root", group="root",
         restart=["slums-login-test"], health=["http://127.0.0.1:8194/api/login/health"],
         breaks="TEST launchers' download passes stop working until they get a new one (they do on their next sign-in).",
         locked=None),
    dict(id="test-report-salt", env="test", title="TEST bug-report salt (hashes reporters' addresses)",
         path="/etc/slums-report-test/salt", make="bytes32", mode=0o600, owner="root", group="root",
         restart=["slums-report-test"], health=["http://127.0.0.1:8193/api/report/health"],
         breaks="Nothing visible. The report limits per address start over.", locked=None),
    dict(id="test-dashboard-login", env="test", title="TEST dashboard password (user \"slums\", basic auth)",
         path="/etc/nginx/slums-dashboard-test.htpasswd", make="htpasswd:slums", mode=0o640, owner="root", group="www-data",
         restart=[], health=[],
         breaks="The old password stops working for the TEST dashboard and live's dashboard (they share this file).",
         locked="Live's dashboard uses this file too, so it counts as live: shown only until the owner says go."),
    # --- TEST: shared with the TEST game ---
    dict(id="test-auth-secret", env="test", title="TEST game sign-in API secret (website <-> TEST game)",
         path="/etc/slums-auth-test/secret", make="text", mode=0o600, owner="root", group="root",
         restart=[], health=[], breaks="TEST sign-ins fail until the TEST game restarts with it.", locked=GAME_LOCK),
    dict(id="test-inspect-secret", env="test", title="TEST bot inspect secret (staff Bots page <-> TEST game)",
         path="/etc/slums-inspect-test/secret", make="text", mode=0o600, owner="root", group="root",
         restart=[], health=[], breaks="The staff Bots page fails until the TEST game restarts with it.", locked=GAME_LOCK),
    # --- live: shown, locked ---
    dict(id="live-login-key", env="live", title="Live website sign-in key", path="/etc/slums-login/key", make="bytes48",
         mode=0o640, owner="root", group="slums-login", restart=[], health=[],
         breaks="Every player is signed out of the website and the launcher.", locked=LIVE_LOCK),
    dict(id="live-pass-key", env="live", title="Live download-pass key", path="/etc/slums-login/pass-key", make="bytes48",
         mode=0o640, owner="root", group="slums-login", restart=[], health=[],
         breaks="Launchers' download passes stop until their next sign-in.", locked=LIVE_LOCK),
    dict(id="live-auth-secret", env="live", title="Live game sign-in API secret", path="/etc/slums-auth/secret", make="text",
         mode=0o640, owner="root", group="slums-login", restart=[], health=[],
         breaks="Website sign-in fails until the live game restarts with it.", locked=LIVE_LOCK),
    dict(id="live-report-salt", env="live", title="Live bug-report salt", path="/etc/slums-report/salt", make="bytes32",
         mode=0o640, owner="root", group="root", restart=[], health=[], breaks="Nothing visible.", locked=LIVE_LOCK),
    dict(id="live-patch-key", env="live", title="Patch download key (user \"slums\")", path="/root/slums-patch-access-key.txt",
         make=None, mode=0o600, owner="root", group="root", restart=[], health=[],
         breaks="Launchers with the old key built in can't download patches until friends get a new launcher. "
                "Plan it with the launcher session.", locked=LIVE_LOCK),
    dict(id="live-signing-key", env="live", title="Launcher signing key (Tauri updates)",
         path="187:/root/.config/slumsms-launcher/tauri-signing.key", make=None, mode=0o600, owner="root", group="root",
         restart=[], health=[],
         breaks="Every installed launcher stops auto-updating and must be reinstalled by hand.",
         locked="Never changed from here: a new signing key breaks every installed launcher's updates."),
    dict(id="live-backup-key", env="live", title="Off-box backup passphrase", path="/root/.config/slumsms-backup/offbox.key",
         make=None, mode=0o600, owner="root", group="root", restart=[], health=[],
         breaks="Older backups still need the old passphrase: keep it.",
         locked="Not changed from here: old backups need the old passphrase (deploy/backup/README.md)."),
    dict(id="live-cloud-llm", env="live", title="Cloud AI keys", path="/root/.config/slumsms/cloud-llm.env", make=None,
         mode=0o600, owner="root", group="root", restart=[], health=[],
         breaks="Bots' cloud chat stops until the game reloads them.",
         locked="Made on the AI company's site, not here."),
    dict(id="live-webhooks", env="live", title="Discord webhooks (monitor)", path="/etc/slumsms-monitor/live.env", make=None,
         mode=0o600, owner="root", group="root", restart=[], health=[], breaks="Monitor posts stop until updated.",
         locked="Made in Discord, not here."),
]

BY_ID = {k["id"]: k for k in KEYS}
ID_RE = r"^[a-z0-9-]{3,40}$"


def changeable(key):
    return key is not None and key["locked"] is None and key["env"] == "test" and key["make"] is not None
