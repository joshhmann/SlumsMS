# SlumsMS server

MapleRoot is the gameplay baseline, with SoloMapling integration, separate classic FM access, the in-field cash shop, and visual weather/time. Arcane remains the main hub. The companion client is [SlumsMS-Client](https://github.com/joshhmann/SlumsMS-Client).

## Build

Install JDK 21. The checked-in Maven wrapper downloads Maven 3.6.3. Create a local configuration:

```sh
cp config.example.yaml config.yaml
./mvnw test package
```

On Windows use `mvnw.cmd`. The complete suite is checked from this repository layout. The inherited registration webhook was moved to the optional private `SLUMSMS_REGISTRATION_WEBHOOK` environment variable; an unset value skips notification without blocking registration. This is a deliberate deployment change from the earlier accepted test JAR. `config.yaml` is ignored by Git. Update its database credentials, host and network settings locally. Never copy test credentials to live.

## Runtime inputs and start

Supply the matching server-side `wz/` XML tree and a compatible MySQL database from your protected deployment assets. These are external inputs, not part of this source repo. Keep server XML and Windows binary IMG files separate. Scripts and the validated 1,839-item cash-shop catalogue are included here.

For an original pre-integration database, review and apply `database/migrations/001-preserve-arcane.sql` then `002-classic-fm-return.sql` with an appropriate database backup. Their ledger/idempotence guards and rollback scripts are retained. An accepted test database already has these migrations; do not apply reverse scripts during normal deployment.

```sh
java -Dcashshop.window.enabled=true -Dweather.enabled=true -Dsolomapling.enabled=false -Dsolomapling.autopopulate=false -Dsolomapling.tutorial-admin=false -jar target/Cosmic.jar
```

Default login port is 8485; default channel base is 7585. Test profiles can override `mapleroot.login-port` and `mapleroot.channel-base-port`. The matched client is mandatory when these addon flags are enabled.

`deploy/slumsms.service.example` is a persistent systemd template. Adapt its paths/user and install it deliberately; it does not replace or restart an existing service. The live rollout now uses persistent `slumsms.service`; see [the live handoff](docs/live-handoff-20261019.md) for the current service, private backup locations and acceptance status.

## Baseline and recovery

Both repos use tag `baseline-20261018-r1`. `release/baseline.json` records flags and accepted binary hashes. The protected release record pins both commit IDs. Source checkout/build verification is recorded separately from manual acceptance of the archived binaries. Builds can differ in ZIP timestamps/debug metadata; server class bytes were compared directly.

Accepted checks cover classic FM routing, selected SoloMapling interactions, ordinary shop purchases/persistence, visual weather, movement keys and character-info mount previews. This is not exhaustive gameplay parity. Weather combat/debuff/slippery-physics changes, the deferred bot drop fix and 64-bit damage work are excluded. No live rollout is implied.

The accepted application checkpoint and private database backup are outside Git. See `docs/DEPLOYMENT.md` for staged deployment and rollback. Existing project licence notices are preserved; server source is under AGPL-3.0 as supplied by its upstream lineage.

## Credits

SlumsMS stands on a long line of open-source MapleStory v83 work. All of it is AGPL-3.0, and so is this server: if you
run a modified copy for others, offer them its source.

- **OdinMS → HeavenMS → [Cosmic](https://github.com/P0nk/Cosmic)** by P0nk and the Cosmic contributors: the server base,
  plus the upstream fixes backported in v1.2.0.
- **MapleRoot**: the custom server this baseline was captured from (64-bit EXP, rebirths, custom skills and content).
- **[SoloMapling](https://github.com/MadaraGameDev/SoloMapling)** by MadaraGameDev, with the bot-persistence work in
  [joshhmann/SoloMapling](https://github.com/joshhmann/SoloMapling): the playerbots.
- **GreenCatMS by [nutnnut](https://github.com/nutnnut/Cosmic)**: the bot movement and physics system, and combat
  targeting, used in SoloMapling.
- **[Kiwiism/cosmic_agents](https://github.com/Kiwiism/cosmic_agents)**: reference for future bot work.
- **Kaentake**: the client injector the SlumsMS client is built from ([SlumsMS-Client](https://github.com/joshhmann/SlumsMS-Client)).

`docs/PROVENANCE.md` records exactly what came from where.
