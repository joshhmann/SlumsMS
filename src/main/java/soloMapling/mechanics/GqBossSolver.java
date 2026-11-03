package soloMapling.mechanics;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code gq-boss} (Guild Quest stage 7, Sharenian's last gate and Ergoth's hall: {@code 990000700} -> {@code 990000800}
 * -> {@code 990000900}): the way on from the dressed body, the gate that wants the Protector Rock, and the boss.
 *
 * <p><b>The sacrifice.</b> Ergoth's gate is the reactor {@code kinggate} ({@code 9208009} at (261,83) on 800). Its
 * only event is an item one: it opens when exactly one {@code 1032033} - the Protector Rock earring - lies in its box
 * ({@code Reactor.wz/9208009.img.xml}, lt(-86,-15) rb(42,33): x 175..303, y 68..116), and the earring is {@code only 1}
 * ({@code Character.wz/Accessory/01032033.img.xml}: nobody can carry a spare, {@code BotHoldings} enforces the pickup
 * restriction). The party therefore has to give <em>one member's earring</em> to the gate, and that member is left without
 * it on {@code 990000800} and {@code 990000900}, whose {@code decHP} is 30000 for anyone not wearing it
 * ({@code MapFactory.java:358-359}, {@code Character.doHurtHp}: {@code Character.java:3606-3609}). The solver says so in
 * three explicit steps and one explicit member, and hides nothing in a generic step:
 * <ol>
 *   <li>the <b>sacrificer</b> is the highest-id bot that is not the leader (the leader has to loot and hand in on the
 *       boss map; if the party's only bot is the leader, he pays) - recomputed every tick from the members, so a
 *       sacrificer who left before paying is replaced;</li>
 *   <li>on 800 it takes the earring off ({@link SolverStep.UnequipItem}; the run log line is {@code UNEQUIP <name> takes
 *       off 1032033 - ..} and names the member who paid);</li>
 *   <li>it drops the one earring in the gate's box ({@link SolverStep.PlaceItem}, the server acts 5 s later), and the gate
 *       opens - {@code 9208009.js} also sets {@code canRevive=1}, so a member who falls from here on stays in the quest
 *       ({@code GuildQuest.js:264-277}).</li>
 * </ol>
 * After that the sacrificer <em>sits out</em> (a {@link SolverStep.Wait} on 800) and does not follow the party to the
 * boss map: it has nothing to protect it there.
 *
 * <p><b>What is and is not proven.</b> Everything above is read from the scripts and the WZ. UNPROVEN: that a bot or alt
 * without the earring really dies of {@code decHP} on 800 (the tick is {@code World.runPlayerHpDecreaseSchedule}; it skips
 * any character whose "away from the world" flag is set, {@code World.java:1808}, and that flag stays at its default
 * {@code true} for a <b>population bot</b> - only a player's login and {@code OwnedAltBackend} clear it - so population
 * bots are very likely never hurt by it at all, and an owned alt or a player is); the tick interval and count
 * ({@code MAP_DAMAGE_OVERTIME_*}, not in {@code config.example.yaml}); the earring drop rows (reactordrops
 * {@code 9202000/9202009 -> 1032033}, seed data, <b>live table unverified</b>); the Rubian drop ({@code 9300028 ->
 * 4001024}, seed data, <b>live table unverified</b>); and whether a party of low-level bots can kill Ergoth (level 115,
 * 1,700,000 HP, {@code Mob.wz/9300028.img.xml}).
 *
 * <p><b>The rest of the stage</b>, for everyone but the sacrificer: out of the maze rooms to 700 by the clothing rooms'
 * {@code guild1F00} door ({@link GqMazeSolver#move}); 700's {@code next00} ({@code ghostgate_open.js}, needs the reactor
 * {@code ghostgate} at state 1, which the Soul's talk forced - {@code 9040003.js:70}); 800's {@code boss00}
 * ({@code kinggate_open.js}, needs {@code kinggate} at state 1); on 900 one bot hits the reactor {@code boss}
 * ({@code 9201002}, states 0-5; its act spawns Ergoth {@code 9300028} and four guards {@code 9300029-32},
 * {@code scripts/reactor/9201002.js}), everybody fights, and the Rubian {@code 4001024} Ergoth drops goes to the leader,
 * who gives it to the Soul {@code 9040010} - one click: {@code scripts/npc/9040010.js:30-36} removes it and calls
 * {@code eim.clearPQ()}, which clears the event.
 */
public final class GqBossSolver implements StageSolver {

    public static final String ID = "gq-boss";

    static final int M600 = 990000600;
    static final int M700 = 990000700;
    static final int M800 = 990000800;
    static final int M900 = 990000900;

    static final int KING_GATE_REACTOR = 9208009;
    static final String KING_GATE = "kinggate";
    static final int GHOST_GATE_REACTOR = 9208008;
    static final int BOSS_REACTOR = 9201002;
    static final String BOSS_REACTOR_NAME = "boss";
    /** The reactor's last state: it has states 0-5 ({@code Reactor.wz/9201002.img.xml}) and five hits get it there. */
    static final int BOSS_REACTOR_SPENT = 5;
    static final int ERGOTH = 9300028;
    static final List<Integer> FOES = List.of(9300028, 9300029, 9300030, 9300031, 9300032);
    /** The Rubian: Ergoth's drop (seed row, live table unverified) and the Soul's one request. */
    static final int RUBIAN = 4001024;
    static final int SOUL = 9040010;
    static final String SEEN_BOSS = "boss:seen";
    static final String SEEN_REACTOR = "boss:reactorSeen";
    static final String SPENT_SINCE = "boss:spentSince";
    /** How long a spent boss reactor may go without an Ergoth on the map before the stage stops waiting for him. */
    static final long SPAWN_GRACE_MS = 15_000;

    @Override
    public String id() {
        return ID;
    }

    /** The rooms this stage walks members out of and through: the maze rooms, 700, the gate hall and the boss hall. */
    @Override
    public Set<Integer> extraMaps(StageView view) {
        return Set.of(M600, 990000610, 990000611, 990000620, 990000630, 990000631, 990000640, 990000641, M700, M900);
    }

    /**
     * The member who gives his earring to Ergoth's gate: the highest-id bot that is not the leader, else the leader when
     * he is the only bot, else nobody (a party of players is never steered, so nobody can pay).
     */
    static Optional<RunMember> sacrificer(StageView view) {
        Optional<RunMember> other = view.members().stream().filter(RunMember::bot).filter(m -> !m.leader())
                .max(Comparator.comparingInt(RunMember::characterId));
        if (other.isPresent()) {
            return other;
        }
        return view.members().stream().filter(RunMember::bot).max(Comparator.comparingInt(RunMember::characterId));
    }

    static boolean gateOpen(StageView view) {
        return view.reactorState(KING_GATE) >= 1;
    }

    private static boolean ghostGateOpen(StageView view) {
        return view.reactorsOn(M700).stream().anyMatch(r -> r.reactorId() == GHOST_GATE_REACTOR && r.state() >= 1);
    }

    private static StageView.ReactorPlacement bossReactor(StageView view) {
        return view.reactorsOn(M900).stream().filter(r -> r.reactorId() == BOSS_REACTOR).findFirst().orElse(null);
    }

    /** Whether Ergoth has been seen on the boss map: once seen, a reactor that is gone or a boss that is down is "finished". */
    private static boolean bossSpawned(StageView view) {
        if (view.mobsAliveOn(M900, ERGOTH) > 0) {
            view.stageMemory().putIfAbsent(SEEN_BOSS, Boolean.TRUE);
            return true;
        }
        return view.stageMemory().containsKey(SEEN_BOSS);
    }

    /** The one bot that wakes the boss: the lowest id of the bots standing on the boss map that are not the sacrificer. */
    private static Optional<RunMember> hitter(StageView view, Optional<RunMember> sacrificer) {
        return view.members().stream()
                .filter(RunMember::bot)
                .filter(m -> view.mapOf(m.characterId()) == M900)
                .filter(m -> sacrificer.map(s -> s.characterId() != m.characterId()).orElse(true))
                .min(Comparator.comparingInt(RunMember::characterId));
    }

    @Override
    public Optional<String> leaderPrompt(StageView view, RunMember leader) {
        if (view.cleared()) {
            return Optional.empty();
        }
        int here = view.mapOf(leader.characterId());
        String who = "@" + leader.characterId();
        if (here == M700 && ghostGateOpen(view)) {
            return Optional.of(who + " the way is open: take the portal at the far right of the room, into Ergoth's gate hall");
        }
        if (here == M800 && gateOpen(view)) {
            return Optional.of(who + " the gate is open: take the portal at the right of the hall (boss00) into Ergoth's hall");
        }
        if (here == M900 && bossSpawned(view) && view.mobsAliveOn(M900, ERGOTH) == 0) {
            return Optional.of(view.itemCount(leader.characterId(), RUBIAN) > 0
                    ? who + " you hold the Rubian: talk to the Soul (9040010) to finish"
                    : who + " Ergoth is down: pick up the Rubian (" + RUBIAN + ") and talk to the Soul (9040010)");
        }
        return Optional.empty();
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        int here = view.mapOf(id);
        Optional<RunMember> sac = sacrificer(view);
        boolean paysForTheGate = sac.map(s -> s.characterId() == id).orElse(false);

        if (paysForTheGate) {
            SolverStep own = sacrificerStep(view, member, here);
            if (own != null) {
                return own;
            }
        }
        SolverStep protect = GqProtection.step(view, member);
        if (protect != null) {
            return protect;
        }
        return switch (here) {
            case M900 -> bossHall(view, member, sac);
            case M800 -> gateHall(view, member);
            case M700 -> ghostGateOpen(view)
                    ? new SolverStep.ScriptedPortal("next00") // ghostgate_open.js
                    : new SolverStep.Wait(1_500, "the ghost gate (reactor " + GHOST_GATE_REACTOR + ", \"ghostgate\") on 700 is "
                    + "closed: the leader's talk with Sharen III's Soul (9040003) opens it");
            default -> GqMazeSolver.move(view, member, M700);
        };
    }

    /**
     * The sacrificer's part, or null when it has none right now (it is not on 800 yet, so it walks and is protected like
     * everyone else). On 800: the gate shut -> take the earring off, set it down on the gate; the gate open -> sit out.
     */
    private SolverStep sacrificerStep(StageView view, RunMember member, int here) {
        int id = member.characterId();
        if (gateOpen(view)) {
            return new SolverStep.Wait(2_000, "paid for Ergoth's gate (" + KING_GATE + "): its Protector Rock is on the gate, "
                    + "not on this member, so " + id + " stays out of 800/900 (decHP 30000)");
        }
        if (here != M800) {
            return null;
        }
        // short on purpose: this is the run log's line; the full account (decHP 30000 on 800/900) is the class javadoc and the row
        String why = "sacrifice: Ergoth's gate wants the only " + GqProtection.EARRING + "; " + id + " is unprotected from here on";
        if (view.wearing(id, GqProtection.EARRING)) {
            return new SolverStep.UnequipItem(GqProtection.EARRING, why);
        }
        if (view.itemCount(id, GqProtection.EARRING) > 0) {
            StageView.ReactorPlacement gate = view.reactorsOn(M800).stream()
                    .filter(r -> r.reactorId() == KING_GATE_REACTOR).findFirst().orElse(null);
            int x = gate == null ? 261 : gate.x();
            int y = gate == null ? 83 : gate.y();
            return new SolverStep.PlaceItem(GqProtection.EARRING, 1, KING_GATE, x, y, why);
        }
        return new SolverStep.Wait(2_000, "no Protector Rock (" + GqProtection.EARRING + ") worn or held, gate still shut: "
                + "either it lies in " + KING_GATE + "'s box (the server acts 5 s after it lands) or this member never "
                + "had one (the rocks' drop row is live-table unverified)");
    }

    private SolverStep gateHall(StageView view, RunMember member) {
        if (gateOpen(view)) {
            return new SolverStep.ScriptedPortal("boss00"); // kinggate_open.js
        }
        String payer = sacrificer(view).map(s -> String.valueOf(s.characterId())).orElse("nobody (no bot to pay)");
        return new SolverStep.Wait(1_500, "Ergoth's gate (" + KING_GATE + ") is shut: waiting for the sacrificer, " + payer
                + ", to set its " + GqProtection.EARRING + " in the box");
    }

    private SolverStep bossHall(StageView view, RunMember member, Optional<RunMember> sac) {
        int id = member.characterId();
        if (!bossSpawned(view)) {
            StageView.ReactorPlacement reactor = bossReactor(view);
            if (reactor != null) {
                view.stageMemory().putIfAbsent(SEEN_REACTOR, Boolean.TRUE);
            }
            boolean spent = reactor != null ? reactor.state() >= BOSS_REACTOR_SPENT
                    : view.stageMemory().containsKey(SEEN_REACTOR);
            if (reactor == null && !spent) {
                return new SolverStep.Wait(2_000, "no reactor " + BOSS_REACTOR + " (\"" + BOSS_REACTOR_NAME + "\") on 990000900 "
                        + "to wake Ergoth with");
            }
            if (spent) {
                // the reactor has done its part and Ergoth has not been seen: he is about to appear - or he came and went
                // before this stage was watching (a restart, a fight faster than the ticks), which must not wait for ever
                Object since = view.stageMemory().putIfAbsent(SPENT_SINCE, view.nowMs());
                if (since instanceof Long t && view.nowMs() - t >= SPAWN_GRACE_MS) {
                    view.stageMemory().putIfAbsent(SEEN_BOSS, Boolean.TRUE);
                    return new SolverStep.Wait(1_000, "Ergoth never showed himself in " + SPAWN_GRACE_MS / 1_000
                            + " s after the boss reactor was spent: looking for the Rubian (" + RUBIAN + ")");
                }
                return new SolverStep.Wait(1_000, "the boss reactor is spent: waiting for Ergoth to appear");
            }
            boolean mine = hitter(view, sac).map(h -> h.characterId() == id).orElse(false);
            return mine ? new SolverStep.HitReactorNamed(BOSS_REACTOR_NAME)
                    : new SolverStep.Wait(1_000, "waiting for the party's hitter to wake Ergoth (reactor " + BOSS_REACTOR + ")");
        }
        int leaderId = view.leader().map(RunMember::characterId).orElse(-1);
        if (view.itemCount(id, RUBIAN) > 0) {
            if (member.leader()) {
                return new SolverStep.TalkNpc(SOUL, RUBIAN, 1);
            }
            return leaderId > 0 ? new SolverStep.DropFor(RUBIAN, 1, leaderId)
                    : new SolverStep.Wait(1_000, "holding the Rubian with no leader to give it to");
        }
        if (view.floorItemsOn(M900, RUBIAN, id) > 0) {
            return new SolverStep.Loot(RUBIAN);
        }
        if (FOES.stream().anyMatch(m -> view.mobsAliveOn(M900, m) > 0)) {
            return new SolverStep.Fight(FOES, 0, 0);
        }
        return new SolverStep.Wait(2_000, "Ergoth (" + ERGOTH + ") is down but nobody holds or sees the Rubian (" + RUBIAN
                + "): its drop row is seed data, live table unverified");
    }
}
