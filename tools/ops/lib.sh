# Shared helpers for tools/ops (sourced, not run). Bash only.
#
# TEST lives on its own box since 1.6.3: mapletest = 192.168.0.111 (ssh alias `mapletest` on 187, key
# ~/.ssh/id_slums_lxc). Live, release staging and the patch site stay on 192.168.0.164.
#
# The setting is SLUMS_TEST_HOST (same name as the Website lane's installer): a bare host or IP, default
# 192.168.0.111 (mapletest) since the 1.6.3 TEST cutover, 2026-11-01. Only TEST's two boxes are accepted, and
# the check is testhost.py, the one guard every QA tool shares (tools/ops/client-mcp-win/src/testhost.py): a refused
# value stops the tool here, before it sshes anywhere. 164 is the one host that keeps ssh's default key, so a tool can
# still be pointed at the old shared box by name without asking for the TEST key.
_ops_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_testhost_setting=$(python3 "$_ops_dir/client-mcp-win/src/testhost.py" --shell) || {
    echo "tools/ops/lib.sh: refusing this TEST host (see testhost.py above)" >&2
    return 1
}
read -r TEST_HOST _TEST_HOST_KEY_MODE <<<"$_testhost_setting"
TEST_SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15)
if [ "$_TEST_HOST_KEY_MODE" = test ]; then
    TEST_SSH_OPTS+=(-o IdentitiesOnly=yes -i "$HOME/.ssh/id_slums_lxc")
fi

# test_ssh <remote command...>: one command on the TEST box as root.
test_ssh() { ssh "${TEST_SSH_OPTS[@]}" "root@$TEST_HOST" "$@"; }

# test_scp <scp args...>: copy to or from the TEST box; the destination/source names root@$TEST_HOST.
test_scp() { scp "${TEST_SSH_OPTS[@]}" "$@"; }
