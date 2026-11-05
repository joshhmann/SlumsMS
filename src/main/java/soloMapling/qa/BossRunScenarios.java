package soloMapling.qa;

import client.Character;
import server.expeditions.ExpeditionType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boss runs, 1.6.4 ex-2: does a boss fight happen at all, and what does it look like from the outside?
 *
 * <p>{@code ex-1} proves bots join an expedition. It stops there: nobody enters, nobody clicks the Eye of Fire, and no
 * bot swings. {@code ex-2} carries one Zakum run all the way through the real path - the channel expedition Adobis
 * makes, {@code ZakumBattle}'s {@code startInstance} (which registers and warps everyone on the recruiting map), the GM
 * opening the altar with the call {@code StepExecutor} uses - and then samples the room once a second for a minute and
 * a half: per bot and per alt, its map, whether the mechanics run holds it, the mob its last swing was aimed at,
 * whether a swing from where it stands reaches that mob, and what it has landed. Every {@code Abandon} and every
 * {@code gave up} the run reports is printed as it appears.
 *
 * <p>Run it twice from the same build, once with the boss switches on and once off, with the same command; the log
 * says which one it was ({@code EX2 START ... slums.mechanics.boss=...}), so the two runs are comparable.
 *
 * <p>The alt is optional: with no argument the run stages the GM and the party bots only, which is what auto-QA can
 * make (it cannot create a named alt), so {@code t-ex-2} proves the fight on every build. The run always ends on an
 * {@code EX2 CHECK} line - members reached the room, was a boss mob targeted, did a swing from where the bot stood
 * reach it, did swings land damage, was the altar opened, was anything abandoned or wedged - because that line is what
 * the check reads, in both switch settings.
 */
final class BossRunScenarios {

    /** The map Adobis stands on: the expedition only registers who is standing here. */
    static final int RECRUIT_MAP = 211042400;
    /** Zakum's altar room: the three real players' Zakum map. */
    static final int ALTAR_MAP = 280030000;

    private BossRunScenarios() {
    }

    /** Chaos Zakum's altar room (one map id over from the normal one). */
    static final int CHAOS_ALTAR_MAP = 280030001;
    /** The level a Chaos Zakum run asks for (the row's minLevel); ex-3 raises the GM to it for the run. */
    static final int CHAOS_LEVEL = 120;

    static List<QaScenario> all() {
        return List.of(new Ex2ZakumRoom(), new Ex2ZakumRoom(Variant.CHAOS));
    }

    /** What differs between the normal Zakum run (ex-2) and the Chaos Zakum run (ex-3). */
    enum Variant {
        NORMAL("ex-2", "EX2", "Zakum", ExpeditionType.ZAKUM, ALTAR_MAP, 8800000, 8810010,
                "A GM and party bots fight Zakum in the real expedition"),
        CHAOS("ex-3", "EX3", "Chaos Zakum", ExpeditionType.CHAOS_ZAKUM, CHAOS_ALTAR_MAP, 8800100, 8800110,
                "A GM and party bots fight Chaos Zakum in the real expedition");

        final String id;
        final String tag;
        final String boss;
        final ExpeditionType type;
        final int altarMap;
        final int mobFrom;
        final int mobTo;
        final String title;

        Variant(String id, String tag, String boss, ExpeditionType type, int altarMap, int mobFrom, int mobTo,
                String title) {
            this.id = id;
            this.tag = tag;
            this.boss = boss;
            this.type = type;
            this.altarMap = altarMap;
            this.mobFrom = mobFrom;
            this.mobTo = mobTo;
            this.title = title;
        }

        boolean chaos() {
            return this == CHAOS;
        }
    }

    /** ex-2: the GM, two or more party bots and his own alt, through Adobis's path into Zakum's altar. */
    static final class Ex2ZakumRoom implements QaScenario {
        /**
         * Party bots staged next to the GM when no alt comes: the Zakum row takes 6-30 members
         * (Orchestrator: {@code zakum takes 6-30 members, not 5} ends the run before it starts), and a party holds six.
         * With an alt the alt is the sixth.
         */
        static final int BOTS = 5;
        static final int BOTS_WITH_ALT = 4;
        static final int MIN_SECONDS = 90;
        static final int MAX_SECONDS = 150;

        /** The run this scenario staged, or null before it stages one. */
        private volatile Run run;
        private final Variant v;

        Ex2ZakumRoom() {
            this(Variant.NORMAL);
        }

