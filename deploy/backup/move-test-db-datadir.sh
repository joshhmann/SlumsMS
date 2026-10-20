#!/bin/bash
# Moves the test DB datadir (ticket 45) out of a folder that looks like an "old test copy":
#   /opt/mapleroot-dev/solomapling-test/mysql  ->  /opt/mapleroot-dev/integration-db/mysql
# The container mapleroot-integration-db (127.0.0.1:3307) bind-mounts it; a container's mounts cannot be changed,
# so the container is recreated with the same image, env, port, memory limit and command, and the old container is
# kept (renamed, stopped) for rollback. The move is a rename on the same ZFS dataset: instant, no copy.
#
# NEEDS A WINDOW: every test server using the test DB is down for a minute or two. Live (3309) is not involved.
#
#   move-test-db-datadir.sh            preflight only (default): checks, prints the plan, changes nothing
#   move-test-db-datadir.sh --go       does it (refuses while slums-* test servers are running)
#   move-test-db-datadir.sh --rollback puts the datadir and the old container back
#   (after a few good days)            docker rm mapleroot-integration-db-old
set -euo pipefail
umask 077

C=mapleroot-integration-db
OLD_C=$C-old
OLD=/opt/mapleroot-dev/solomapling-test/mysql
NEW_PARENT=/opt/mapleroot-dev/integration-db
NEW=$NEW_PARENT/mysql
SECRETS=/opt/mapleroot-dev/solomapling-test/secrets
CNF=$SECRETS/root.cnf
RCPT=${RCPT:-/opt/mapleroot-dev/integration-db-move-$(date -u +%Y%m%dT%H%M%SZ)}
MODE=${1:-preflight}

say() { echo "== $*"; }
die() { echo "ABORT: $*" >&2; exit 1; }
sql() { mysql --defaults-extra-file="$CNF" -h127.0.0.1 -P3307 -N -B "$@"; }
wait_db() {
    for _ in $(seq 1 90); do sql -e "SELECT 1" >/dev/null 2>&1 && return 0; sleep 2; done
    return 1
}
inventory() { # per schema: table count and bytes
    sql -e "SELECT table_schema, COUNT(*), COALESCE(SUM(data_length+index_length),0) FROM information_schema.tables
            WHERE table_schema NOT IN ('mysql','sys','performance_schema','information_schema')
            GROUP BY table_schema ORDER BY 1"
}
consumers() { # test servers that hold connections to the test DB
    systemctl list-units --type=service --state=active --no-legend 'slums-*' 'slumsms-*' | awk '{print $1}' \
        | grep -v '^slumsms\.service$' || true
}

src_of() { docker inspect "$1" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Source}}{{end}}{{end}}'; }

case "$MODE" in
preflight|--go)
    say "preflight"
    docker inspect "$C" >/dev/null || die "no container $C"
    ! docker inspect "$OLD_C" >/dev/null 2>&1 || die "$OLD_C already exists (an earlier move?)"
    cur=$(src_of "$C")
    [ "$cur" = "$OLD" ] || die "$C mounts $cur, expected $OLD"
    [ ! -e "$NEW" ] || die "$NEW already exists"
    [ "$(stat -c %d "$OLD")" = "$(stat -c %d "$(dirname "$NEW_PARENT")")" ] || die "$OLD and $NEW_PARENT are on different filesystems (would need a copy)"
    image=$(docker inspect "$C" --format '{{.Image}}')
    echo "container $C: image $(docker inspect "$C" --format '{{.Config.Image}}') ($image)"
    echo "  port 127.0.0.1:3307->3306, memory $(docker inspect "$C" --format '{{.HostConfig.Memory}}'), swap $(docker inspect "$C" --format '{{.HostConfig.MemorySwap}}')"
    echo "  cmd $(docker inspect "$C" --format '{{json .Config.Cmd}}'), restart policy $(docker inspect "$C" --format '{{.HostConfig.RestartPolicy.Name}}')"
    echo "  datadir $OLD ($(du -sh "$OLD" | cut -f1)) -> $NEW"
    echo "schemas now:"; inventory | sed 's/^/  /'
    users=$(consumers)
    echo "test servers running: ${users:-none}"
    [ "$MODE" = --go ] || { echo; echo "preflight only. In the window: stop the test servers above, then run with --go."; exit 0; }
    [ -z "$users" ] || die "stop these first: $users (persistence-test restarts with /opt/mapleroot-dev/persistence-test/start-test.sh; save the dashboard unit's command first: systemctl cat slums-dashboard-test)"

    mkdir -p "$RCPT"
    inventory > "$RCPT/schemas-before.tsv"
    docker inspect "$C" > "$RCPT/container-before.json"
    say "stop $C"
    docker stop -t 120 "$C"
    say "rename datadir"
    mkdir -p "$NEW_PARENT"
    mv "$OLD" "$NEW"
    docker rename "$C" "$OLD_C"
    say "recreate $C on $NEW"
    envf=$(mktemp)
    trap 'rm -f "$envf"' EXIT
    docker inspect "$OLD_C" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^MYSQL_(ROOT_PASSWORD|DATABASE|USER|PASSWORD)=' > "$envf"
    mapfile -t cmd < <(docker inspect "$OLD_C" --format '{{range .Config.Cmd}}{{println .}}{{end}}' | sed '/^$/d')
    docker run -d --name "$C" \
        --env-file "$envf" \
        -p 127.0.0.1:3307:3306 \
        --memory "$(docker inspect "$OLD_C" --format '{{.HostConfig.Memory}}')" \
        --memory-swap "$(docker inspect "$OLD_C" --format '{{.HostConfig.MemorySwap}}')" \
        -v "$NEW:/var/lib/mysql" \
        -v "$SECRETS:/run/mapleroot-secrets:ro" \
        "$image" "${cmd[@]}"
    wait_db || die "new container does not answer; run --rollback"
    inventory > "$RCPT/schemas-after.tsv"
    if diff "$RCPT/schemas-before.tsv" "$RCPT/schemas-after.tsv"; then
        echo "schemas and table counts identical"
    else
        echo "WARNING: schema inventory differs (sizes can shift slightly after a restart); check the diff above"
    fi
    [ "$(src_of "$C")" = "$NEW" ] || die "new container does not mount $NEW"
    echo
    echo "DONE. Receipt: $RCPT. Old container kept stopped as $OLD_C (remove after a few good days)."
    echo "Restart the test servers (persistence-test: /opt/mapleroot-dev/persistence-test/start-test.sh)."
    echo "Then update docs/ops/lxc125-architecture.md: test DB datadir is $NEW."
    ;;
--rollback)
    say "rollback"
    docker inspect "$OLD_C" >/dev/null || die "no $OLD_C to roll back to"
    [ -d "$NEW" ] && [ ! -e "$OLD" ] || die "expected $NEW present and $OLD absent"
    if docker inspect "$C" >/dev/null 2>&1; then
        [ "$(src_of "$C")" = "$NEW" ] || die "$C does not mount $NEW; not touching it"
        docker stop -t 120 "$C"
        docker rm "$C"
    fi
    mv "$NEW" "$OLD"
    docker rename "$OLD_C" "$C"
    docker start "$C"
    wait_db || die "old container does not answer"
    echo "rolled back: $C on $OLD"
    ;;
*) echo "usage: $0 [--go|--rollback]" >&2; exit 2 ;;
esac
