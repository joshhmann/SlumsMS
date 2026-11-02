package soloMapling.qa;

import client.Character;

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
 */
final class BossRunScenarios {

    /** The map Adobis stands on: the expedition only registers who is standing here. */
    static final int RECRUIT_MAP = 211042400;
    /** Zakum's altar room: the three real players' Zakum map. */
    static final int ALTAR_MAP = 280030000;

    private BossRunScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Ex2ZakumRoom());
    }

    /** ex-2: the GM, two or more party bots and his own alt, through Adobis's path into Zakum's altar. */
    static final class Ex2ZakumRoom implements QaScenario {
        static final int BOTS = 4;
        static final int MIN_SECONDS = 90;
        static final int MAX_SECONDS = 150;

        /** The run this scenario staged, or null before it stages one. */
        private volatile Run run;

        public String id() {
            return "ex-2";
        }

        public String title() {
            return "A GM, party bots and his alt fight Zakum in the real expedition";
        }

        public String usage() {
            return "<your alt's name>";
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
            if (gm.getLevel() < ExpeditionScenarios.TYPE.getMinLevel()) {
                throw new QaRefusal("Zakum takes level " + ExpeditionScenarios.TYPE.getMinLevel() + "+: level up first.");
            }
            if (gm.getParty() != null && gm.getParty().getLeaderId() != gm.getId()) {
                throw new QaRefusal("Only a party leader brings his bots: leave your party or lead it.");
            }
            String altName = ctx.arg(0, null);
            if (altName == null || altName.isBlank()) {
                throw new QaRefusal("Name one of your own alts: !qa run ex-2 <altName> (make one with !qa do alts).");
            }
            if (!ctx.world().warp(gm, RECRUIT_MAP)) {
                throw new QaRefusal("Map " + RECRUIT_MAP + " (where Adobis stands) does not exist here.");
            }
            List<Integer> bots = ctx.world().stageDojoFollowers(gm, BOTS);
            if (bots.size() < 2) {
                ctx.world().unstageDojoFollowers(bots);
                throw new QaRefusal("At least two follower bots are needed (is your party full?).");
            }
            ctx.onStop("take " + bots.size() + " party bot(s) out of the party and the world",
                    () -> ctx.world().unstageDojoFollowers(bots));
            int altId = ctx.world().ex2StageAlt(gm, altName);
            if (altId <= 0) {
                throw new QaRefusal("Your alt " + altName + " could not be summoned (see the yellow message).");
            }
            ctx.onStop("send " + altName + " home", () -> ctx.world().ex2DismissAlt(gm, altName));
            ctx.onStop("end the Zakum expedition and its instance", () -> ctx.world().ex2EndExpedition(gm));
            ctx.onStop("stop sampling", () -> run = null);

            if (!ctx.world().ex2StartZakum(gm)) {
                throw new QaRefusal("The Zakum expedition could not start (another channel expedition in progress?).");
            }
            List<Integer> members = new ArrayList<>();
            members.add(gm.getId());
            members.addAll(bots);
            members.add(altId);
            run = new Run(members, System.currentTimeMillis());
            ctx.log("EX2 START members=" + members.size() + " bots=" + bots.size() + " alt=" + altName
                    + " sample=" + MIN_SECONDS + "s/" + MAX_SECONDS + "s " + ctx.world().ex2Switches());
            ctx.run().note("EX2", "START members=" + members.size() + " bots=" + bots.size() + " alt=" + altName
                    + " " + ctx.world().ex2Switches());

            List<String> out = new ArrayList<>();
            out.add("You are in the Zakum expedition with " + bots.size() + " party bot(s) and your alt " + altName
                    + "; the script has been asked to start the fight, as Adobis's 'Start the fight!' does.");
            out.add("The Eye of Fire needs nobody to hold an item: this scenario's GM clicks reactor 2111001 once the"
                    + " room has two of you in it, with the same call StepExecutor uses.");
            out.add("One EX2 SEEN line a second per member, EX2 PARTS and EX2 RUN every ten seconds, then EX2 PASS,"
                    + " EX2 FAIL or EX2 END. Wait about " + MAX_SECONDS + " s, then !qa stop.");
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
            int inRoom = 0;
            r.swings = 0;
            r.damage = 0;
            for (QaWorld.Ex2Seen s : seen) {
                long[] base = r.baseline.computeIfAbsent(s.charId(), id -> new long[]{s.landed(), s.damage()});
                long swings = Math.max(0, s.landed() - base[0]);
                long damage = Math.max(0, s.damage() - base[1]);
                r.swings += swings;
                r.damage += damage;
                if (s.mapId() == ALTAR_MAP) {
                    inRoom++;
                }
                ctx.run().note("EX2", "SEEN t=" + second + " " + s.name() + " " + s.kind() + " map=" + s.mapId()
                        + " held=" + flag(s.held()) + " run=" + flag(s.inRun()) + " target=" + s.targetOid() + "/"
                        + s.targetMobId() + " " + s.targetName() + " reach=" + flag(s.canSwing()) + " swings="
                        + swings + " dmg=" + damage + " doing=" + s.doing());
            }
            if (!r.altarTried && inRoom >= 2) {
                r.altarTried = true;
                String what = ctx.world().ex2Altar(ctx.gm());
                ctx.run().note("EX2", "ALTAR t=" + second + " " + what);
                r.altarHit = what.startsWith("hit reactor");
            }
            if (second % 10 == 0) {
                ctx.run().note("EX2", "PARTS t=" + second + " " + ctx.world().ex2Parts(ctx.gm()));
                ctx.run().note("EX2", "RUN t=" + second + " " + ctx.world().ex2Run(ctx.gm()));
            }
            if (!r.verdictDone && second >= MIN_SECONDS) {
                String state = ctx.world().ex2Run(ctx.gm());
                r.verdictDone = true;
                Observed o = new Observed(second, bossOn(ctx.world().ex2Switches()), state.contains("cleared=true"), endedBecause(state),
                        inRoom, r.members.size(), r.swings, r.damage, state.toLowerCase().contains("abandon"),
                        r.altarHit, second >= MAX_SECONDS);
                ctx.run().note("EX2", verdict(o));
            }
        }
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
                    boolean windowOver) {
    }

    /**
     * The one line ex-2 ends on. {@code EX2 BASELINE} when the boss switches are off (a run worth comparing, not a
     * verdict); otherwise {@code EX2 PASS} only for a clear, {@code EX2 FAIL} with the reason the fight did not happen,
     * and {@code EX2 END} for a fight still going when the window closed.
     */
    static String verdict(Observed o) {
        String facts = "members=" + o.membersInRoom() + "/" + o.membersTotal() + " swings=" + o.swings()
                + " damage=" + o.damage() + " s=" + o.seconds();
        if (!o.bossOn()) {
            return "EX2 BASELINE boss=off " + facts;
        }
        if (o.cleared()) {
            return "EX2 PASS cleared " + facts;
        }
        if (o.endedBecause() != null) {
            return "EX2 FAIL the run ended: " + o.endedBecause() + " " + facts;
        }
        if (o.abandonSeen()) {
            return "EX2 FAIL the run abandoned " + facts;
        }
        if (!o.altarHit()) {
            return "EX2 FAIL the Eye of Fire was never opened " + facts;
        }
        if (o.membersInRoom() == 0) {
            return "EX2 FAIL nobody reached " + ALTAR_MAP + " " + facts;
        }
        if (o.swings() == 0) {
            return "EX2 FAIL no member swung in " + o.seconds() + " s " + facts;
        }
        if (o.damage() == 0) {
            return "EX2 FAIL " + o.swings() + " swing(s) landed no damage " + facts;
        }
        if (o.windowOver()) {
            return "EX2 END still fighting when the window closed " + facts;
        }
        return "EX2 FAIL nothing decided it " + facts;
    }

    private static String flag(boolean value) {
        return value ? "yes" : "no";
    }

    /** One staged run's sampling state. */
    static final class Run {
        final List<Integer> members;
        final long startedMs;
        final Map<Integer, long[]> baseline = new LinkedHashMap<>();
        long swings;
        long damage;
        boolean altarTried;
        boolean altarHit;
        boolean verdictDone;

        Run(List<Integer> members, long startedMs) {
            this.members = List.copyOf(members);
            this.startedMs = startedMs;
        }
    }
}
