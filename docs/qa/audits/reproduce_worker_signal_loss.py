"""Read-only audit reproduction for ticket 179. No network or game client required."""

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
    assert second is None, "Defect no longer reproduces: review ticket 179 and this audit script."
    assert "channels.ready" in seen
    print(json.dumps({"first_wait": first["name"], "second_wait": second,
                      "recorded_events": seen, "defect_reproduced": True}, indent=2))


if __name__ == "__main__":
    main()
