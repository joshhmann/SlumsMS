"""b170: the orchestrator's worker seam (qarunner.SilviaWorker and WorkerTransport), fakes only: no ssh, no Silvia,
no client. The transport tests run the same remote program tools/ops/client-mcp sends, against a worker played by a
thread on a temp folder."""
import json
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qarunner  # noqa: E402

HELLO = {'ev': 'hello', 't': 12, 'pid': 4242, 'release': '1.6.2-qa', 'build': 10701}


def answer(payload, is_error=False):
    return {'isError': is_error, 'text': [payload if isinstance(payload, str) else json.dumps(payload)]}


class FakeTransport:
    """The worker's tool calls answered from a table: tool name -> answer, or a list of answers used in order."""

    def __init__(self, answers=None):
        self.answers = dict(answers or {})
        self.calls = []
        self.fetched = []
        self.target = 'silvia'

    def call(self, name, arguments=None):
        self.calls.append((name, arguments or {}))
        reply = self.answers.get(name) or answer({})
        reply = reply.pop(0) if isinstance(reply, list) else reply
        if isinstance(reply, Exception):
            raise reply
        return reply

    def fetch(self, relpath, dest):
        self.fetched.append((relpath, Path(dest)))
        return Path(dest)

    def names(self):
        return [name for name, _ in self.calls]


def ticking(times=1000, step=1.0):
    """A clock that only moves when asked, so a wait's timeout is exact."""
    state = {'now': 1000.0}

    def clock():
        return state['now']

    def sleep(seconds):
        state['now'] += max(seconds, step)

    return clock, sleep


def make_worker(answers=None, clock=None, sleep=None):
    transport = FakeTransport(answers)
    return qarunner.SilviaWorker(transport, sleep=sleep or (lambda s: None), clock=clock or ticking()[0]), transport


