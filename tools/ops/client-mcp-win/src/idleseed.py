"""Where a new worker's owner-idle clock starts (b023). Pure functions, no Windows calls.

A worker counts the owner's physical input with low-level hooks and ignores its own tagged SendInput. But when it
starts it has no hook history yet, so it seeds from GetLastInputInfo, which counts EVERY input, the previous worker's
injected clicks included. So each worker restart after a run looked like fresh owner activity and waited the full
idle_minutes again (2026-10-31: 9.5 minutes before a 25-second run).

The running worker therefore keeps logs/owner-input.json: the UTC time of the last physical input it saw and the
UTC time it last checked ("watched_until"). A new worker trusts that file when the newest input of any kind
(GetLastInputInfo) happened while the previous worker was still watching: then the previous worker classified it,
and its last_physical is right. Input after the previous worker stopped watching is unclassified and counts as the
owner's (the safe side).
"""
import json

SLACK_SECONDS = 2.0      # GetLastInputInfo and the state file are read a moment apart
MAX_STATE_AGE = 3600.0   # an older file says nothing about now


def seed_last_physical(now_wall, last_input_wall, state):
    """Wall-clock time (epoch seconds) to treat as the owner's last physical input at worker start."""
    if not isinstance(state, dict):
        return last_input_wall
    try:
        physical = float(state['last_physical_utc'])
        watched = float(state['watched_until_utc'])
    except (KeyError, TypeError, ValueError):
        return last_input_wall
    if watched > now_wall + SLACK_SECONDS or physical > watched + SLACK_SECONDS or now_wall - watched > MAX_STATE_AGE:
        return last_input_wall          # clock skew, a nonsense file, or too old
    if last_input_wall <= watched + SLACK_SECONDS:
        return physical                 # the previous worker saw that input; trust its classification
    return last_input_wall              # input after the previous worker stopped watching: count it as the owner's


def read_state(path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


def write_state(path, last_physical_wall, now_wall):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps({'last_physical_utc': round(last_physical_wall, 3),
                                     'watched_until_utc': round(now_wall, 3)}), encoding='utf-8')
    temporary.replace(path)
