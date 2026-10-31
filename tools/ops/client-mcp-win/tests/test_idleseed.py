"""b023: the owner-idle clock across worker restarts (idleseed.py)."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
import idleseed

NOW = 1_800_000_000.0


class SeedTest(unittest.TestCase):
    def test_no_state_uses_get_last_input(self):
        self.assertEqual(idleseed.seed_last_physical(NOW, NOW - 5, None), NOW - 5)

    def test_restart_after_a_run_keeps_the_owner_idle(self):
        # The previous worker's own clicks ended 30 s ago and it watched until 10 s ago; the owner left 40 min ago.
        state = {'last_physical_utc': NOW - 2400, 'watched_until_utc': NOW - 10}
        self.assertEqual(idleseed.seed_last_physical(NOW, NOW - 30, state), NOW - 2400)

    def test_input_after_the_old_worker_stopped_counts_as_the_owner(self):
        state = {'last_physical_utc': NOW - 2400, 'watched_until_utc': NOW - 60}
        self.assertEqual(idleseed.seed_last_physical(NOW, NOW - 20, state), NOW - 20)

    def test_bad_old_or_future_state_is_ignored(self):
        for state in ({'last_physical_utc': 'x', 'watched_until_utc': NOW}, {'watched_until_utc': NOW},
                      {'last_physical_utc': NOW - 10, 'watched_until_utc': NOW + 60},
                      {'last_physical_utc': NOW - 10, 'watched_until_utc': NOW - 4000},
                      {'last_physical_utc': NOW - 5, 'watched_until_utc': NOW - 100}, []):
            self.assertEqual(idleseed.seed_last_physical(NOW, NOW - 30, state), NOW - 30, state)

    def test_a_newer_physical_input_than_last_input_info_wins(self):
        # Inconsistent readings: keep the more recent (less idle, the safe side).
        state = {'last_physical_utc': NOW - 50, 'watched_until_utc': NOW - 10}
        self.assertEqual(idleseed.seed_last_physical(NOW, NOW - 100, state), NOW - 50)

    def test_state_file_round_trip(self):
        path = Path(tempfile.mkdtemp()) / 'owner-input.json'
        self.assertIsNone(idleseed.read_state(path))
        idleseed.write_state(path, NOW - 2400, NOW)
        state = idleseed.read_state(path)
        self.assertEqual(idleseed.seed_last_physical(NOW + 3, NOW + 1, state), NOW - 2400)


if __name__ == '__main__':
    unittest.main()
