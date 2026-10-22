# tools/release

Release pipeline (ticket 44, `docs/tickets/44-release-pipeline.md`). Each script's header has its full usage.

| Step | Script | Where it runs |
|---|---|---|
| 1 | `build` (suite + jar + `BUILD.txt`) | workstation |
| 2 | `stage <version>` (jar, scripts, data, migrations, generated `deploy.sh`/`rollback.sh` on 164) | workstation -> 164 |
| 3 | `rehearse <version>` (fresh copy of live, deploy + rollback, receipt) | workstation -> 164 |
| 3b | `stage-client <client-version> --build-dir 'Z:\...\build\Release'` | workstation: Silvia -> 164 |
| 4 | owner: `release <version>` (server + client in MMO order, see `docs/release/README.md`) | workstation -> 164 |
| 4 (old way) | owner: `bash /opt/mapleroot-deployments/v<version>/deploy.sh`, then `publish-client` | 164, workstation |
| 5 | `publish-launcher` (hash-diff rule built in); `publish-client --restore-manifest FILE` puts a saved manifest back | workstation -> 164 |
| test | `rehearse-release-flow` (the release command on scratch copies, both failure paths) | workstation -> 164 |

## Client hand-off: `stage-client`

Client builds happen on the Windows desktop (Silvia, `crims@192.168.0.186`, key `~/.ssh/id_rsprox_desktop`), but
`publish-client --source` needs a staging tree on 164. `stage-client` makes it, so no client file is copied by hand:

```
tools/release/stage-client 1.4.0 --build-dir 'Z:\MSv83\SlumsMS-dev\client-1.4.0\src\build\Release' [--dry-run]
```

1. On Silvia (read-only): sha256 of `slums.dll` and `Custom.wz` (`--wz` for another Custom.wz) and the git commit,
   branch and dirty state of the clone the build dir is in.
2. Both files are copied here over scp and re-hashed; they must match what Silvia reported.
3. On 164: `/srv/slums-staging/client-<version>/` is filled with a full copy of the client layer players have now
   (the one `manifest.json` points at, or `--from-layer`), then only `slums.dll` and `Custom.wz` are replaced.
   `SlumsMS.exe` and every other file keep the published bytes. An existing non-empty staging dir is refused.
4. `/srv/slums-staging/client-<version>.STAGED.txt` (next to the tree, so it is never published) records the source
   host, paths, commit and the old and new hashes.
5. `publish-client <version> --source /srv/slums-staging/client-<version> --dry-run` runs the HASH-DIFF.

It writes only under `/srv/slums-staging` on 164 (never `/srv/slums-patch`). The real publish stays the owner's go:
`tools/release/publish-client <version> --source /srv/slums-staging/client-<version> --release <release> --news <news.json>`.

## Client version gate: `set-min-client-build` (release hook)

The server refuses real players whose slums.dll build is below `MIN_CLIENT_BUILD` (config.yaml, `server:`; 0 = off).
slums.dll reports its build at login (CP `0x3738 CLIENT_BUILD_STAMP`: short format=1, int build, string release,
sent just before CP_CheckPassword); a client without the stamp (1.4.1 and older) counts as 0. Build numbers come from
the client repo's `VERSION` file: `major*10000 + minor*100 + patch` (1.4.2 -> 10402), also written to
`slums-build.txt` next to the built slums.dll. The refused player sees `CLIENT_UPDATE_MESSAGE` ("Your game is out of
date: open the SlumsMS launcher to update.") in a popup, and the log has
`Client version gate: refused '<account>' ... client build N (no stamp) < minimum M`.

Order for a release with a new client (the MMO order: the client goes out while the server is down):

1. Stop the server (deploy step).
2. `publish-client` the new client (launchers now offer it; Play updates first).
3. In the server apply step, before the start:
   `tools/release/set-min-client-build "$SRV/config.yaml" <new client build>` (idempotent; refuses to lower the
   minimum without `--allow-lower`; keeps CRLF, owner and mode; `--dry-run` and `--show` available).
4. Start the server: from now on older clients are refused with the update message.

Set these optional values in `docs/release/<version>.conf` (or the conf passed to `stage`):

```bash
MIN_CLIENT_BUILD=10500
CHANNELS=5
CHANNEL_LOAD=125
```

`stage` copies `set-min-client-build` and `set-release-config` into `staging/`, includes them in
`SHA256SUMS`, and `gen-deploy` bakes the values into the generated scripts. Unset or empty values leave that
setting alone. `MIN_CLIENT_BUILD` is a non-negative integer; `CHANNELS` and `CHANNEL_LOAD` are positive integers.
The channel editor requires Python 3 (standard library only).

Pre-flight (including `--dry-run`) validates the requested edits and prints before/after values without writing.
In `apply-start`, after the backup and any `CONFIG_HEAD`/`CONFIG_APPEND` edits, while the server is stopped,
the deploy calls the existing gate helper, sets every `worlds[*].channels`, and sets `server.CHANNEL_LOAD`.
Each helper prints before/after values. Repeating the same values is safe. The editors preserve comments,
spacing, CRLF, owner and mode; they replace decimal scalars or insert only these known keys in their owning
mappings. The channel editor accepts block-style world mappings and refuses ambiguous or unsupported structures
rather than reserializing YAML. It never adds a `CHANNELS` key to the strict server config.

Use `tools/release/release` for a combined client/server release: it publishes the client between `stop-backup`
and `apply-start`, so the client is available before the gate takes effect at server start. Standalone `deploy.sh`
does not publish a client; arrange the same publish order yourself when using its phases.

The backup includes both `backup/config.yaml` and config.yaml in `backup/server-runtime.tgz`. Rollback restores
the entire old config with the old jar before starting, including the previous gate, channel counts and load.
Do not set these values with `CONFIG_APPEND`: existing keys would be duplicated. The gate helper continues to
refuse lowering the minimum; rollback restores the backup instead.

## Cash Shop catalogue: `CASHSHOP_CATALOG`

When a release changes `cashshop/catalog.tsv` (made by `tools/cashshop`), set `CASHSHOP_CATALOG=cashshop/catalog.tsv`
in the release conf. A catalogue and a jar must go live together (1.5.0: the partner rings are sold only by a jar that
knows `CashShopPartnerRings`). `stage` copies the file into `staging/` (in `SHA256SUMS`); the deploy pre-checks that
`Server/cashshop/` exists, backs up the old `cashshop/catalog.tsv` in `server-runtime.tgz`, and copies the new one in
the apply step next to the jar. `rollback.sh` puts the old catalogue back with the old jar. Unset: no catalogue change.

Offline tests (fixture config only; no SSH or deploy execution):

```bash
python3 -m unittest discover -s tools/release -p 'test_*.py'
```
