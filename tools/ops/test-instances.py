#!/usr/bin/env python3
"""The TEST instances on mapletest (A today, B for QA long runs, C for the frozen RC): one table, and the generator of
B's and C's start scripts. Plan: docs/ops/test-instances.md. Python 3 stdlib only; touches no host.

  tools/ops/test-instances.py table                 # the instance table
  tools/ops/test-instances.py get <A|B|C> <key>     # one value (deploy-test and test-instance read this)
  tools/ops/test-instances.py render-start <B|C>    # rewrite deploy/test/start-test-<b|c>.sh from deploy/test/start-test.sh
  tools/ops/test-instances.py render-start <B|C> --stdout

B and C are A's start script with only these changed: unit name, folder, ports, -Xms/-Xmx, MemoryMax, CPUQuota, the
inspect/ops secret file paths, the GC log path (so the instances never share A's file), and the switched-off flags
removed (OFF_IN_BC). Every other -D flag stays exactly as A has it.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
A_SCRIPT = os.path.join(ROOT, "deploy", "test", "start-test.sh")

# One row per instance. ram_bytes is MemoryMax in bytes (systemd accepts the number as is).
INSTANCES = {
    "A": dict(unit="slums-persistence-test", folder="/opt/mapleroot-dev/persistence-test",
              login=8489, channel_base=7985, auth=8196, inspect=8198, ops=8199,
              inspect_secret="/etc/slums-inspect-test/secret", ops_secret="/etc/slums-ops-test/secret",
              gc_log="/opt/mapleroot-dev/soak-rc150e/gc/gc.log",
              db_container="mapleroot-integration-db", db_port=3307, db_name="mapleroot_persist",
              heap="12G", memory_max=15032385536, memory_max_text="14G", cpu="600%", population=3000,
              start_script="deploy/test/start-test.sh", secrets_dir="/etc/slums-persistence-test"),
    "B": dict(unit="slums-test-b", folder="/opt/mapleroot-dev/test-b",
              login=8589, channel_base=8085, auth=None, inspect=8298, ops=8299,
              inspect_secret="/etc/slums-test-b/inspect", ops_secret="/etc/slums-test-b/ops",
              gc_log="/opt/mapleroot-dev/test-b/gc/gc.log",
              db_container="slums-test-b-db", db_port=3308, db_name="mapleroot_persist",
              heap="4608M", memory_max=6442450944, memory_max_text="6G", cpu="200%", population=150,
              start_script="deploy/test/start-test-b.sh", secrets_dir="/etc/slums-test-b"),
    "C": dict(unit="slums-test-c", folder="/opt/mapleroot-dev/test-c",
              login=8689, channel_base=8185, auth=None, inspect=8398, ops=8399,
              inspect_secret="/etc/slums-test-c/inspect", ops_secret="/etc/slums-test-c/ops",
              gc_log="/opt/mapleroot-dev/test-c/gc/gc.log",
              db_container="slums-test-c-db", db_port=3310, db_name="mapleroot_persist",
              heap="4608M", memory_max=6442450944, memory_max_text="6G", cpu="200%", population=50,
              start_script="deploy/test/start-test-c.sh", secrets_dir="/etc/slums-test-c"),
}
CHANNELS = 5          # channel ports are channel_base .. channel_base + 4
LIVE_DB_PORT = 3309   # live's DB: no test instance may ever use it

# -D flags that B and C do not run (they are for the TEST website / A's plumbing). Names, exact.
OFF_IN_BC = [
    "slums.site.export", "slums.market.export", "slums.board", "slums.board.inbox", "slums.board.outbox",
    "slums.weblogin.dir", "solomapling.dashboard.dir", "solomapling.reportsFile",
    "slums.auth.port", "slums.auth.secretFile",
]


def ports(name):
    i = INSTANCES[name]
    p = {"login": i["login"], "inspect": i["inspect"], "ops": i["ops"], "db": i["db_port"]}
    if i["auth"]:
        p["auth"] = i["auth"]
    for n in range(CHANNELS):
        p["channel%d" % n] = i["channel_base"] + n
    return p


def get(name, key):
    if name not in INSTANCES:
        raise KeyError("unknown instance %r (A, B or C)" % name)
    v = INSTANCES[name][key]
    return "" if v is None else v


def _sub(text, old, new, count):
    if text.count(old) != count:
        raise SystemExit("render-start: expected %d x %r in deploy/test/start-test.sh, found %d (A's script changed shape)"
                         % (count, old, text.count(old)))
    return text.replace(old, new)


def render_start(name, a_text=None):
    """B's or C's start script text, made from A's by the allowed changes only."""
    if name not in ("B", "C"):
        raise SystemExit("render-start: B or C only (A's script is the source)")
    a, i = INSTANCES["A"], INSTANCES[name]
    t = open(A_SCRIPT).read() if a_text is None else a_text
    t = _sub(t, "--unit=%s " % a["unit"], "--unit=%s " % i["unit"], 1)
    t = _sub(t, "WorkingDirectory=%s/Server" % a["folder"], "WorkingDirectory=%s/Server" % i["folder"], 1)
    t = _sub(t, "-Xms%s -Xmx%s" % (a["heap"], a["heap"]), "-Xms%s -Xmx%s" % (i["heap"], i["heap"]), 1)
    t = _sub(t, "MemoryMax=%d " % a["memory_max"], "MemoryMax=%d " % i["memory_max"], 1)
    t = _sub(t, "CPUQuota=%s " % a["cpu"], "CPUQuota=%s " % i["cpu"], 1)
    t = _sub(t, "file=%s:" % a["gc_log"], "file=%s:" % i["gc_log"], 1)
    for flag, key in (("mapleroot.login-port", "login"), ("mapleroot.channel-base-port", "channel_base"),
                      ("slums.ops.port", "ops"), ("slums.inspect.port", "inspect"),
                      ("slums.ops.secretFile", "ops_secret"), ("slums.inspect.secretFile", "inspect_secret")):
        t = _sub(t, "-D%s=%s " % (flag, a[key]), "-D%s=%s " % (flag, i[key]), 1)
    # Coordinator 9 Nov: no cloud chat on B and C (no key file); their regulars use the fallback replies
    t = _sub(t, "-p EnvironmentFile=-/etc/slums-persistence-test/cloud-llm.env ", "", 1)
    for flag in OFF_IN_BC:
        t, n = re.subn(r" -D%s(?:=\S*)?(?=\s)" % re.escape(flag), "", t)
        if n != 1:
            raise SystemExit("render-start: -D%s found %d times in A's script, expected 1" % (flag, n))
    # A's history comments describe A; B and C get a short generated header instead
    t = "".join(l for l in t.splitlines(True) if not l.startswith("#"))
    head, nl, rest = "#!/bin/bash", "\n", t
    note = ("# GENERATED from deploy/test/start-test.sh by tools/ops/test-instances.py render-start %s: do not edit by hand.\n"
            "# TEST-%s (docs/ops/test-instances.md): unit %s, folder %s, login %d, population %d (config.yaml).\n"
            % (name, name, i["unit"], i["folder"], i["login"], i["population"]))
    return head + nl + note + rest


