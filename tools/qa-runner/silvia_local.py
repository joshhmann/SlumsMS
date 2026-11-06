#!/usr/bin/env python3
"""Play a client-only qa-scripts YAML on the QA box itself, for a DSH worker on Silvia (no ssh, no TEST feed).

    python silvia_local.py <script.yaml> [--runs-root DIR] [--client-mcp DIR] [--dry-run]

The same Run, SilviaWorker and WorkerTransport as tools/qa-runner/run, with the transport in its local mode (the
worker's file queue is read in this process, not over ssh) and a feed that has no server. So a script may only use
client signals and client steps: any server_line wait or check is refused up front, because without the feed it
could never pass and must not look as if it had been judged. The TEST journal slice is pulled afterwards from 187.

Credentials: the script names a vault; the worker's login_credentials tool reads client-mcp\\secrets itself. Nothing
here reads, prints or writes a credential. Outputs go to --runs-root only (default Z:\\MSv83\\SlumsMS-dev\\qa).
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "qa-scripts"))

import qarunner  # noqa: E402
import qascript  # noqa: E402
import yaml  # noqa: E402

DEFAULT_RUNS_ROOT = r"Z:\MSv83\SlumsMS-dev\qa"
DEFAULT_CLIENT_MCP = r"Z:\MSv83\SlumsMS-dev\client-mcp"


class NullFeed:
    """No TEST server on this box: nothing to poll."""

    def poll(self, since, chars, topics, match=None, wait=20):
        return {"ok": True, "lines": [], "next": since}


def server_dependencies(doc):
    """Where a script needs the server feed: step ids and check topics, so the refusal can name them."""
    found = []
    for step in doc.get("steps", []) or []:
        sig = step.get("signal") or {}
        if step.get("do") == "wait" and sig.get("type") == "server_line":
            found.append("wait %s" % step.get("id", "?"))
    for check in (doc.get("expect") or {}).get("checks", []) or []:
        if check.get("check") == "server_line":
            found.append("check server_line %s" % check.get("topic"))
    return found


def build_run(script, runs_root, client_mcp, transport=None, sleep=None, clock=None):
    doc = yaml.safe_load(Path(script).read_text(encoding="utf-8"))
    deps = server_dependencies(doc)
    if deps:
        raise qarunner.RunFailed("this box has no TEST feed; the script needs it for: " + ", ".join(deps), "error")
    transport = transport or qarunner.WorkerTransport(
        target="silvia", wrap=False, root=client_mcp, python=sys.executable)
    kwargs = {}
    if sleep:
        kwargs["sleep"] = sleep
    worker = qarunner.SilviaWorker(transport=transport, **({"sleep": sleep} if sleep else {}),
                                   **({"clock": clock} if clock else {}))
    run = qarunner.Run(script, NullFeed(), worker, runs_root=runs_root, commit="unknown",
                       trigger="dsh", **kwargs)
    run.manifest["runner"]["host"] = "silvia (DSH worker, local queue)"
    run.manifest["server"]["commit_source"] = "unknown: no TEST access on this box; fill in from 187"
    return run


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    script = argv[0]
    runs_root = argv[argv.index("--runs-root") + 1] if "--runs-root" in argv else DEFAULT_RUNS_ROOT
    client_mcp = argv[argv.index("--client-mcp") + 1] if "--client-mcp" in argv else DEFAULT_CLIENT_MCP
    errors, warnings = qascript.validate_file(script)
    if errors:
        print("the script does not validate:\n  " + "\n  ".join(errors))
        return 2
    try:
        run = build_run(script, runs_root, client_mcp)
    except qarunner.RunFailed as error:
        print("refused: %s" % error)
        return 2
    if "--dry-run" in argv:
        print(qarunner.plan(script))
        for w in warnings:
            print("warn: " + w)
        return 0
    manifest = run.run()
    print("%s %s -> %s" % (manifest["result"], run.run_id, run.folder))
    return 0 if manifest["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
