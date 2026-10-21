# Deployment and rollback

The tagged baseline has been deployed. See [the live handoff](live-handoff-20261019.md) for the service, backups, accepted validation and Parsec startup workaround.

1. Resolve matching server and client tags and verify the release manifest hashes. Use fresh build outputs only after testing that matched pair; the archived accepted artifacts remain the recovery reference.
2. Stage in a new directory, not in the running server root. Include target/Cosmic.jar, scripts/, cashshop/, full matching server wz/, and a private config.yaml. Verify database and host/port settings explicitly. A fresh service account needs access to those paths and its log directory.
3. Back up the live database with a method covering its InnoDB and MyISAM tables (the tested backup used table locks), runtime/config/scripts/WZ and service definition. Keep the backup private and verify it can be read. Do not restore the disposable test database over live players.
4. Stop the game service in an agreed maintenance window before applying database map migrations. Preserve Arcane data and use the migration ledger. Deploy the paired client assets and source-compatible DLLs together.
5. Start the persistent service using the example after adapting paths/user. Smoke-test login, character entry, FM/Arcane routing, shop, weather and channel changes. Check logs and normal client exit.
6. On failure, stop the new service, restore the previous runtime and matched client, and restore the database backup or use reviewed reverse migrations in reverse order (002 then 001). Do not mix JAR/DLL/asset generations. Restore the saved original service definition deliberately.

Source excludes config.yaml, database dumps, binaries produced by builds and server WZ XML. Runtime packaging tooling verifies a supplied asset tree and emits a manifest. It does not deploy or migrate a running server.

Registration Discord notifications are optional. Supply SLUMSMS_REGISTRATION_WEBHOOK privately if needed; it defaults off. Never commit the endpoint into source or service examples.

## Releases from 1.4.2: one command

`tools/release/release <version>` (workstation) pairs the server deploy and the client publish in MMO order:
pre-flight (changes nothing) -> maintenance on + "going down" post, stop, backup -> client publish while the server
is down -> migrations/config/install, start, smoke -> maintenance off + one "back up, press Play to update" post.
A failure before the publish restarts the old version; a failed publish restores the previous `manifest.json` and
restarts the old version; a failure after the publish restores the previous `manifest.json` first and then runs
`rollback.sh`. Players never get a new client with an old server. Guide, failure table and rehearsal:
[docs/release/README.md](release/README.md).
