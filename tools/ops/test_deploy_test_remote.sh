#!/bin/bash
# P0-3: tools/ops/deploy-test-remote.sh (the TEST-host half of deploy-test) against a fixture: a temp BASE, and fake
# systemctl, journalctl, docker, sleep and start-test.sh on PATH. No remote calls.
#   1. the server comes online      -> exit 0, DEPLOYED_COMMIT is the new commit
#   2. the server never says online -> exit non-zero naming the rollback dir, DEPLOYED_COMMIT unchanged
#   3. the unit fails at boot       -> exit non-zero, DEPLOYED_COMMIT unchanged
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fail() { echo "FAIL: $*"; exit 1; }

# run <scenario>: prints "<exit status>" then the script's output; leaves the fixture in $fx
run() {
    fx="$(mktemp -d)"
    mkdir -p "$fx/bin" "$fx/base/Server/target" "$fx/deploy"
    echo old-jar > "$fx/base/Server/target/Cosmic.jar"
    echo new-jar > "$fx/deploy/Cosmic.jar"
    echo oldcommit > "$fx/base/Server/DEPLOYED_COMMIT"
    : > "$fx/deploy/deleted.txt"
    echo "$1" > "$fx/scenario"
    cat > "$fx/bin/systemctl" <<EOF
#!/bin/bash
sc=\$(cat "$fx/scenario")
case "\$1" in
  is-active) [ -f "$fx/started" ] && [ "\$sc" != failed ] && { [ "\${2:-}" = -q ] || echo active; exit 0; }
             [ "\${2:-}" = -q ] || echo failed; exit 3 ;;
  *) exit 0 ;;
esac
EOF
    cat > "$fx/bin/journalctl" <<EOF
#!/bin/bash
[ -f "$fx/started" ] || exit 0
case "\$(cat "$fx/scenario")" in
  online) echo "Nov 06 10:00:00 host java[1]: 10:00:00.000 [main] INFO  server.Server - Cosmic is now online after 2000 ms." ;;
  stalled) echo "Nov 06 10:00:00 host java[1]: 10:00:00.000 [main] INFO  server.Server - SlumsMS v1.6.5 (MapleStory v83) starting up." ;;
  failed) echo "Nov 06 10:00:00 host java[1]: 10:00:00.000 [main] ERROR server.Server - [SEVERE] Boot failed; exiting with status 1" ;;
esac
EOF
    printf '#!/bin/bash\nexit 0\n' > "$fx/bin/docker"
    printf '#!/bin/bash\nexit 0\n' > "$fx/bin/sleep"
    printf '#!/bin/bash\ntouch "%s/started"\n' "$fx" > "$fx/base/start-test.sh"
    chmod +x "$fx/bin/"*
    set +e
    out=$(cd "$fx" && PATH="$fx/bin:$PATH" DEPLOY_TEST_FIXTURE=1 \
        bash "$here/deploy-test-remote.sh" "$fx/deploy" newcommit 0 "$fx/base" slums-fixture fixture_db 2>&1)
    st=$?
    set -e
}

run online
[ "$st" = 0 ] || fail "online: exit $st: $out"
[ "$(cat "$fx/base/Server/DEPLOYED_COMMIT")" = newcommit ] || fail "online: marker not written"
[ "$(cat "$fx/base/Server/target/Cosmic.jar")" = new-jar ] || fail "online: jar not placed"
rm -rf "$fx"

run stalled
[ "$st" != 0 ] || fail "stalled: a server that never came online passed: $out"
[ "$(cat "$fx/base/Server/DEPLOYED_COMMIT")" = oldcommit ] || fail "stalled: marker moved to an unproven commit"
[[ "$out" == *"$fx/deploy"* ]] || fail "stalled: the failure does not name the rollback dir: $out"
rm -rf "$fx"

run failed
[ "$st" != 0 ] || fail "failed: a failed boot passed: $out"
[ "$(cat "$fx/base/Server/DEPLOYED_COMMIT")" = oldcommit ] || fail "failed: marker moved"
rm -rf "$fx"

echo "PASS test_deploy_test_remote.sh (online, stalled, failed boot)"
