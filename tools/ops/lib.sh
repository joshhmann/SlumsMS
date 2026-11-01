# Shared helpers for tools/ops (sourced, not run). Bash only.
#
# TEST lives on its own box since 1.6.3: mapletest = 192.168.0.111 (ssh alias `mapletest` on 187, key
# ~/.ssh/id_slums_lxc). Live, release staging and the patch site stay on 192.168.0.164.
#
# The setting is SLUMS_TEST_HOST (same name as the Website lane's installer): a bare host name or IP, default
# 192.168.0.164 until the 1.6.3 TEST cutover (o03 step 3), then 192.168.0.111. 164 is the one host that keeps ssh's default key, so a tool can still be pointed at the old
# shared box by name without asking for the TEST key.
# Until the 1.6.3 TEST cutover (o03 step 3) the played TEST is still on 164: flip this default to 192.168.0.111 then.
TEST_HOST=${SLUMS_TEST_HOST:-192.168.0.164}
TEST_SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15)
if [ "$TEST_HOST" != 192.168.0.164 ]; then
    TEST_SSH_OPTS+=(-o IdentitiesOnly=yes -i "$HOME/.ssh/id_slums_lxc")
fi

# test_ssh <remote command...>: one command on the TEST box as root.
test_ssh() { ssh "${TEST_SSH_OPTS[@]}" "root@$TEST_HOST" "$@"; }

# test_scp <scp args...>: copy to or from the TEST box; the destination/source names root@$TEST_HOST.
test_scp() { scp "${TEST_SSH_OPTS[@]}" "$@"; }