        Ex2ZakumRoom(Variant v) {
            this.v = v;
        }

        public String id() {
            return v.id;
        }

        public String title() {
            return v.title;
        }

        public String usage() {
            return "[your alt's name]";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(QaLog.Topic.PQ, QaLog.Topic.ATTACK);
        }

        public int minutes(String[] args) {
            return 4;
        }

        public int tickSeconds() {
            return 1; // one sample a second, as the report reads it
        }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            if (v.chaos() && !Boolean.getBoolean("slums.chaos.zakum")) {
                throw new QaRefusal("Chaos Zakum is switched off on this server (-Dslums.chaos.zakum=true).");
            }
            if (!v.chaos() && gm.getLevel() < v.type.getMinLevel()) {
                throw new QaRefusal("Zakum takes level " + v.type.getMinLevel() + "+: level up first.");
            }
            if (gm.getParty() != null && gm.getParty().getLeaderId() != gm.getId()) {
                throw new QaRefusal("Only a party leader brings his bots: leave your party or lead it.");
            }
            String asked = ctx.arg(0, "");
            String altName = asked == null ? "" : asked.trim(); // no name: GM and party bots only (t-ex-2)
            if (v.chaos() && gm.getLevel() < CHAOS_LEVEL) {
                // the party bots are made at the GM's level, and the expedition takes level 120+: raise him for the
                // run and put him back at the stop (the bots follow his level when they are staged, below)
                int was = gm.getLevel();
                MechanicsScenarios.setLevel(gm, CHAOS_LEVEL);
                ctx.onStop("put your level back to " + was, () -> MechanicsScenarios.setLevel(gm, was));
            }
            if (!ctx.world().warp(gm, RECRUIT_MAP)) {
                throw new QaRefusal("Map " + RECRUIT_MAP + " (where Adobis stands) does not exist here.");
            }
            List<Integer> bots = ctx.world().stageDojoFollowers(gm, altName.isEmpty() ? BOTS : BOTS_WITH_ALT);
            if (bots.size() < 2) {
                ctx.world().unstageDojoFollowers(bots);
                throw new QaRefusal("At least two follower bots are needed (is your party full?).");
            }
            ctx.onStop("take " + bots.size() + " party bot(s) out of the party and the world",
                    () -> ctx.world().unstageDojoFollowers(bots));
            int altId = 0;
            if (!altName.isEmpty()) {
                altId = ctx.world().ex2StageAlt(gm, altName);
                if (altId <= 0) {
                    throw new QaRefusal("Your alt " + altName + " could not be summoned (see the yellow message).");
                }
                String going = altName;
                ctx.onStop("send " + going + " home", () -> ctx.world().ex2DismissAlt(gm, going));
            }
            ctx.onStop("end the " + v.boss + " expedition and its instance", () -> ctx.world().ex2EndExpedition(gm));
            ctx.onStop("stop sampling", () -> run = null);

            boolean started = v.chaos() ? ctx.world().ex3StartChaosZakum(gm) : ctx.world().ex2StartZakum(gm);
            if (!started) {
                throw new QaRefusal("The " + v.boss + " expedition could not start (another channel expedition in"
                        + " progress" + (v.chaos() ? ", or the bots' 25 Chaos Zakum runs for today are used up" : "")
                        + "?).");
            }
            List<Integer> members = new ArrayList<>();
            members.add(gm.getId());
            members.addAll(bots);
            if (altId > 0) {
                members.add(altId);
            }
            run = new Run(members, System.currentTimeMillis());
            String who = altName.isEmpty() ? "none" : altName;
            ctx.log(v.tag + " START members=" + members.size() + " bots=" + bots.size() + " alt=" + who
                    + " sample=" + MIN_SECONDS + "s/" + MAX_SECONDS + "s " + ctx.world().ex2Switches());
            ctx.run().note(v.tag, "START members=" + members.size() + " bots=" + bots.size() + " alt=" + who
                    + " " + ctx.world().ex2Switches());

