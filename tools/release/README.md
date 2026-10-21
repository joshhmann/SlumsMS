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
