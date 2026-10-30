package soloMapling.qa;

import client.Character;
import soloMapling.mechanics.KpqStaging;
import soloMapling.mechanics.Mechanics;
import soloMapling.mechanics.MechanicsRuns;
import soloMapling.mechanics.RunState;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.PQ;

/**
 * 1.7.0 ticket 130 (bots play the mechanics), {@code mx-1}: four fresh bots form a party on Kerning City's PQ map,
 * start the Kerning PQ as Lakelis starts it, and clear stage 1 on their own: the answerers ask Cloto (our
 * {@code scripts/npc/9020001.js}), farm exactly their answer in Ligator coupons (with a persona's 0-2 visible wrong
 * hand-ins first), trade them for passes and drop the passes by the leader, who hands them in. Pass = the stage's
 * clear property set within {@link #LIMIT_S} seconds. The bots leave the party and the world afterwards (the PQ ends).
 * Auto-QA: t-mx-1. Needs {@code -Dslums.mechanics=true}; refuses otherwise.
 */
final class MechanicsScenarios {

    /** Stage 1 must clear within this (the KPQ event gives 30 minutes for all five stages). */
    static final int LIMIT_S = 15 * 60;

    private MechanicsScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Mx1KpqStage1(), new Mx2KpqFull(), new Mx3StopLeavesNobody(), new Mx4TwoChannels(),
                new Mx5PlayerLedWithAlts());
    }

    /**
     * mx-2: the whole PQ must clear within 25 minutes (KPQ's own timer is 30), so the prizes and the bonus (at most
     * 150 s each) still fit the QA suite's 31-minute box.
     */
    static final int FULL_LIMIT_S = 25 * 60;

    /**
     * The line mx-2 logs when the run ends: PASS when every stage cleared within the PQ's time and every bot took its
     * prize (reached the bonus), else FAIL with the reason.
     */
    static String fullVerdict(boolean cleared, long clearedAfterMs, String endedBecause, int wrongTries, int bots,
                              int prizes) {
        long secs = clearedAfterMs / 1000;
        if (cleared && secs <= FULL_LIMIT_S && prizes >= bots) {
            return "MX2 PASS cleared kpq in " + secs + " s (limit " + FULL_LIMIT_S + " s) by " + bots + " bots, "
                    + wrongTries + " wrong tries shown, " + prizes + " prizes taken";
        }
        if (cleared && secs > FULL_LIMIT_S) {
            return "MX2 FAIL cleared kpq only after " + secs + " s (limit " + FULL_LIMIT_S + " s)";
        }
        if (cleared) {
            return "MX2 FAIL cleared kpq in " + secs + " s but only " + prizes + " of " + bots + " bots took a prize";
        }
        return "MX2 FAIL " + endedBecause;
    }

    /** How many bots reached the bonus (the prize conversation sends them there), from the run's events. */
    static int prizes(RunState r) {
        return (int) r.events().stream().filter(e -> e.line().contains(" hunts the bonus for ")).count();
    }

    /**
     * 1.6.1 KPQ automation, {@code mx-2}: four fresh bots start Kerning PQ at Lakelis (the bot leader talks to him
     * through his script) and play it to the end on their own: stage 1's coupons, the ropes, platforms and barrels
     * (the leader checks each formation with Cloto, wrong ones first by persona), stage 5's bosses and passes, then
     * each bot claims its prize from Cloto, hunts the bonus and leaves through Nella. Pass = the PQ cleared within
     * {@link #FULL_LIMIT_S} s and every bot reached the bonus. Auto-QA: t-mx-2. Needs {@code -Dslums.mechanics=true}.
     */
    static final class Mx2KpqFull implements QaScenario {

        public String id() {
            return "mx-2";
        }

        public String title() {
            return "KPQ automation: four bots play all of Kerning PQ on their own, prize and bonus included (1.6.1)";
        }

        public String usage() {
            return "[perfect]";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(KpqStaging.RECRUIT_MAP, 103000800, 103000801, 103000802, 103000803, 103000804, 103000805);
        }

        public int minutes(String[] args) {
            return 31;
        }

        public List<String> stage(QaContext ctx) {
            if (!Mechanics.enabled()) {
                throw new QaRefusal("Mechanics is off: the server needs -D" + Mechanics.FLAG + "=true.");
            }
            boolean perfect = ctx.args() != null && ctx.args().length > 0
                    && "perfect".equals(ctx.args()[0].toLowerCase(Locale.ROOT));
            KpqStaging.Staged s = KpqStaging.start(ctx.gm(), false, 4, perfect, FULL_LIMIT_S * 1000L, 0,
                    line -> ctx.run().note("MX2", line), done -> {
                    });
            if (!s.ok()) {
                String why = s.error() == null ? "the run ended at once" : s.error();
                throw new QaRefusal("mx-2 could not stage: " + why);
            }
            MechanicsRuns.Active a = s.active();
            ctx.onStop("stop the KPQ run and take the " + s.spawned().size() + " bots away", () -> {
                MechanicsRuns.get().stop(a, "QA stop");
                KpqStaging.cleanup(s.spawned());
            });
            ctx.run().note("MX2", "MX2 START kpq with " + a.bots().size() + " bots in " + a.instanceName()
                    + (perfect ? " (perfect)" : ""));
            Thread watcher = new Thread(() -> {
                RunState r = a.run();
                long deadline = System.currentTimeMillis() + (FULL_LIMIT_S + 5 * 60) * 1000L;
                while (!r.ended() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                ctx.run().note("MX2", fullVerdict(r.cleared(), r.clearedAfterMs(),
                        r.ended() ? r.endedBecause() : "no end within the limit", r.wrongShownTotal(),
                        a.bots().size(), prizes(r)));
            }, "qa-mx-2");
            watcher.setDaemon(true);
            watcher.start();
            return List.of(
                    a.bots().size() + " bots are in Kerning PQ (" + a.instanceName() + ") and play all five stages:"
                            + " coupons, ropes, platforms, barrels, then the bosses' passes.",
                    "Then each takes its prize from Cloto, hunts the bonus and leaves through Nella; a RUN line per"
                            + " step and one verdict line at the end."); // no verdict word: a waitFor must not match
        }
    }

    /** The line mx-1 logs when the run ends: PASS when the stage cleared in time, else FAIL with the reason. */
    static String verdict(boolean cleared, long clearedAfterMs, String endedBecause, int wrongTries, int bots) {
        long secs = clearedAfterMs / 1000;
        if (cleared && secs <= LIMIT_S) {
            return "MX1 PASS cleared stage 1 in " + secs + " s (limit " + LIMIT_S + " s) by " + bots + " bots, "
                    + wrongTries + " wrong tries shown";
        }
        if (cleared) {
            return "MX1 FAIL cleared stage 1 only after " + secs + " s (limit " + LIMIT_S + " s)";
        }
        return "MX1 FAIL " + endedBecause;
    }

    static final class Mx1KpqStage1 implements QaScenario {

        public String id() {
            return "mx-1";
        }

        public String title() {
            return "Bots play the mechanics: four bots clear Kerning PQ stage 1 on their own (1.7.0)";
        }

        public String usage() {
            return "[perfect]";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(KpqStaging.RECRUIT_MAP, 103000800);
        }

        public int minutes(String[] args) {
            return 18;
        }

        public List<String> stage(QaContext ctx) {
            if (!Mechanics.enabled()) {
                throw new QaRefusal("Mechanics is off: the server needs -D" + Mechanics.FLAG + "=true.");
            }
            boolean perfect = ctx.args() != null && ctx.args().length > 0
                    && "perfect".equals(ctx.args()[0].toLowerCase(Locale.ROOT));
            KpqStaging.Staged s = KpqStaging.start(ctx.gm(), false, 4, perfect, LIMIT_S * 1000L, 1,
                    line -> ctx.run().note("MX1", line), done -> {
                    });
            if (!s.ok()) {
                String why = s.error() == null ? "the run ended at once" : s.error();
                if (why.contains("another party is in KPQ")) {
                    why += " (wait for it)";
                }
                throw new QaRefusal("mx-1 could not stage: " + why);
            }
            MechanicsRuns.Active a = s.active();
            ctx.onStop("stop the KPQ run and take the " + s.spawned().size() + " bots away", () -> {
                MechanicsRuns.get().stop(a, "QA stop");
                KpqStaging.cleanup(s.spawned());
            });
            ctx.run().note("MX1", "MX1 START kpq stage 1 with " + a.bots().size() + " bots in " + a.instanceName()
                    + (perfect ? " (perfect)" : ""));
            // the verdict line once the run ends (the tick thread calls the reporter with "RUN end ...")
            Thread watcher = new Thread(() -> {
                RunState r = a.run();
                long deadline = System.currentTimeMillis() + (LIMIT_S + 120) * 1000L;
                while (!r.ended() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                ctx.run().note("MX1", verdict(r.cleared(), r.clearedAfterMs(),
                        r.ended() ? r.endedBecause() : "no end within the limit", r.wrongShownTotal(),
                        a.bots().size()));
            }, "qa-mx-1");
            watcher.setDaemon(true);
            watcher.start();
            return List.of(
                    a.bots().size() + " bots are in Kerning PQ stage 1 (" + a.instanceName() + "). They ask Cloto,"
                            + " farm Ligator coupons, hand them in for passes and give the passes to their leader.",
                    "The log has a RUN line per step (answers, hand-ins with wrong tries, drops) and a verdict line at"
                            + " the end; the bots leave 20 s after."); // names no verdict word: a waitFor must not match it
        }
    }

    // ── 1.6.2 (bot audit coverage): a stopped run leaves nobody inside, two channels at once, a player-led party ──

    /** Who is still around once a run has ended, for the verdicts below. */
    record Leftover(boolean disposed, int inside, int held, int inEvent) {

        static Leftover of(MechanicsRuns.Active a, List<Character> watched) {
            scripting.event.EventInstanceManager eim = a.instance();
            int held = 0;
            int inEvent = 0;
            for (Character c : watched) {
                if (soloMapling.mechanics.MechanicsHold.committed(c)) {
                    held++;
                }
                if (c.getEventInstance() == eim) {
                    inEvent++;
                }
            }
            return new Leftover(eim.isEventDisposed(), eim.isEventDisposed() ? 0 : eim.getPlayers().size(), held,
                    inEvent);
        }

        boolean clean() {
            return disposed && inside == 0 && held == 0 && inEvent == 0;
        }

        String describe() {
            return "instance " + (disposed ? "disposed" : "ALIVE with " + inside + " inside") + ", " + held
                    + " still held, " + inEvent + " still registered";
        }
    }

    /** Waits until the run has ended and its instance is gone (or {@code maxMs}); true when both. */
    static boolean awaitGone(MechanicsRuns.Active a, long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            if (a.run().ended() && a.instance().isEventDisposed()) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return a.run().ended() && a.instance().isEventDisposed();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void requireMechanics() {
        if (!Mechanics.enabled()) {
            throw new QaRefusal("Mechanics is off: the server needs -D" + Mechanics.FLAG + "=true.");
        }
    }

    /**
     * mx-3 (1.6.2, TEST 10-30 14:15 and bot audit K3/K7): four bots start Kerning PQ; 30 s in the run is stopped as
     * {@code !mechanics stop} stops it. PASS = within 10 s the instance is disposed, nobody is inside, no bot is held
     * or still registered to it (before 1.6.2 it stayed for its 30-minute timer and Lakelis refused the next party).
     */
    static final class Mx3StopLeavesNobody implements QaScenario {

        public String id() {
            return "mx-3";
        }

        public String title() {
            return "KPQ automation: a stopped run leaves nobody inside and frees the channel's KPQ (1.6.2)";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(KpqStaging.RECRUIT_MAP, 103000800, 103000890);
        }

        public int minutes(String[] args) {
            return 3;
        }

        public List<String> stage(QaContext ctx) {
            requireMechanics();
            KpqStaging.Staged s = KpqStaging.start(ctx.gm(), false, 4, true, 0, 0,
                    line -> ctx.run().note("MX3", line), done -> {
                    });
            if (!s.ok()) {
                throw new QaRefusal("mx-3 could not stage: " + (s.error() == null ? "the run ended at once" : s.error()));
            }
            MechanicsRuns.Active a = s.active();
            ctx.onStop("take the " + s.spawned().size() + " bots away", () -> {
                MechanicsRuns.get().stop(a, "QA stop");
                KpqStaging.cleanup(s.spawned());
            });
            ctx.run().note("MX3", "MX3 START kpq with " + a.bots().size() + " bots in " + a.instanceName());
            List<Character> bots = a.bots();
            Thread t = new Thread(() -> {
                sleep(30_000);
                MechanicsRuns.get().stop(a, "stopped by mx-3");
                awaitGone(a, 10_000);
                Leftover left = Leftover.of(a, bots);
                ctx.run().note("MX3", (left.clean() ? "MX3 PASS stopped run left nobody inside: "
                        : "MX3 FAIL stopped run left: ") + left.describe());
            }, "qa-mx-3");
            t.setDaemon(true);
            t.start();
            return List.of("Four bots are in Kerning PQ (" + a.instanceName() + "); the run is stopped 30 s in.",
                    "One line at the end says whether the instance went away with it.");
        }
    }

    /**
     * mx-4 (1.6.2, bot audit b113): bot-only Kerning PQ runs on two channels at once. Every channel names its first
     * KPQ "Kerning0", and runs used to be kept by that name, so the second was refused. PASS = both run at the same
     * time (each under its own channel's name), then both are stopped and both instances are gone.
     */
    static final class Mx4TwoChannels implements QaScenario {

        public String id() {
            return "mx-4";
        }

        public String title() {
            return "KPQ automation: two channels run Kerning PQ at once (1.6.2, b113)";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(KpqStaging.RECRUIT_MAP, 103000800);
        }

        public int minutes(String[] args) {
            return 3;
        }

        public List<String> stage(QaContext ctx) {
            requireMechanics();
            Character gm = ctx.gm();
            net.server.channel.Channel here = gm.getClient().getChannelServer();
            net.server.channel.Channel other = gm.getWorldServer().getChannels().stream()
                    .filter(c -> c != null && c.getId() != here.getId()).findFirst().orElse(null);
            if (other == null) {
                throw new QaRefusal("mx-4 needs a second channel");
            }
            List<KpqStaging.Staged> staged = new java.util.ArrayList<>();
            ctx.onStop("stop both KPQ runs and take their bots away", () -> {
                for (KpqStaging.Staged s : staged) {
                    MechanicsRuns.get().stop(s.active(), "QA stop");
                    KpqStaging.cleanup(s.spawned());
                }
            });
            for (net.server.channel.Channel ch : List.of(here, other)) {
                KpqStaging.Staged s = KpqStaging.start(ch, gm, false, 3, true, 0, 1, 0,
                        line -> ctx.run().note("MX4", line), done -> {
                        });
                if (!s.ok()) {
                    ctx.run().note("MX4", "MX4 FAIL channel " + ch.getId() + " could not start: " + s.error());
                    return List.of("Channel " + ch.getId() + " could not start its run: " + s.error());
                }
                staged.add(s);
            }
            MechanicsRuns.Active a = staged.get(0).active();
            MechanicsRuns.Active b = staged.get(1).active();
            ctx.run().note("MX4", "MX4 START " + a.instanceName() + " and " + b.instanceName());
            Thread t = new Thread(() -> {
                sleep(20_000);
                boolean both = !a.run().ended() && !b.run().ended()
                        && MechanicsRuns.get().runFor(a.instance()) == a && MechanicsRuns.get().runFor(b.instance()) == b;
                String names = a.instanceName() + " / " + b.instanceName();
                MechanicsRuns.get().stop(a, "stopped by mx-4");
                MechanicsRuns.get().stop(b, "stopped by mx-4");
                boolean gone = awaitGone(a, 10_000) & awaitGone(b, 10_000);
                ctx.run().note("MX4", both && gone ? "MX4 PASS two channels ran KPQ at once (" + names + "), both gone after"
                        : "MX4 FAIL " + (both ? "" : "not both running 20 s in (" + a.run().endedBecause() + " / "
                        + b.run().endedBecause() + ") ") + (gone ? "" : "an instance outlived its run"));
            }, "qa-mx-4");
            t.setDaemon(true);
            t.start();
            return List.of("Two bot-only KPQ runs: " + a.instanceName() + " and " + b.instanceName() + ".",
                    "20 s in both must be running; then both are stopped and must be gone.");
        }
    }

    /** A level for the check; the party list (which KPQ's eligibility reads) is told, as a level-up tells it. */
    static void setLevel(Character c, int level) {
        c.setLevel(level);
        c.setMPC(new net.server.world.PartyCharacter(c));
        c.silentPartyUpdate();
    }

    /** mx-5: the QA account's two alts (t-oa-1 makes them) and the level they play KPQ at for this check. */
    static final String[] MX5_ALTS = {"AutoQaAlt", "AutoQaAltB"};
    static final int MX5_ALT_LEVEL = 30;

    /**
     * mx-5 (1.6.2, bot audit K3/K4 and the coverage gap): the pretend player leads a party of his two owned alts and
     * one bot into Kerning PQ. The run drives the alts like the bot (they ask Cloto and farm); the leader does nothing,
     * so a bot must remind him once the passes are in (or the check gives that up after 6 minutes); then the run is
     * stopped. PASS = both alts were driven (each got a Cloto question), and after the stop nobody is inside, no alt
     * or bot is held or registered. The alts go home and get their levels back.
     */
    static final class Mx5PlayerLedWithAlts implements QaScenario {

        public String id() {
            return "mx-5";
        }

        public String title() {
            return "KPQ automation: a player leads his alts and a bot; the stop leaves nobody inside (1.6.2)";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ, QaLog.Topic.OWNED_ALT);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(KpqStaging.RECRUIT_MAP, 103000800, 103000890);
        }

        public int minutes(String[] args) {
            return 9;
        }

        public List<String> stage(QaContext ctx) {
            requireMechanics();
            var cfg = config.YamlConfig.config.server;
            if (!cfg.OWNED_ALTS_ENABLED || !cfg.OWNED_ALTS_SUMMON_ENABLED) {
                throw new QaRefusal("owned alts are off here (OWNED_ALTS_ENABLED / OWNED_ALTS_SUMMON_ENABLED)");
            }
            Character gm = ctx.gm();
            if (gm.getLevel() < 21) {
                throw new QaRefusal("the pretend player is level " + gm.getLevel() + "; KPQ takes 21+");
            }
            if (gm.getEventInstance() != null) {
                throw new QaRefusal("the pretend player is in an event");
            }
            if (gm.getParty() != null) {
                gm.leaveParty();
            }
            var recruit = gm.getClient().getChannelServer().getMapFactory().getMap(KpqStaging.RECRUIT_MAP);
            gm.changeMap(recruit, recruit.getPortal(0));
            net.server.world.Party.createParty(gm, false);
            soloMapling.companions.OwnedAltService alts = soloMapling.companions.OwnedAltService.getInstance();
            List<Character> called = new java.util.ArrayList<>();
            java.util.Map<Integer, Integer> levels = new java.util.HashMap<>();
            ctx.onStop("send the alts home with their levels back", () -> {
                for (Character alt : called) {
                    Integer was = levels.get(alt.getId());
                    if (was != null) {
                        setLevel(alt, was);
                    }
                    try {
                        alts.dismiss(gm, alt.getName());
                    } catch (RuntimeException e) {
                        ctx.run().note("MX5", "could not dismiss " + alt.getName() + ": " + e.getMessage());
                    }
                }
            });
            for (String name : MX5_ALTS) {
                try {
                    Character alt = alts.summon(gm, name);
                    if (alt != null) {
                        called.add(alt);
                        levels.put(alt.getId(), alt.getLevel());
                        if (alt.getLevel() < MX5_ALT_LEVEL) {
                            setLevel(alt, MX5_ALT_LEVEL);
                        }
                    }
                } catch (RuntimeException e) {
                    throw new QaRefusal("@botme " + name + ": " + e.getMessage() + " (t-oa-1 makes the QA alts)");
                }
            }
            sleep(2_000);
            KpqStaging.Staged s = KpqStaging.start(gm, true, 1, true, 0, 0, line -> ctx.run().note("MX5", line),
                    done -> {
                    });
            if (!s.ok()) {
                throw new QaRefusal("mx-5 could not stage: " + (s.error() == null ? "the run ended at once" : s.error()));
            }
            MechanicsRuns.Active a = s.active();
            ctx.onStop("stop the run and take the bot away", () -> {
                MechanicsRuns.get().stop(a, "QA stop");
                KpqStaging.cleanup(s.spawned());
            });
            List<Character> driven = a.bots();
            ctx.run().note("MX5", "MX5 START kpq led by " + gm.getName() + " with " + driven.size() + " driven ("
                    + called.size() + " alts) in " + a.instanceName());
            Thread t = new Thread(() -> {
                RunState r = a.run();
                long end = System.currentTimeMillis() + 6 * 60_000L;
                while (System.currentTimeMillis() < end && !r.ended() && r.events().stream()
                        .noneMatch(e -> e.line().startsWith("reminded the leader"))) {
                    sleep(1_000);
                }
                List<String> bad = new java.util.ArrayList<>();
                for (Character alt : called) {
                    if (driven.stream().noneMatch(c -> c.getId() == alt.getId())) {
                        bad.add(alt.getName() + " was not driven");
                    } else if (r.events().stream().noneMatch(e -> e.line().startsWith(alt.getId() + " got answer"))) {
                        bad.add(alt.getName() + " never got a Cloto question");
                    }
                }
                boolean reminded = r.events().stream().anyMatch(e -> e.line().startsWith("reminded the leader"));
                MechanicsRuns.get().stop(a, "stopped by mx-5");
                awaitGone(a, 10_000);
                Leftover left = Leftover.of(a, driven);
                if (!left.clean()) {
                    bad.add(left.describe());
                }
                ctx.run().note("MX5", bad.isEmpty() ? "MX5 PASS alts driven, " + (reminded ? "the leader was reminded"
                        : "no reminder within 6 min (passes not in yet)") + ", nobody left inside after the stop"
                        : "MX5 FAIL " + String.join("; ", bad));
            }, "qa-mx-5");
            t.setDaemon(true);
            t.start();
            return List.of(gm.getName() + " leads " + called.size() + " alts and a bot in Kerning PQ ("
                            + a.instanceName() + "); he does nothing, the bots must ask him when it is his turn.",
                    "Then the run is stopped and nobody may be left inside; the alts go home.");
        }
    }
}