            List<String> out = new ArrayList<>();
            out.add("You are in the " + v.boss + " expedition with " + bots.size() + " party bot(s)"
                    + (altName.isEmpty() ? "" : " and your alt " + altName)
                    + "; the script has been asked to start the fight, as Adobis's 'Start the fight!' does.");
            out.add("The Eye of Fire needs nobody to hold an item: this scenario's GM clicks reactor 2111001 once the"
                    + " room has two of you in it, with the same call StepExecutor uses.");
            out.add("One " + v.tag + " SEEN line a second per member, " + v.tag + " PARTS and " + v.tag
                    + " RUN every ten seconds, then " + v.tag + " CHECK (what the window proved) and " + v.tag
                    + " PASS, BASELINE, FAIL or END. Wait about "
                    + MAX_SECONDS + " s, then !qa stop.");
            return out;
        }

        public void tick(QaContext ctx) {
            Run r = run;
            if (r == null) {
                return;
            }
            long now = System.currentTimeMillis();
            int second = (int) ((now - r.startedMs) / 1000);
            List<QaWorld.Ex2Seen> seen = ctx.world().ex2See(ctx.gm(), r.members);
            String state = ctx.world().ex2Run(ctx.gm());
            r.sawAbandon |= state.contains("Abandon");
            r.sawStuck |= state.contains("could not be put in map");
            r.sawRun |= !state.equals("no run");
            int inRoom = 0;
            r.swings = 0;
            r.damage = 0;
            for (QaWorld.Ex2Seen s : seen) {
                long[] base = r.baseline.computeIfAbsent(s.charId(), id -> new long[]{s.landed(), s.damage()});
                long swings = Math.max(0, s.landed() - base[0]);
                long damage = Math.max(0, s.damage() - base[1]);
                r.swings += swings;
                r.damage += damage;
                if (s.mapId() == v.altarMap) {
                    inRoom++;
                    r.reached.add(s.charId());
                }
                if (bossMob(s.targetMobId(), v)) {
                    r.bossTarget = true;
                    r.reach |= s.canSwing();
                }
                r.sawAbandon |= s.doing().contains("Abandon");
                r.sawStuck |= s.doing().contains("could not be put in map");
                ctx.run().note(v.tag, "SEEN t=" + second + " " + s.name() + " " + s.kind() + " map=" + s.mapId()
                        + " held=" + flag(s.held()) + " run=" + flag(s.inRun()) + " target=" + s.targetOid() + "/"
                        + s.targetMobId() + " " + s.targetName() + " reach=" + flag(s.canSwing()) + " swings="
                        + swings + " dmg=" + damage + " doing=" + s.doing());
            }
            if (!r.altarTried && inRoom >= 2) {
                r.altarTried = true;
                String what = ctx.world().ex2Altar(ctx.gm());
                ctx.run().note(v.tag, "ALTAR t=" + second + " " + what);
                r.altarHit = what.startsWith("hit reactor");
            }
            if (second % 10 == 0) {
                ctx.run().note(v.tag, "PARTS t=" + second + " " + ctx.world().ex2Parts(ctx.gm()));
                ctx.run().note(v.tag, "RUN t=" + second + " " + state);
            }
            if (!r.verdictDone && second >= MIN_SECONDS) {
                r.verdictDone = true;
                boolean on = bossOn(ctx.world().ex2Switches());
                ctx.run().note(v.tag, check(new Facts(r.reached.size(), r.members.size(), r.bossTarget, r.reach,
                        r.swings, r.damage, r.altarHit, r.sawAbandon, r.sawStuck, on)));
                Observed o = new Observed(second, on, state.contains("cleared=true"), endedBecause(state), inRoom,
                        r.members.size(), r.swings, r.damage, r.sawAbandon, r.altarHit, true, r.sawRun);
                ctx.run().note(v.tag, verdict(v, o));
            }
        }
    }

    /**
     * The one line {@code t-ex-2} reads: what the window proved about a fight actually happening, in both switch
     * settings, so the check never has to infer it from ninety {@code SEEN} lines. {@code members} counts the staged
     * members that were seen in the altar room at least once.
     */
    static String check(Facts f) {
        return "CHECK members=" + f.reached() + "/" + f.staged() + " boss=" + flag(f.bossTarget())
                + " reach=" + flag(f.reach()) + " swings=" + f.swings() + " dmg=" + f.damage()
                + " altar=" + flag(f.altar()) + " abandoned=" + flag(f.abandoned())
                + " stuck=" + flag(f.stuck()) + " boss-on=" + flag(f.bossOn());
    }

    /** Zakum's body, its arms and the fake body: the ids a fight in the altar room aims at (8800000-8810010). */
    static boolean bossMob(int mobId) {
        return bossMob(mobId, Variant.NORMAL);
    }

    /** Chaos Zakum's body (8800100-8800102) and arms (8800103-8800110) for ex-3; Zakum's own ids for ex-2. */
    static boolean bossMob(int mobId, Variant v) {
        return mobId >= v.mobFrom && mobId <= v.mobTo;
    }

    /** Whether this server was started with the boss switches the two runs are labelled by. */
    static boolean bossOn(String switches) {
        return switches != null && switches.contains("mechanics=true")
                && switches.contains("slums.mechanics.boss=true");
    }

    /** {@code ended=...} out of a run description, or null when it has not ended. */
    static String endedBecause(String runDescription) {
        if (runDescription == null) {
            return null;
        }
        int at = runDescription.indexOf("ended=");
        if (at < 0) {
            return null;
        }
        String rest = runDescription.substring(at + "ended=".length()).trim();
        int end = rest.indexOf(' ');
        String reason = end < 0 ? rest : rest.substring(0, end);
        return reason.isEmpty() || reason.equals("-") ? null : reason;
    }

    /** What ex-2 saw over its window, so the verdict can be judged without a server. */
    record Observed(int seconds, boolean bossOn, boolean cleared, String endedBecause, int membersInRoom,
                    int membersTotal, long swings, long damage, boolean abandonSeen, boolean altarHit,
                    boolean windowOver, boolean runSeen) {
    }

    /** What {@link #check(Facts)} prints, so the line can be judged without a server. */
    record Facts(int reached, int staged, boolean bossTarget, boolean reach, long swings, long damage,
                 boolean altar, boolean abandoned, boolean stuck, boolean bossOn) {
    }

    /**
     * The one line ex-2 ends on. {@code EX2 BASELINE} when the boss switches are off (a run worth comparing, not a
     * verdict); otherwise {@code EX2 PASS} only for a clear, {@code EX2 FAIL} with the reason the fight did not happen,
     * and {@code EX2 END} for a fight still going when the window closed.
     */
    static String verdict(Observed o) {
        return verdict(Variant.NORMAL, o);
    }

    /** The same verdict for either run: the tag and the altar room come from the variant. */
    static String verdict(Variant v, Observed o) {
        String facts = "members=" + o.membersInRoom() + "/" + o.membersTotal() + " swings=" + o.swings()
                + " damage=" + o.damage() + " s=" + o.seconds();
        if (!o.bossOn()) {
            return v.tag + " BASELINE boss=off " + facts;
        }
        if (o.cleared()) {
            return v.tag + " PASS cleared " + facts;
        }
        if (o.endedBecause() != null) {
            return v.tag + " FAIL the run ended: " + o.endedBecause() + " " + facts;
        }
        if (o.abandonSeen()) {
            return v.tag + " FAIL the run abandoned " + facts;
        }
        if (!o.altarHit()) {
            return v.tag + " FAIL the Eye of Fire was never opened " + facts;
        }
        if (!o.runSeen()) {
            // the members fought by themselves with nothing driving the room (a party under the row's minimum ends
            // its run at the start): that is a staging or arming fault, never "still fighting"
            return v.tag + " FAIL no mechanics run was ever armed for the room " + facts;
        }
        if (o.membersInRoom() == 0) {
            return v.tag + " FAIL nobody reached " + v.altarMap + " " + facts;
        }
        if (o.swings() == 0) {
            return v.tag + " FAIL no member swung in " + o.seconds() + " s " + facts;
        }
        if (o.damage() == 0) {
            return v.tag + " FAIL " + o.swings() + " swing(s) landed no damage " + facts;
        }
        if (o.windowOver()) {
            return v.tag + " END still fighting when the window closed " + facts;
        }
        return v.tag + " FAIL nothing decided it " + facts;
    }

    private static String flag(boolean value) {
        return value ? "yes" : "no";
    }

    /** One staged run's sampling state. */
    static final class Run {
        final List<Integer> members;
        final long startedMs;
        final Map<Integer, long[]> baseline = new LinkedHashMap<>();
        final Set<Integer> reached = new java.util.LinkedHashSet<>();
        long swings;
        long damage;
        boolean altarTried;
        boolean altarHit;
        boolean bossTarget;
        boolean reach;
        boolean sawAbandon;
        boolean sawStuck;
        boolean sawRun;
        boolean verdictDone;

        Run(List<Integer> members, long startedMs) {
            this.members = List.copyOf(members);
            this.startedMs = startedMs;
        }
    }
}
