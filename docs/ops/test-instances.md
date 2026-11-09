# TEST-A, TEST-B and TEST-C (all on mapletest, 192.168.0.111)

Plan: /root/work/agent-runs/serverdev/plans/test-instances-b-c.md (approved by the Coordinator, 9 Nov 2026).
Nothing here touches live (164). TEST-A is today's TEST server and stays the default for every tool.

## Which instance for what

| | A (today) | B (QA long runs) | C (frozen RC) |
|---|---|---|---|
| use it for | every new build; the owner plays here | the build QA asks for; auto-QA long runs | release-proof of the pinned release candidate |
| unit | slums-persistence-test | slums-test-b | slums-test-c |
| folder | /opt/mapleroot-dev/persistence-test | /opt/mapleroot-dev/test-b | /opt/mapleroot-dev/test-c |
| login / channels | 8489 / 7985-7989 | 8589 / 8085-8089 | 8689 / 8185-8189 |
| auth / inspect / ops | 8196 / 8198 / 8199 | off / 8298 / 8299 | off / 8398 / 8399 |
| secrets | /etc/slums-*-test/ | /etc/slums-test-b/{inspect,ops,db.env} | /etc/slums-test-c/{inspect,ops,db.env} |
| DB container : port | mapleroot-integration-db : 3307 | slums-test-b-db : 3308 | slums-test-c-db : 3310 (never 3309, that is live's) |
| DB name | mapleroot_persist | mapleroot_persist | mapleroot_persist |
| heap / MemoryMax / CPU | 12G / 14G / 600% | 4608M / 6G / 200% | 4608M / 6G / 200% |
| population (BOT_ROSTER_PEAK_ONLINE) | 3000 | 150 | 50 |
| builds | every new build | any commit you pass | only the commit in deploy/test/test-c.pin, unless --force |

B and C run without the TEST website's plumbing: slums.site.export, slums.market.export, slums.board (and its
inbox/outbox dirs), slums.weblogin.dir, solomapling.dashboard.dir, solomapling.reportsFile, slums.auth.port and
slums.auth.secretFile are all switched off. Every other -D flag is exactly A's (deploy/test/start-test.sh).
One more path differs: the GC log goes to the instance's own folder so it never shares A's file.

Separate data: B and C each have their own MySQL 8.4.9 container, loaded from a `mysqldump --single-transaction` of A.
A is only ever read by that dump.

## Start scripts

deploy/test/start-test-b.sh and start-test-c.sh are generated from deploy/test/start-test.sh. Never edit them by hand:

    python3 tools/ops/test-instances.py render-start B     # rewrites deploy/test/start-test-b.sh
    python3 tools/ops/test-instances.py render-start C
    python3 tools/ops/test-instances.py table              # the table above, from the code

A test fails if a committed script differs from what the generator makes, if the -D flags of B/C differ from A's
anywhere but the list above, or if two instances share a port.

## Deploying a build

    tools/ops/deploy-test                          # A, exactly as before
    tools/ops/deploy-test --instance B [--commit <sha>]
    tools/ops/deploy-test --instance C             # refuses any commit but the one in deploy/test/test-c.pin
    tools/ops/deploy-test --instance C --force     # the owner/Coordinator's call only

One call, one target: it restarts only that instance's unit, applies migrations only to that instance's DB and checks
only that instance's boot. An unknown instance is refused. `--dry-run` shows what would happen.
`tools/ops/flag-parity` is checked per instance (`--instance B|C` reads the B/C-only section of
docs/release/flag-parity.allow).

## Setting up and refreshing (host steps)

    tools/ops/test-instance setup B --dry-run      # prints every command
    tools/ops/test-instance setup B                # runs them (ask first)
    tools/ops/test-instance refresh-db B|C [--dry-run]

`setup` copies A's Server folder with wz/ hard-linked, starts the instance's DB container (mysql:8.4.9) on its port,
loads it from a dump of A, creates the secrets (0600, from /dev/urandom, never printed), and sets config.yaml's DB
port, DB user/password and BOT_ROSTER_PEAK_ONLINE. `refresh-db` stops that instance, reloads its DB from a fresh dump
of A and starts it again. Neither ever stops, restarts or writes to A.

## Clients

Point the client's config.ini at 8589 (B) or 8689 (C). QA tools must allow those ports per run; never 8485 (live).
