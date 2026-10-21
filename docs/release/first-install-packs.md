# First-install packs (ticket 20): release steps

Launcher branch `ops-first-install` (launcher 1.3.3, on top of the unreleased 1.3.2 `installed-vs-available`).
Nothing below has been run on the patch server. Every step is the owner's go.

## What ships

- **Publisher:** `node tools/publish.mjs --packs NAME@VERSION --out DIR` builds
  `layers/NAME/VERSION/packs/{0001.pack ... index.json}` from an already published layer.
  - Each file is re-hashed against the layer manifest while packing.
  - The output is deterministic, about 256 MiB per pack, uncompressed, and stores each distinct content once.
  - `packs/` is written in one rename and is immutable.
  - The layer's `files/` and `manifest.json` are not touched.
  - The next `--release` adds `packs: { url, sha256 }` to that layer's reference on its own.
- **Launcher 1.3.3:**
  - A first install (or any download of 32 files or more) fetches the pinned pack index. It uses whole packs, or ranges of a pack, where that is cheaper than one request per file. Small updates stay per-file.
  - Every file is still SHA-256 verified against the layer manifest before it is moved into place.
  - A dropped or stalled connection (no data for 60 s) resumes at the exact byte with HTTP Range.
  - A damaged or missing pack falls back to per-file downloads.
  - Free disk space is checked before the download starts.
  - Progress shows speed and time left.
- **Older launchers** (1.3.0-1.3.2) ignore the `packs` field and keep downloading per file.

## Sizes (from the published manifests, read-only)

| | files | bytes | packs | pack index |
|---|---|---|---|---|
| base `v83-2026.10.19` | 85,865 (68,380 distinct) | 23.56 GB (23.27 GB distinct) | 91 (39-312 MiB) | 401 KB |
| client `1.4.0` | 1,274 | 7.61 GB | 31 | 9 KB |

The client layer repeats 1,195 base paths (7.56 GB), and 1,166 of those files are byte-identical to base. A fresh install
plans as follows:

- **Base packs only:** 90 packs plus 104 single files, 23.32 GB to download.
- **Base and client packs:** 96 packs plus 2 single files, the same 23.32 GB.

Client packs therefore save only about 100 requests, and they cost 7.6 GB of server disk for every client release.
**Recommendation: pack the base layer only.** Client packs are optional (step 3).

Server disk: base packs add about 23.3 GB. On 2026-10-21, `/` on 164 had 281 GB free.

## What a remote friend sees (launcher 1.3.3)

Download about 23.3 GB, bounded by the slower of the friend's download and the owner's upload:

| effective link | time |
|---|---|
| 50 Mbps | about 65 min |
| 100 Mbps | about 32 min |
| 300 Mbps | about 11 min |

If several friends install at once, they share the owner's upload.

Local benchmark: a 3,000-file sample of the real base size mix, through a server with fixed latency and a bandwidth cap.

| cap / RTT | per-file | packs |
|---|---|---|
| 50 Mbps / 80 ms | 104.5 s (3,003 requests) | 100.7 s (6 requests) |
| 100 Mbps / 40 ms | 52.5 s | 50.4 s |
| 300 Mbps / 150 ms | 46.6 s (request-bound) | 17.5 s |

On a slow link the first install is bandwidth-bound either way. Packs mainly help in three ways:

- They cut 86k requests to about 100, so there is far less load on Caddy and nginx.
- A fast or distant link stays bandwidth-bound instead of request-bound.
- The install survives drops: it resumes mid-pack, and closing the launcher keeps every finished file.

## Release steps (owner's go)

On 164, as root, with `N=/opt/slums-tools/node/bin/node`.

1. **Update the publisher tools.** Keep the old copy, then copy in the new `src/` and `tools/` from launcher
   `ops-first-install`. The tests are optional and need the repo's dev dependencies.

   ```sh
   cp -a /opt/slums-tools/launcher /opt/slums-tools/launcher.bak-$(date +%Y%m%d)
   # from this box: rsync -a /root/work/launcher/{src,tools,package.json} root@192.168.0.164:/opt/slums-tools/launcher/
   ```

2. **Build the base packs.** This is additive and unreferenced until step 4. It takes about 5-15 minutes and holds the publish lock.

   ```sh
   cd /opt/slums-tools/launcher
   $N tools/publish.mjs --packs base@v83-2026.10.19 --out /srv/slums-patch
   # prints 91 packs and the index SHA-256
   ```

3. **Optional: client packs**, for the client layer version the release will use. Skip by default (see Sizes).

   ```sh
   $N tools/publish.mjs --packs client@<CLIENT_VERSION> --out /srv/slums-patch
   ```

4. **Check range support through Caddy.** The owner runs this, because it needs the access key. Expect `206 100`:

   ```sh
   curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -u "slums:$(cat /root/slums-patch-access-key.txt)" \
     -r 0-99 https://slumspatch.asslorde.com/layers/base/v83-2026.10.19/packs/0001.pack
   ```

   `200 <full size>` means ranges are not passed through. That still works, but a dropped connection then restarts
   the whole pack.
5. **Reference the packs.** With the next release manifest (for example 1.4.1 or 1.4.2), the output should say
   `base@v83-2026.10.19 (packs)`:

   ```sh
   $N tools/publish.mjs --release <RELEASE> --out /srv/slums-patch \
     --use base@v83-2026.10.19 --use client@<CLIENT_VERSION> --news /srv/slums-staging/news-<RELEASE>.json
   ```

6. **Build launcher 1.3.3 with the access key baked in.** This is the owner's step, the same way as 1.3.0:
   - Build on Silvia from `launcher-firstinstall` with the key in `src/launcher.config.json`, then run `npm run build:win`.
   - Copy `dist/win-unpacked` to `/srv/slums-staging/launcher-1.3.3/`.
   - Publish the launcher channel. This is the first `launcher.json` on the live feed:

   ```sh
   $N tools/publish.mjs --launcher /srv/slums-staging/launcher-1.3.3 --out /srv/slums-patch
   ```

   Running launchers (1.3.0+) update themselves on their next start. New friends get the
   `SlumsMS-Launcher-1.3.3-win-x64.zip` (with the key) as their download.
7. **Smoke test.** On a PC that has never installed the game, use the 1.3.3 zip and an empty folder:
   - Install, and expect speed and time left in the progress line.
   - Close the launcher mid-install, start it again, and press Install. It should continue rather than start over.
   - Play.

**Rollback:**
- Publish a new release manifest with `--no-packs`. Every launcher goes back to per-file downloads.
- `packs/` can stay on disk, or be deleted once nothing references it.
- Launchers never downgrade. A bad 1.3.3 is fixed by publishing a 1.3.4.

## Risks and limits

- **Range through Caddy is unverified.** It needs the access key (step 4). Without ranges, installs still work, but a resume costs up to one pack (about 256 MiB).
- **A half-downloaded file restarts from its first byte after a launcher restart.** Within one session it resumes mid-file. The worst case is the largest base file, 327 MB.
- **Windows Defender scans each of the 86k written files.** This can make the friend's disk the bottleneck on slow PCs. An optional exclusion prompt (from the ticket) is not implemented, because it needs elevation. Manual tip: add the game folder to Defender exclusions before installing.
- **The client layer re-ships 7.5 GB of base-identical Data/Map and Data/Sound files every release.** Players do not re-download them (hash match), but each client release costs 7 GB on the server. Trimming the client layer to real overrides is a separate cleanup.
- **The disk-space check needs about 23.8 GB free** (23.6 GB plus a 512 MB margin) for a fresh install, and the game folder itself is 23.6 GB.
