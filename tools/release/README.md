# tools/release

Release pipeline (ticket 44, `docs/tickets/44-release-pipeline.md`). Each script's header has its full usage.

| Step | Script | Where it runs |
|---|---|---|
| 1 | `build` (suite + jar + `BUILD.txt`) | workstation |
| 2 | `stage <version>` (jar, scripts, data, migrations, generated `deploy.sh`/`rollback.sh` on 164) | workstation -> 164 |
| 3 | `rehearse <version>` (fresh copy of live, deploy + rollback, receipt) | workstation -> 164 |
| 4 | owner: `bash /opt/mapleroot-deployments/v<version>/deploy.sh` | 164 |
| 4b | `stage-client <client-version> --build-dir 'Z:\...\build\Release'` | workstation: Silvia -> 164 |
| 5 | `publish-client`, `publish-launcher` (hash-diff rule built in) | workstation -> 164 |

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

Hook for the deploy template (kept to one call so the release-flow work can wire it in): in `release.conf`, set
`MIN_CLIENT_BUILD=<build>` (empty = leave config.yaml alone), have `stage` copy `set-min-client-build` into
`staging/`, and in `deploy.body.sh`, after the config.yaml edits and before the start:

```
[ -z "${MIN_CLIENT_BUILD:-}" ] || "$ST/set-min-client-build" "$C" "$MIN_CLIENT_BUILD"
```

Its `--dry-run` belongs in the deploy's dry-run block. Rollback needs nothing extra: `rollback.sh` restores the backed-up
config.yaml, which has the old minimum. Do not set `MIN_CLIENT_BUILD` with `CONFIG_APPEND`: that aborts once the key
exists, and every later release has to raise the value.