class WorkerCallsTest(unittest.TestCase):
    def make(self, answers=None, clock=None, sleep=None):
        return make_worker(answers, clock, sleep)

    def test_each_script_step_becomes_one_agent_tool_call(self):
        worker, transport = self.make()
        worker.send('me', {'do': 'select_world', 'world': 'Bera'})
        worker.send('me', {'do': 'select_channel', 'channel': 3})
        worker.send('me', {'do': 'select_character', 'slot': 0})
        worker.send('me', {'do': 'gm', 'line': '!bot followbot 1000000777'})
        worker.send('me', {'do': 'chat', 'text': 'hello'})
        worker.send('me', {'do': 'click', 'anchor': 'channel.confirm'})
        worker.send('me', {'do': 'key', 'key': 'Z', 'hold_ms': 250})
        worker.send('me', {'do': 'move', 'dir': 'left', 'ms': 400})
        self.assertEqual([
            ('select_world', {'world': 'Bera'}), ('select_channel', {'channel': 3}),
            ('select_character', {'slot': 0}), ('command', {'line': '!bot followbot 1000000777'}),
            ('command', {'line': 'hello'}), ('click_anchor', {'name': 'channel_confirm'}),
            ('press_key', {'name': 'Z', 'hold_ms': 250}), ('press_key', {'name': 'LEFT', 'hold_ms': 400}),
        ], transport.calls)

    def test_a_step_the_build_cannot_play_is_refused_not_skipped(self):
        worker, transport = self.make()
        for step, word in (({'do': 'jump', 'dir': 'right'}, 'jump'),
                           ({'do': 'type', 'text': 'x'}, 'type'),
                           ({'do': 'wheel', 'anchor': 'inventory.cash_tab'}, 'wheel')):
            result = worker.send('me', step)
            self.assertEqual('refused', result['result'])
            self.assertIn(word, result['detail'])
        self.assertEqual([], transport.calls)

    def test_a_tool_that_refuses_the_call_is_a_refused_step(self):
        worker, _ = self.make({'command': answer('Field screen is not visible.', is_error=True)})
        result = worker.send('me', {'do': 'gm', 'line': '!warp 100000000'})
        self.assertEqual({'result': 'refused', 'detail': 'Field screen is not visible.'}, result)

    # -- waiting --

    def test_a_signal_this_client_build_does_not_send_fails_at_once(self):
        worker, transport = self.make()
        for name, extra in (('jump.done', {}), ('field.ready', {'window': 'x'}), ('window.open', {'map_id': 1})):
            with self.assertRaises(qarunner.RunFailed) as caught:
                worker.wait_signal('me', name, 30, extra)
            self.assertIn('client signal %s not available in this client build' % name, str(caught.exception))
            self.assertEqual('refused', caught.exception.result)
        self.assertEqual([], transport.calls)   # no timeout was burned waiting for it

    def test_signals_match_their_filters(self):
        events = [{'ev': 'field.ready', 'map_id': 100000000}, {'ev': 'chat.received', 'kind': 255, 'text': 'hi'},
                  {'ev': 'field.ready', 'map_id': 104040000},
                  {'ev': 'chat.received', 'kind': 255, 'text': 'Spawned 1 BISHOP TrainingBot cids [77]'},
                  {'ev': 'window.open', 'window': 'item'}, {'ev': 'window.open', 'window': 'party_invite'}]
        for name, extra, want in (('field.ready', {'map_id': 104040000}, 2),
                                  ('chat.received', {'regex': r'cids \[(\d+)\]'}, 3),
                                  ('window.open', {'window': 'party_invite'}, 5)):
            worker, _ = self.make({'qa_events': answer({'from': 0, 'next': len(events), 'events': events})})
            got = worker.wait_signal('me', name, 5, extra)
            self.assertEqual(events[want].get('text', ''), got['text'], name)
            self.assertIn(name, got['name'])

    def test_a_wait_reads_the_new_events_by_position(self):
        pages = [answer({'from': 0, 'next': 1, 'more': False, 'events': [HELLO]}),
                 answer({'from': 1, 'next': 3, 'more': False,
                         'events': [{'ev': 'worldselect.ready', 'utc': '2026-10-31T03:12:10.000Z'},
                                    {'ev': 'channels.ready', 'utc': '2026-10-31T03:12:11.000Z'}]}),
                 answer({'from': 3, 'next': 3, 'more': False, 'events': []})]
        worker, transport = self.make({'qa_events': pages})
        worker.events('me')   # the log up to the login
        got = worker.wait_signal('me', 'channels.ready', 5, {})
        self.assertEqual('channels.ready', got['name'])
        self.assertEqual('2026-10-31T03:12:11.000Z', got['utc'])
        self.assertEqual([('qa_events', {'since': 0}), ('qa_events', {'since': 1})], transport.calls)
        self.assertEqual(3, worker.cursor['me'])   # the next wait starts after what it already read

    def test_a_signal_that_never_comes_times_out(self):
        clock, sleep = ticking(step=2.0)
        worker, transport = self.make({'qa_events': answer({'from': 0, 'next': 0, 'events': []})},
                                      clock=clock, sleep=sleep)
        self.assertIsNone(worker.wait_signal('me', 'field.ready', 5, {}))
        self.assertGreaterEqual(len(transport.calls), 3)

    def test_a_pipe_that_closes_fails_the_wait_instead_of_timing_out(self):
        worker, _ = self.make({'qa_events': answer({'from': 0, 'next': 1, 'events': [{'ev': '_closed'}]})})
        with self.assertRaises(qarunner.RunFailed) as caught:
            worker.wait_signal('me', 'field.ready', 60, {})
        self.assertIn('the QA pipe closed', str(caught.exception))

    # -- the client's identity --

    def test_a_client_without_a_hello_is_refused(self):
        worker, _ = self.make({'status': answer({'client_running': True, 'build': {'sha256': 'abc'}})})
        with self.assertRaises(qarunner.RunFailed) as caught:
            worker.character('me')
        self.assertIn('not a running SLUMS_QA_MODULE build', str(caught.exception))

    def test_the_client_entry_comes_from_the_hello_stamp_and_build_stamp_line(self):
        worker, transport = self.make({
            'qa_events': answer({'from': 0, 'next': 1, 'events': [HELLO]}),
            'status': answer({'client_running': True, 'build': {'sha256': 'b' * 64}}),
            'read_logs': answer({'integration.log': 'SLUMS 1.6.2\nBuild stamp: caps 0x80000001\n', 'crash.log': ''}),
        })
        worker.events('me')
        info = worker.character('me')
        self.assertEqual({'build': 10701, 'release': '1.6.2-qa', 'caps': '0x80000001', 'dll_sha256': 'b' * 64}, info)
        self.assertEqual(['qa_events', 'status', 'read_logs'], transport.names())

    def test_a_client_that_never_logged_its_caps_is_refused(self):
        worker, _ = self.make({
            'qa_events': answer({'from': 0, 'next': 1, 'events': [HELLO]}),
            'status': answer({'client_running': True, 'build': {'sha256': 'b' * 64}}),
            'read_logs': answer({'integration.log': 'no stamp here', 'crash.log': ''}),
        })
        worker.events('me')
        with self.assertRaises(qarunner.RunFailed) as caught:
            worker.character('me')
        self.assertIn('has not logged its build stamp', str(caught.exception))

    def test_a_click_uses_the_anchors_calibrated_point(self):
        worker, transport = self.make()
        self.assertEqual('ok', worker.send('me', {'do': 'click', 'anchor': 'channel.1'})['result'])
        self.assertEqual([('click_anchor', {'name': 'channel_1'})], transport.calls)

    def test_an_uncalibrated_anchor_is_refused_before_any_call(self):
        worker, transport = self.make()
        with self.assertRaises(qarunner.RunFailed) as caught:
            worker.send('me', {'do': 'click', 'anchor': 'party_invite.accept'})
        self.assertIn('anchor party_invite.accept is not calibrated', str(caught.exception))
        self.assertEqual([], transport.calls)

    def test_an_unknown_anchor_is_refused(self):
        worker, _ = self.make()
        with self.assertRaises(qarunner.RunFailed):
            worker.send('me', {'do': 'click', 'anchor': 'no.such_anchor'})

    def test_login_starts_the_client_when_none_is_running(self):
        worker, transport = self.make({
            'status': [answer({'client_running': False, 'build': {'sha256': 'b' * 64}}),
                       answer({'client_running': True, 'build': {'sha256': 'b' * 64}}),
                       answer({'client_running': True, 'build': {'sha256': 'b' * 64}})],
            'start_client': answer({'state': 'login'}),
            'qa_events': answer({'from': 0, 'next': 1, 'events': [HELLO]}),
            'login_credentials': answer({'vault': 'aq-gm-1', 'entered': True}),
            'read_logs': answer({'integration.log': 'Build stamp: caps 0x80000001', 'crash.log': ''}),
        })
        info = worker.login('me', {'account': {'vault': 'aq-gm-1'}})
        self.assertEqual(['status', 'start_client', 'qa_events', 'login_credentials', 'status', 'read_logs'],
                         transport.names())
        self.assertEqual({'vault': 'aq-gm-1'}, transport.calls[3][1])
        self.assertEqual('1.6.2-qa', info['release'])

    def test_login_replaces_a_client_left_running_from_before(self):
        worker, transport = self.make({
            'status': [answer({'client_running': True}),
                       answer({'client_running': True, 'build': {'sha256': 'b' * 64}})],
            'stop_client': answer({'state': 'stopped'}),
            'start_client': answer({'state': 'login'}),
            'qa_events': answer({'from': 0, 'next': 1, 'events': [HELLO]}),
            'login_credentials': answer({'vault': 'aq-gm-1', 'entered': True}),
            'read_logs': answer({'integration.log': 'Build stamp: caps 0x80000001', 'crash.log': ''}),
        })
        worker.login('me', {'account': {'vault': 'aq-gm-1'}})
        self.assertEqual(['status', 'stop_client', 'start_client', 'qa_events', 'login_credentials', 'status',
                          'read_logs'], transport.names())

    def test_a_client_with_no_vault_is_refused(self):
        worker, _ = self.make({'status': answer({'client_running': True, 'build': {'sha256': 'b' * 64}}),
                               'qa_events': answer({'from': 0, 'next': 0, 'events': []})})
        with self.assertRaises(qarunner.RunFailed) as caught:
            worker.login('me', {'account': {}})
        self.assertIn('has no account.vault', str(caught.exception))

    def test_capture_kinds_the_build_does_not_record_are_refused(self):
        worker, _ = self.make()
        for kind in ('chat_log', 'packet_trace', 'samples'):
            with self.assertRaises(qarunner.RunFailed) as caught:
                worker.capture('me', kind, 'cap-1', {}, True)
            self.assertIn('%s capture is not available in this client build' % kind, str(caught.exception))

    def test_closing_stops_the_client_the_worker_started(self):
        worker, transport = self.make({'stop_client': answer({'state': 'stopped'})})
        worker.close()
        worker.close()
        self.assertEqual([('stop_client', {})], transport.calls)

    def test_a_stop_that_is_refused_does_not_hide_the_run(self):
        worker, _ = self.make({'stop_client': qarunner.RunFailed('no worker', 'error')})
        worker.close()   # the run's result already stands: cleaning up must not raise


