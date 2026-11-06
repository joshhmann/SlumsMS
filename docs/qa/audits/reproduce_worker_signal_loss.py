"""Ticket 179 / b286 check, adapted after the fix (170-qa-events): both waits must now succeed.
No network or game client required. Before the fix the second wait timed out."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools" / "qa-runner"))
from qarunner import SilviaWorker


class Transport:
    def __init__(self):
        self.polls = 0

    def call(self, name, arguments=None):
        assert name == "qa_events", name
        self.polls += 1
        events = ([{"ev": "worldselect.ready"}, {"ev": "channels.ready"}]
                  if self.polls == 1 else [])
        return {"text": [json.dumps({"next": 2, "events": events})]}


def main():
    now = [0.0]

    def sleep(seconds):
        now[0] += seconds

    worker = SilviaWorker(Transport(), clock=lambda: now[0], sleep=sleep)
    first = worker.wait_signal("me", "worldselect.ready", 1, {})
    second = worker.wait_signal("me", "channels.ready", 1, {})
    seen = [event["ev"] for event in worker.seen["me"]]
    assert first and first["name"] == "worldselect.ready"
    assert second and second["name"] == "channels.ready", "ticket 179 is back: a batched signal was lost"
    assert "channels.ready" in seen
    print(json.dumps({"first_wait": first["name"], "second_wait": second["name"],
                      "recorded_events": seen, "defect_reproduced": False}, indent=2))


if __name__ == "__main__":
    main()
