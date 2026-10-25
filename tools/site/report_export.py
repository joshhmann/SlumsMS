#!/usr/bin/env python3
"""Print reports newer than an id as JSON lines (for copying onto the tracker). Standard library only.

    python3 tools/site/report_export.py [--file reports.jsonl] [--since N]

Prints one JSON object per line, oldest first, without the hashed IP. Prints nothing if there is nothing new.
Over ssh:  ssh <host> python3 /opt/slums-report/report_export.py --since 12
"""
import argparse
import json
import sys

DEFAULT = "/var/lib/slums-feedback/reports.jsonl"


def export(path, since=0):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                rec = json.loads(line)
                if int(rec["id"]) > since:
                    rec.pop("ip_hash", None)
                    rows.append(rec)
            except (ValueError, KeyError, TypeError):
                continue
    return sorted(rows, key=lambda r: r["id"])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", default=DEFAULT)
    ap.add_argument("--since", type=int, default=0, help="only reports with an id above this")
    args = ap.parse_args(argv)
    try:
        rows = export(args.file, args.since)
    except FileNotFoundError:
        rows = []
    for r in rows:
        print(json.dumps(r, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
