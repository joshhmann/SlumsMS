# 170: Borrow controlled QA fixtures from cosmic_agents

Owner request: review Kiwiism/cosmic_agents alongside BotStory and record useful QA follow-ups, 2026-10-31
(UTC and PT). Target: after 1.6.2. Only a minimal regression case needed for an existing release blocker may enter
1.6.2 under [ticket 168](168-finish-1.6.2-and-freeze-scope.md). No scheduler replacement or new release gate.
Server Dev owns selection and implementation; status belongs in the release book/exported STATUS file.

## Relationship and review basis

BotStory's README identifies Kiwiism/cosmic_agents as its upstream Agent platform, rather than cosmic_agents being
a derivative of BotStory. Both credit P0nk/Cosmic and nutnnut's companion work. This describes documented lineage,
not a complete git ancestry comparison.

- Repository: https://github.com/Kiwiism/cosmic_agents
- Inspected master/HEAD: **`3c7e094357329932734a860e5a831d57add27a00`**.
- Local reference: `/root/work/research/cosmic-agents-qa-review`.
- Source/docs/selected tests inspected; no upstream build, tests, server or live-client checks run.
- SlumsMS already cites `3c7e09435` in `docs/PROVENANCE.md` for KPQ cross-checks. Check prior adoption before ports.

Overlap with [ticket 169](169-botstory-qa-reference-adoption.md): at the inspected heads,
`BotFollowTickPerfHarness`, `AgentDecisionReplay`, `AgentSchedulerParityTest` and `AgentTickSchedulerSoakTest`
are byte-identical in both repositories. Track one adoption, credit its source, and do not create duplicate work.

## Candidate 1: controlled movement regression fixtures

Reference: [BotMovementSimulationLabTest.java](https://github.com/Kiwiism/cosmic_agents/blob/3c7e094357329932734a860e5a831d57add27a00/src/test/java/server/agents/integration/BotMovementSimulationLabTest.java).

Useful pattern: construct geometry and actors, step movement explicitly, assert arrival/grounding/follow state,
and print recent movement traces on failure. Examples include an intermediate bump landing, wall-end momentum
and rope oscillation. Some scenarios load real map geometry and require data; they are not all data-free.

Adapt one existing SlumsMS navigation/follow bug to the existing movement runtime and test seams. Record start
position, target, geometry/data identity, tick inputs and expected result. Require fail-before/pass-after proof.
Controlled simulation does not prove client rendering or the complete asynchronous runtime.

## Candidate 2: lifecycle and scheduler invariants under controlled time

References:

- [AgentSchedulerParityTest.java](https://github.com/Kiwiism/cosmic_agents/blob/3c7e094357329932734a860e5a831d57add27a00/src/test/java/server/agents/runtime/AgentSchedulerParityTest.java).
- [AgentTickSchedulerSoakTest.java](https://github.com/Kiwiism/cosmic_agents/blob/3c7e094357329932734a860e5a831d57add27a00/src/test/java/server/agents/runtime/AgentTickSchedulerSoakTest.java).
- [AgentTickSchedulerScaleGateTest.java](https://github.com/Kiwiism/cosmic_agents/blob/3c7e094357329932734a860e5a831d57add27a00/src/test/java/server/agents/runtime/scheduler/AgentTickSchedulerScaleGateTest.java).

Borrow assertions appropriate to SlumsMS: due callbacks execute, a session is not executed concurrently twice,
cancellation releases owned scheduling/queue state, and stopped sessions cannot regain registrations. Use
controlled clocks and minimal callbacks for fast regression feedback. Compare observable behavior before/after
a real scheduling change; the upstream basic parity test compares callback counts, not full gameplay behavior.

Upstream's scale test covers 2,000 mocked sessions over 20 cadences and checks cleanup/duplicate execution.
That is implementation evidence, not a long-duration soak with 2,000 playing bots. Do not use these tests to
claim production capacity, packet parity or client stability. No adoption of its central scheduler is in scope.

## Candidate 3: opt-in performance diagnosis

Reference: [BotFollowTickPerfHarness.java](https://github.com/Kiwiism/cosmic_agents/blob/3c7e094357329932734a860e5a831d57add27a00/src/test/java/server/agents/integration/BotFollowTickPerfHarness.java).

Pattern: warm up, reset counters, drive the normal tick/movement paths sequentially and with several workers,
then report section timings. This can help isolate expensive tick sections or contention before scheduling a
whole TEST run. Its entities/maps are mocked; it is a diagnostic harness, not proof of live throughput.

Prototype only for a measured SlumsMS bottleneck, using its existing performance instrumentation. Record host,
commit/data identity, workload, warm-up and repetitions. Keep it opt-in; avoid flaky CI thresholds or claims of
improved development-cycle time based solely on bot tick throughput.

## Candidate 4: decision trace validation, with an explicit limit

Reference: [AgentDecisionReplay.java](https://github.com/Kiwiism/cosmic_agents/blob/3c7e094357329932734a860e5a831d57add27a00/src/main/java/server/agents/runtime/decision/AgentDecisionReplay.java).

Despite its name, this implementation validates sequence/time ordering, fingerprints recorded fields and reports
last choices. It does not rerun a decision engine or reconstruct a world. Repeated hashing of one trace proves
neither deterministic AI nor correct decisions.

If useful for an existing diagnostic need, adapt bounded correlated traces that record actor/run, decision domain,
candidates, choice, reason and behavior version. Any comparison must define which inputs are held fixed and what
output differences matter. Do not start a full game-replay project as part of this ticket.

## Candidate 5: focused architecture checks

Reference: [AgentSchedulerBlockingBoundaryTest.java](https://github.com/Kiwiism/cosmic_agents/blob/3c7e094357329932734a860e5a831d57add27a00/src/test/java/server/agents/runtime/async/AgentSchedulerBlockingBoundaryTest.java).

Source scans guard against selected direct waits and synchronous navigation graph construction in tick code.
Borrow only a narrow rule tied to an established SlumsMS invariant. Such scans are heuristics: absence of a regex
match does not prove nonblocking execution or deadlock freedom. Do not mirror the upstream architecture wholesale.

## Execution and acceptance

1. Preserve ticket 168's freeze. Check existing SlumsMS tests and ticket 169 before selecting any work.
2. After release, choose one bounded pilot with a concrete failure/invariant, expected evidence and owner.
3. Adapt to SlumsMS's runtime; avoid importing gateways, configuration, schedulers or blanket refactors.
4. Hand back commits, meaningful regression/performance results, limitations and required runtime/client checks.
5. Record unselected candidates as deferred or separately scoped work. One completed pilot does not mean all
   candidates are implemented. No need to implement every candidate to close this research/adoption ticket once
   their dispositions and the selected pilot's outcome are recorded.

Follow existing port provenance and license requirements for copied source. Preserve ordinary release and
real-client acceptance gates. This ticket authorizes no merge, deployment, restart or live access.