def table():
    rows = [("", "A", "B", "C"),
            ("unit",) + tuple(INSTANCES[n]["unit"] for n in "ABC"),
            ("folder",) + tuple(INSTANCES[n]["folder"] for n in "ABC"),
            ("login / channels",) + tuple("%d / %d-%d" % (INSTANCES[n]["login"], INSTANCES[n]["channel_base"],
                                                         INSTANCES[n]["channel_base"] + CHANNELS - 1) for n in "ABC"),
            ("auth / inspect / ops",) + tuple("%s / %d / %d" % (INSTANCES[n]["auth"] or "off", INSTANCES[n]["inspect"],
                                                               INSTANCES[n]["ops"]) for n in "ABC"),
            ("DB container : port",) + tuple("%s : %d" % (INSTANCES[n]["db_container"], INSTANCES[n]["db_port"]) for n in "ABC"),
            ("heap / MemoryMax / CPU",) + tuple("%s / %s / %s" % (INSTANCES[n]["heap"], INSTANCES[n]["memory_max_text"],
                                                                  INSTANCES[n]["cpu"]) for n in "ABC"),
            ("population",) + tuple(str(INSTANCES[n]["population"]) for n in "ABC")]
    return "\n".join("| " + " | ".join(r) + " |" for r in rows)


def main(argv):
    if argv and argv[0] == "table":
        print(table())
        return 0
    if len(argv) == 3 and argv[0] == "get":
        try:
            print(get(argv[1], argv[2]))
        except KeyError as e:
            print("test-instances: %s" % e, file=sys.stderr)
            return 2
        return 0
    if len(argv) >= 2 and argv[0] == "render-start":
        name = argv[1]
        out = render_start(name)
        if "--stdout" in argv[2:]:
            sys.stdout.write(out)
        else:
            path = os.path.join(ROOT, INSTANCES[name]["start_script"])
            with open(path, "w") as f:
                f.write(out)
            os.chmod(path, 0o755)
            print("wrote " + os.path.relpath(path, ROOT))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
