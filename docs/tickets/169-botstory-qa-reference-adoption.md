# 169: Borrow targeted QA diagnostics from BotStory

Requested by the owner, 2026-10-31 (UTC and PT), after a read-only review of
[Incluwin/BotStory](https://github.com/Incluwin/BotStory).

Target: primarily after 1.6.2. A minimal diagnostic or regression test may enter 1.6.2 only when needed to close
an existing release blocker under [ticket 168](168-finish-1.6.2-and-freeze-scope.md). This is not a new release gate
or permission to expand the frozen release. Server Dev owns technical selection; Client Development owns any
required visual evidence. Current tracking belongs in the release book and exported STATUS file.

## Goal

Make failures easier to diagnose and experiments harder to misread, using selected BotStory patterns in the
existing SlumsMS QA system. Adapt narrow pieces; do not import its Agent runtime or create another QA platform.

## Review basis and limits

Source inspected at **`beede7880802d2a95618c074bc11e3d25e8d608d`**. Local reference checkout:
`/root/work/research/BotStory-qa-review`. Review covered source, documentation and selected tests. No BotStory
server, build or test suite was run; documented coverage counts are upstream claims, not SlumsMS proof.

BotStory uses `server.agents` gateways, registries, provisioning and configuration. SlumsMS uses its existing
`soloMapling` runtime and QA tools. Similar ancestry does not make these implementations directly compatible.

## A. Conditional 1.6.2 work: actionable KPQ stall evidence

Use only if an existing blocker cannot be diagnosed with current logs. SlumsMS already reports stage-clear times
and has a stall guard; check those and the current fix branches before adding anything.

Reference: [AgentKpqPaceReport.java](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/src/main/java/server/agents/capabilities/partyquest/kpq/AgentKpqPaceReport.java).
It separates grinding, hand-ins, puzzle checks, movement, combat, loot and pass delivery, and marks incomplete
measurements with reasons such as member loss or throttled simulation.

Smallest useful SlumsMS adaptation:

- On a meaningful transition or stall, identify run, stage, participant and current action/wait reason.
- Include elapsed time, last progress event, relevant target/location and blocking condition where available.
- Distinguish unreachable target, missing member, waiting for leader/pass, cooldown and failed staging when the
  runtime can actually establish that cause. Record unknown instead of inferring a cause from silence.
- Keep output bounded and available in the run evidence, without flooding player chat or logging every tick.
- Preserve gameplay behavior, rewards, movement rules and production timing. Do not import pace calibration,
  change NPC scripts or accelerate bots to make a QA verdict green.

Acceptance for any selected slice: a focused regression test proves the diagnostic; the original failing check
is rerun on TEST by its lane owner; the report identifies the blocked action rather than only a total timeout.
All normal ticket 168 and lane checks still apply. If current evidence already suffices, defer this section.

## B. After 1.6.2: skill execution probes

References:

- [Skill probe guide](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/docs/agents/SKILL_PROBE_HARNESS.md).
- [SkillProbeOutcome.java](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/src/main/java/server/agents/diagnostics/skillprobe/SkillProbeOutcome.java).

Prototype one known problematic skill/job through SlumsMS's normal combat path before expanding coverage.
Record skill/job/build, prerequisites, intended action, observed result, elapsed time and explicit failure stage:
missing/unlearned skill, weapon, ammo/cost, target reachability, planner rejection, handler rejection or no damage.
Distinguish unsupported, not exercised and inconclusive from PASS.

State the strength of each assertion: learned, eligible, dispatched, effect applied or damage landed. A direct
dispatch or learned passive is not proof that the autonomous combat loop chooses it correctly. Server execution
does not prove packet compatibility or a visible client animation. Keep those as separate checks.

Use disposable TEST fixtures and existing QA entrypoints. Upstream's headless operator is a useful reference,
not a requirement to add a remote GM console or another control service.

## C. After 1.6.2: validate combat measurements

Reference: [ArenaAnomalyChecks.java](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/src/main/java/server/agents/diagnostics/arena/ArenaAnomalyChecks.java).

Before trusting DPS/class comparisons, detect attacks with no landed damage, casting rates inconsistent with
the applicable animation/charge constraints, and trials bounded by the dummy's HP rather than sustained damage.
Derive bounds from SlumsMS's actual rules, including legitimate speed buffs. Invalid measurements are flagged
with evidence; do not alter class balance or copy upstream numeric thresholds without validation.

Acceptance: focused tests cover a valid trial and each selected invalid measurement; one bounded TEST trial
shows the readings and validity reasons. This work does not authorize economy or combat-balance changes.

## D. After 1.6.2, or a directly relevant blocker: lifecycle regression cases

Reference: [CompanionFullRateSimulationTest.java](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/src/test/java/server/agents/capabilities/partyquest/kpq/CompanionFullRateSimulationTest.java).

Borrow cases, adapting assertions to SlumsMS's ownership model: termination racing a tick, member ejection,
death, cleanup and preserving another subsystem's resource/hold. Use controlled time and reusable fixtures where
appropriate. BotStory's seeded KPQ harness is also a reference for stable scenario inputs; a seed alone does not
make asynchronous gameplay deterministic.

These are mocked regression cases, not complete gameplay simulations. Each selected case must protect a real
SlumsMS failure or invariant and retain the required runtime/client acceptance check.

## E. Later: offline content checks and evidence packaging

References:

- [ContentLint.java](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/src/main/java/server/living/config/ContentLint.java).
- [PopulationLintMain.java](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/src/main/java/server/living/population/PopulationLintMain.java).
- [Soak evidence tools](https://github.com/Incluwin/BotStory/blob/beede7880802d2a95618c074bc11e3d25e8d608d/tools/soak/README.md).

Evaluate small checks for invalid map/mob/item/NPC references and broken content links before boot. Compare with
existing SlumsMS validators first. Preserve the distinction between warnings and errors, and report missing input
data explicitly. Evidence-package validation can check required artifacts, but does not prove gameplay success.
Upstream's soak scripts package/validate evidence and preflight inputs; do not assume its design specifications
constitute a complete automated player or soak runner.

## Work order and completion

1. For 1.6.2, decide whether A or one D case is required by an existing blocker; record its id and bounded scope.
   Otherwise leave this ticket for after release. Do not delay 1.6.2 to complete it.
2. After release, inspect existing equivalents and choose one small pilot from B or C with acceptance criteria.
3. Hand back branch/commits, tests and TEST evidence, limits, and a recommendation on further adoption.
4. Record unselected work as deferred or create separately scoped follow-ups; do not describe the entire catalog
   as implemented because one pilot works.

For any copied source, pin and credit the upstream commit and record it in `docs/PROVENANCE.md` under the existing
porting rules. Follow repository license requirements. No runtime merge, deployment, live access or additional
feature adoption is authorized by creating this ticket.