class WorkerTransportTest(unittest.TestCase):
    """The remote program, run here against a worker played by a thread: the same inbox/outbox protocol."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / 'logs').mkdir()
        (self.root / 'logs' / 'worker-outbox.jsonl').write_text('')   # a worker that is up has written it
        self.stop = threading.Event()
        self.threads = []

    def tearDown(self):
        self.stop.set()
        for thread in self.threads:
            thread.join(2)

    def worker(self, handle):
        """Answers each request in the inbox with `handle(request)` -> list of outbox lines (None: stay silent)."""
        def loop():
            offset = 0
            while not self.stop.is_set():
                inbox = self.root / 'logs' / 'worker-inbox.jsonl'
                if inbox.exists():
                    with inbox.open(encoding='utf-8') as stream:
                        stream.seek(offset)
                        for line in stream:
                            if not line.endswith('\n'):
                                break
                            offset += len(line)
                            request = json.loads(line)
                            if request.get('action') != 'call':
                                continue
                            for event in handle(request) or []:
                                event.setdefault('id', request['id'])
                                with (self.root / 'logs' / 'worker-outbox.jsonl').open('a', encoding='utf-8') as out:
                                    out.write(json.dumps(event) + '\n')
                time.sleep(0.02)
        thread = threading.Thread(target=loop, daemon=True)
        thread.start()
        self.threads.append(thread)

    def transport(self, wait=3, queue_wait=3):
        return qarunner.WorkerTransport(target='vm', wrap=False, root=str(self.root), runner=subprocess.run,
                                        wait=wait, queue_wait=queue_wait, python=sys.executable)

    def test_a_worker_that_answers_gives_the_tool_result(self):
        self.worker(lambda r: [{'event': 'started'}, {'result': {'isError': False, 'content': [
            {'type': 'text', 'text': '{"client_running": true}'}]}}])
        self.assertEqual({'isError': False, 'text': ['{"client_running": true}']},
                         self.transport().call('status', {}))

    def test_a_tool_error_is_reported_as_one(self):
        self.worker(lambda r: [{'result': {'isError': True, 'content': [{'type': 'text', 'text': 'nope'}]}}])
        self.assertEqual({'isError': True, 'text': ['nope']}, self.transport().call('status', {}))

    def test_no_worker_running_is_said_plainly(self):
        (self.root / 'logs' / 'worker-outbox.jsonl').unlink()   # nothing has ever run there
        with self.assertRaises(qarunner.RunFailed) as caught:
            self.transport(wait=2).call('status', {})
        self.assertIn('no client-MCP worker is running on vm', str(caught.exception))

    def test_a_call_the_owner_kept_queued_fails_with_that_reason(self):
        self.worker(lambda r: [{'event': 'queued'}])
        started = time.monotonic()
        with self.assertRaises(qarunner.RunFailed) as caught:
            self.transport(wait=1, queue_wait=1).call('status', {})
        self.assertIn('kept the call queued', str(caught.exception))
        self.assertLess(time.monotonic() - started, 30)

    def test_a_worker_that_exits_mid_call_is_reported(self):
        self.worker(lambda r: [{'exited': True}])
        with self.assertRaises(qarunner.RunFailed) as caught:
            self.transport().call('status', {})
        self.assertIn('exited while the call was queued', str(caught.exception))

    def test_the_request_the_worker_gets_is_the_wrapper_s_own_shape(self):
        seen = {}

        def handle(request):
            seen.update(request)
            return [{'result': {'isError': False, 'content': [{'type': 'text', 'text': '{}'}]}}]

        self.worker(handle)
        self.transport().call('select_channel', {'channel': 3})
        self.assertEqual({'action', 'name', 'arguments', 'wait_until', 'id'}, set(seen))
        self.assertEqual(('call', 'select_channel', {'channel': 3}),
                         (seen['action'], seen['name'], seen['arguments']))
        self.assertTrue(seen['id'].startswith('run-'))

    def test_a_run_folder_is_fetched_as_a_zip(self):
        folder = self.root / 'runs' / 'b140-1' / 'frames' / 'column-early'
        folder.mkdir(parents=True)
        (folder / '0001.png').write_bytes(b'png-bytes')
        dest = Path(tempfile.mkdtemp()) / 'frames'
        self.transport().fetch('runs/b140-1/frames/column-early', dest)
        self.assertEqual(b'png-bytes', (dest / '0001.png').read_bytes())

    def test_a_copy_that_cannot_come_back_fails_the_pull(self):
        with self.assertRaises(qarunner.RunFailed) as caught:
            self.transport().fetch('runs/absent', Path(tempfile.mkdtemp()))
        self.assertIn('no folder runs/absent on the box', str(caught.exception))


class FramesTest(unittest.TestCase):
    def test_a_burst_s_bmps_become_the_pngs_the_run_folder_promises(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest('no PIL on this machine')
        folder = Path(tempfile.mkdtemp())
        for n in (1, 2):
            Image.new('RGB', (8, 6), (n, 0, 0)).save(folder / ('%04d.bmp' % n))
        (folder / 'frames.tsv').write_text('n\tutc\tms\n')
        self.assertEqual(2, qarunner.convert_frames(folder))
        self.assertEqual([], list(folder.glob('*.bmp')))
        self.assertEqual(['0001.png', '0002.png'], sorted(p.name for p in folder.glob('*.png')))
        self.assertTrue((folder / 'frames.tsv').exists())


class WorkerPullTest(unittest.TestCase):
    def test_pull_writes_the_frames_and_the_pipe_log(self):
        worker, transport = make_worker()
        worker.seen['me'] = [HELLO, {'ev': 'field.ready', 'utc': '2026-10-31T03:12:20.000Z', '_at': 1.0}]
        transport.answers['read_logs'] = answer({'integration.log': 'log line', 'crash.log': ''})
        folder = Path(tempfile.mkdtemp())
        worker.pull(folder, {'run-column-early': 'column-early'})
        self.assertEqual([('client/qa-captures/run-column-early', folder / 'frames' / 'column-early')],
                         transport.fetched)
        self.assertIn('field.ready', (folder / 'client' / 'me' / 'pipe.log').read_text())
        self.assertIn('log line', (folder / 'client' / 'me' / 'client.log').read_text())


if __name__ == '__main__':
    unittest.main()


class LocalFeed:
    """The TEST feed, empty: this class is about the worker, so no server line ever arrives."""

    def __init__(self):
        self.polls = []

    def poll(self, since, chars, topics, match, wait):
        self.polls.append((since, tuple(topics)))
        return {'ok': True, 'last': 0, 'next': since, 'gap': False, 'lines': []}


class RunWithTheRealWorkerTest(unittest.TestCase):
    """b140 played through SilviaWorker: it must get only as far as this client build can honestly take it."""

    def b140(self, pages):
        script = qarunner.REPO / 'qa-scripts/b140-bot-heal-column.yaml'
        clock = lambda: qarunner.datetime.datetime(2026, 10, 31, 4, 20, 0, tzinfo=qarunner.datetime.timezone.utc)
        worker, transport = make_worker({
            'status': [answer({'client_running': False, 'build': {'sha256': 'c' * 64}})] * 3,
            'start_client': answer({'state': 'login'}),
            'qa_events': pages,
            'login_credentials': answer({'vault': 'aq-gm-1', 'entered': True}),
            'read_logs': answer({'integration.log': 'Build stamp: caps 0x80000001', 'crash.log': ''}),
            'stop_client': answer({'state': 'stopped'}),
        }, *ticking(step=5.0))
        run = qarunner.Run(script, LocalFeed(), worker, runs_root=tempfile.mkdtemp(), commit='d4f103b0e',
                           clock=clock, sleep=lambda s: None)
        return run, run.run(), transport

    def pages(self):
        steps = [('hello', [HELLO]), ('worldselect.ready', [{'ev': 'worldselect.ready'}]),
                 ('channels.ready', [{'ev': 'channels.ready'}]), ('charselect.ready', [{'ev': 'charselect.ready'}]),
                 ('field.ready', [{'ev': 'field.ready'}])]
        out, position = [], 0
        for _, events in steps:
            out.append(answer({'from': position, 'next': position + len(events), 'more': False, 'events': events}))
            position += len(events)
        return out

    def test_the_run_stops_where_this_client_build_stops(self):
        run, m, transport = self.b140(self.pages())
        self.assertEqual('FAIL', m['result'])
        self.assertEqual('client-mcp silvia', m['runner']['worker'])
        self.assertEqual(9, m['failed_at'])   # "on-map": no field.ready with map id 104040000 ever comes
        step = m['steps'][9]
        self.assertEqual('timeout', step['result'])
        self.assertIn('timed out waiting for field.ready', step['detail'])
        self.assertEqual(['ok'] * 9, [s['result'] for s in m['steps'][:9]])
        self.assertEqual('not run', m['steps'][10]['result'])
        # what the client said it is; no character name is invented because the build sends none
        self.assertEqual({'build': 10701, 'release': '1.6.2-qa', 'caps': '0x80000001', 'dll_sha256': 'c' * 64},
                         m['clients']['me'])
        # the folder has the pipe log and the client log; the worker was left with its client stopped
        self.assertIn('hello', (run.folder / 'client' / 'me' / 'pipe.log').read_text())
        self.assertIn('Build stamp', (run.folder / 'client' / 'me' / 'client.log').read_text())
        self.assertEqual(('stop_client', {}), transport.calls[-1])
        self.assertEqual('1.6.2-qa', m['clients']['me']['release'])

    def test_the_login_never_claims_a_character_it_does_not_know(self):
        run, m, _ = self.b140(self.pages())
        self.assertTrue([s for s in m['steps'] if s['do'] == 'wait'])
        self.assertNotIn('char_name', m['clients']['me'])
        self.assertNotIn('me.name', run.values)
