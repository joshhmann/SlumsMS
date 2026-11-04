package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axis;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions.ActEvent;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialPilotSim.Reaction;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialPilotSim.Snap;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 7: THE PILOT PROOF. Twenty roster bots chosen by the real pilot selection, with seeded varied minds,
 * live seven simulated days in a fake Henesys on the {@link SocialSim} clock (see {@link SocialPilotSim} for exactly what is
 * real and what is faked), with a few visits of two real players and one simulated server restart in the middle. The test
 * prints a report and asserts each of the ten pilot criteria with its numbers, then the safety properties. The same seed
 * gives the same report.
 *
 * <p>This is a harness result: the world is a fake. What it cannot show (a client seeing the bots, the real director logging
 * a pilot bot in, the real {@code ModeSwitch} swap, real sampling of who stood near whom) needs the TEST runbook.
 */
class SocialPilotSimulationTest {

    static final long SEED = 20261103L;
    static final int DAYS = 7;

    @Test
    void twentyBotsSevenDaysTenCriteria() {
        SocialPilotSim sim = new SocialPilotSim(SEED, DAYS, true, true);
        String report;
        try {
            sim.start();
            sim.run();
            report = report(sim);
            System.out.println(report);
            criteria(sim);
            safety(sim);
        } finally {
            sim.stop();
        }
        // the same seed reproduces the same report, byte for byte
        SocialPilotSim again = new SocialPilotSim(SEED, DAYS, true, true);
        try {
            again.start();
            again.run();
            assertEquals(report, report(again), "a deterministic seed gives the same report");
        } finally {
            again.stop();
        }
        System.out.println("PILOT same seed, same report: identical (" + report.length() + " characters)");
    }

    @Test
    void theSwitchOffRunHasNoRoutineNoGroupsAndNoHold() {
        SocialPilotSim on = new SocialPilotSim(SEED, DAYS, true, false);
        SocialPilotSim off = new SocialPilotSim(SEED, DAYS, false, false);
        Map<String, Double> onM, offM;
        try {
            on.start();
            on.run();
            onM = shape(on);
        } finally {
            on.stop();
        }
        try {
            off.start();
            off.run();
            offM = shape(off);
            assertEquals(0, off.holdsApplied, "no director hold with the pilot off");
            assertEquals(0, SocialPilot.stats().get("routineBiases"), "no routine bias with the pilot off");
            assertEquals(0, SocialPilot.stats().get("groups"), "no groups with the pilot off");
            assertEquals(0, SocialPilot.stats().get("placements"), "no pilot placement with the pilot off");
            assertTrue(SocialGroups.current(off.now()).groups().isEmpty());
            assertEquals(SocialRoutine.ModeBias.NONE, SocialRoutine.modeBias(off.ids[0], off.now()));
        } finally {
            off.stop();
        }
        System.out.println("PILOT on  vs off over " + DAYS + " days:");
        for (String k : onM.keySet()) {
            System.out.printf("PILOT   %-34s on %8.2f   off %8.2f%n", k, onM.get(k), offM.get(k));
        }
        assertTrue(onM.get("bot-hours online") > offM.get("bot-hours online"),
                "the hold keeps the pilot online longer than ordinary sessions do");
    }

    @Test
    void anotherSeedGivesAnotherWeek() {
        SocialPilotSim a = new SocialPilotSim(SEED, 5, true, false), b = new SocialPilotSim(SEED + 1, 5, true, false);
        String ra, rb;
        try {
            a.start();
            a.run();
            ra = report(a);
        } finally {
            a.stop();
        }
        try {
            b.start();
            b.run();
            rb = report(b);
        } finally {
            b.stop();
        }
        assertNotEquals(ra, rb, "a different seed is a different week");
    }

    // ── the ten criteria ──

    private void criteria(SocialPilotSim s) {
        long now = s.now();
        String[] names = placeNames(s);

        // (1) independent routines: different bots at different places by hour
        {
            int hoursWithThree = 0, hoursChecked = 0;
            for (int h = 10; h <= 22; h++) {
                int distinctSum = 0, samples = 0;
                for (int d = 0; d < s.days; d++) {
                    int[] count = new int[names.length];
                    int online = 0;
                    for (int[] bot : s.place) {
                        count[bot[d * 24 + h]]++;
                        if (bot[d * 24 + h] != 0) online++;
                    }
                    int distinct = 0;
                    for (int c = 1; c < count.length; c++) {
                        if (count[c] > 0) distinct++;
                    }
                    if (online >= 6) {
                        distinctSum += distinct;
                        samples++;
                    }
                }
                if (samples > 0) {
                    hoursChecked++;
                    if (distinctSum / (double) samples >= 3.0) hoursWithThree++;
                }
            }
            // pairwise agreement of the hourly place over the hours both are online: a lock-step crowd would be 1.0
            double agree = 0;
            int pairs = 0;
            for (int i = 0; i < s.place.length; i++) {
                for (int j = i + 1; j < s.place.length; j++) {
                    int both = 0, same = 0;
                    for (int t = 0; t < s.days * 24; t++) {
                        if (s.place[i][t] != 0 && s.place[j][t] != 0) {
                            both++;
                            if (s.place[i][t] == s.place[j][t]) same++;
                        }
                    }
                    if (both >= 24) {
                        agree += same / (double) both;
                        pairs++;
                    }
                }
            }
            agree /= Math.max(1, pairs);
            // each bot's own share of its online hours spent in town; and each bot's favourite town place
            double minTown = 1, maxTown = 0;
            Set<Integer> favourites = new HashSet<>();
            for (int[] bot : s.place) {
                int online = 0, town = 0;
                int[] at = new int[names.length];
                for (int code : bot) {
                    if (code != 0) online++;
                    if (code >= 2) {
                        town++;
                        at[code]++;
                    }
                }
                double share = town / (double) Math.max(1, online);
                minTown = Math.min(minTown, share);
                maxTown = Math.max(maxTown, share);
                int fav = 2;
                for (int c = 2; c < at.length; c++) {
                    if (at[c] > at[fav]) fav = c;
                }
                favourites.add(fav);
            }
            System.out.printf("PILOT (1) routines: >=3 different places at once in %d of %d afternoon/evening hours; mean pairwise "
                            + "agreement %.2f over %d pairs (lock-step = 1.00); town share per bot %.2f..%.2f; %d different favourite places%n",
                    hoursWithThree, hoursChecked, agree, pairs, minTown, maxTown, favourites.size());
            assertTrue(hoursWithThree >= hoursChecked / 2, "bots are at three or more different places in most hours");
            assertTrue(agree < 0.65, "bots do not move in lock step: pairwise agreement " + agree);
            assertTrue(maxTown - minTown >= 0.15, "bots spend their time differently");
            assertTrue(favourites.size() >= 2, "different bots favour different places");
        }

        // (2) friendships by repeated interaction
        {
            Set<Long> scripted = new HashSet<>();
            for (Object[] e : s.axesBeforeAfter) {
                scripted.add(SocialGroups.pairKey((int) e[5], (int) e[6]));
            }
            int edges = 0, enough = 0, minInteractions = Integer.MAX_VALUE;
            List<Integer> counts = new ArrayList<>();
            Set<Long> mutual = new HashSet<>();
            for (Map.Entry<Long, long[]> e : s.firstFriend.entrySet()) {
                int a = (int) (e.getKey() >>> 32), b = (int) (long) e.getKey();
                long pk = SocialGroups.pairKey(a, b);
                if (scripted.contains(pk)) continue;
                edges++;
                long interactions = e.getValue()[1] + e.getValue()[2] + e.getValue()[3];
                counts.add((int) interactions);
                minInteractions = (int) Math.min(minInteractions, interactions);
                if (interactions >= 5) enough++;
                if (s.firstFriend.containsKey(((long) b << 32) | (a & 0xffffffffL))) mutual.add(pk);
            }
            counts.sort(Integer::compare);
            // the control: of the pairs ranked by how much they interacted, the top quarter ends up friends far more often than
            // the bottom quarter (so it is the interaction, not chance, that makes the friendship)
            List<long[]> ranked = new ArrayList<>(); // {interactions, friends(0/1)}
            for (int i = 0; i < s.ids.length; i++) {
                for (int j = i + 1; j < s.ids.length; j++) {
                    long pk = SocialGroups.pairKey(s.ids[i], s.ids[j]);
                    if (scripted.contains(pk)) continue;
                    int[] l = s.pairLedger.getOrDefault(pk, new int[4]);
                    boolean friends = s.firstFriend.containsKey(((long) s.ids[i] << 32) | (s.ids[j] & 0xffffffffL))
                            || s.firstFriend.containsKey(((long) s.ids[j] << 32) | (s.ids[i] & 0xffffffffL));
                    ranked.add(new long[]{l[0] + l[1] + l[2], friends ? 1 : 0});
                }
            }
            ranked.sort((x, y) -> Long.compare(x[0], y[0]));
            int quarter = ranked.size() / 4, hiPairs = quarter, loPairs = quarter, hiFriends = 0, loFriends = 0;
            for (int i = 0; i < quarter; i++) {
                loFriends += ranked.get(i)[1];
                hiFriends += ranked.get(ranked.size() - 1 - i)[1];
            }
            long loMax = ranked.get(quarter - 1)[0], hiMin = ranked.get(ranked.size() - quarter)[0];
            double hiRate = hiFriends / (double) Math.max(1, hiPairs), loRate = loFriends / (double) Math.max(1, loPairs);
            System.out.printf("PILOT (2) friendships: %d directed edges reached FRIEND (scripted pairs excluded), %d mutual pairs; "
                            + "interactions (NEAR+SPEND_TIME+CONVERSE) before the edge reached FRIEND: min %d, median %d, max %d; "
                            + "most-interacting quarter of pairs (>=%d interactions): %d pairs, friends %.0f%%; least-interacting quarter (<=%d): %d pairs, friends %.0f%%%n",
                    edges, mutual.size(), minInteractions == Integer.MAX_VALUE ? 0 : minInteractions,
                    counts.isEmpty() ? 0 : counts.get(counts.size() / 2), counts.isEmpty() ? 0 : counts.get(counts.size() - 1),
                    hiMin, hiPairs, 100 * hiRate, loMax, loPairs, 100 * loRate);
            assertTrue(edges >= 6, "friendships formed: " + edges);
            assertTrue(mutual.size() >= 2, "mutual friendships formed: " + mutual.size());
            assertTrue(enough >= edges * 0.9, "every friendship rests on repeated interaction (>=5): " + enough + " of " + edges);
            assertTrue(hiRate > loRate + 0.2, "more interaction, more friendship: " + hiRate + " vs " + loRate);
        }

        // friend groups (stage 7 item 3): they form from the mutual FRIEND edges and their members gather at the group's hour
        {
            SocialGroups.Snapshot snap = SocialGroups.current(now);
            assertTrue(snap.groups().size() >= 1, "a friend group formed from the bond graph");
            int lastDays = Math.min(3, s.days);
            double[] share = new double[4]; // members at the event hour, members elsewhere, others at the event hour, others elsewhere
            int[] n = new int[4];
            SocialGroups.Group g = snap.groups().get(0);
            int spotCode = 2 + g.spot();
            for (int i = 0; i < s.ids.length; i++) {
                boolean member = g.has(s.ids[i]);
                for (int d = s.days - lastDays; d < s.days; d++) {
                    for (int h = 11; h <= 23; h++) {
                        int code = s.place[i][d * 24 + h];
                        if (code < 2) continue;
                        boolean atEvent = Math.abs(h + 0.5 - g.eventHour()) <= 1.5;
                        int slot = (member ? 0 : 2) + (atEvent ? 0 : 1);
                        n[slot]++;
                        if (code == spotCode) share[slot]++;
                    }
                }
            }
            System.out.printf("PILOT (groups) %d group(s) at the end: %s; the largest has %d bots, gathers at place %d around %.1fh. In town over the last %d days, "
                            + "share of its members at that place: %.0f%% within 1.5 h of the hour, %.0f%% otherwise; other pilot bots: %.0f%% and %.0f%%%n",
                    snap.groups().size(), SocialGroups.describe(now), g.size(), g.spot(), g.eventHour(), lastDays,
                    100 * share[0] / Math.max(1, n[0]), 100 * share[1] / Math.max(1, n[1]), 100 * share[2] / Math.max(1, n[2]),
                    100 * share[3] / Math.max(1, n[3]));
        }

        // (3) conflicting personalities choose different acts in the same situation
        {
            int peace = -1, hot = -1;
            double best = -1e9, worst = 1e9;
            for (int id : s.ids) {
                SocialContext.Self me = SocialActions.selfOf(id, now);
                double score = me.t(Trait.AGREEABLENESS) + me.t(Trait.PATIENCE) + me.t(Trait.EMPATHY) - me.t(Trait.IMPULSIVENESS);
                if (score > best) {
                    best = score;
                    peace = id;
                }
                if (score < worst) {
                    worst = score;
                    hot = id;
                }
            }
            SocialContext.Self p = SocialActions.selfOf(peace, now), h = SocialActions.selfOf(hot, now);
            int differ = 0;
            StringBuilder lines = new StringBuilder();
            for (Scenario sc : Scenario.values()) {
                SocialActKind pa = argmax(sc.context(peace, s.ids[0] == peace ? s.ids[1] : s.ids[0], p, now));
                SocialActKind ha = argmax(sc.context(hot, s.ids[0] == hot ? s.ids[1] : s.ids[0], h, now));
                if (pa != ha) differ++;
                lines.append(String.format("%n         %-22s peacemaker (bot %d) -> %-10s hothead (bot %d) -> %s", sc.label,
                        peace - BotStore.BOT_ID_BASE, pa, hot - BotStore.BOT_ID_BASE, ha));
            }
            System.out.printf("PILOT (3) personalities: most agreeable/patient vs most impulsive/disagreeable, same situation:%s%n"
                    + "         agreeableness %.2f vs %.2f, impulsiveness %.2f vs %.2f; choices differ in %d of %d situations%n", lines,
                    p.t(Trait.AGREEABLENESS), h.t(Trait.AGREEABLENESS), p.t(Trait.IMPULSIVENESS), h.t(Trait.IMPULSIVENESS), differ,
                    Scenario.values().length);
            assertTrue(differ >= 2, "different personalities choose different acts in the same situation: " + differ);
            // and over the week: who argued vs who made peace
            double argue = mean(s, SocialActKind.ARGUE, Trait.AGREEABLENESS), sorry = mean(s, SocialActKind.APOLOGIZE, Trait.AGREEABLENESS);
            System.out.printf("         over the week: mean agreeableness of ARGUE actors %.2f, of APOLOGIZE actors %.2f%n", argue, sorry);
            assertTrue(argue < sorry, "the ones who argue are less agreeable than the ones who apologize");
        }

        // (4) relationships change after meaningful events
        {
            int moved = 0;
            for (Object[] e : s.axesBeforeAfter) {
                BondEvent ev = (BondEvent) e[0];
                Axes before = (Axes) e[1], after = (Axes) e[2];
                switch (ev) {
                    case GIFT -> {
                        assertTrue(after.affection() > before.affection() + 0.05, "a gift warms the receiver's edge");
                        moved++;
                    }
                    case KS -> {
                        assertTrue(after.resentment() > before.resentment() + 0.05 && after.trust() < before.trust(),
                                "a stolen mob sours the victim's edge");
                        moved++;
                    }
                    case PQ -> {
                        assertTrue(after.trust() > before.trust() || after.affection() > before.affection(), "a PQ together warms the edge");
                        moved++;
                    }
                    default -> { }
                }
            }
            int stageChanges = 0;
            for (Object[] e : s.axesBeforeAfter) {
                if (e[3] != e[4]) stageChanges++;
            }
            s.meaningful.forEach(l -> System.out.println("PILOT (4) " + l));
            System.out.println("PILOT (4) " + moved + " meaningful events moved the right axes; " + stageChanges + " changed the stage");
            assertEquals(s.axesBeforeAfter.size(), moved);
            assertTrue(stageChanges >= 1, "at least one event moved a stage");
        }

        // (5) agents remember previous interactions
        {
            int apologies = 0, backed = 0;
            List<String> shown = new ArrayList<>();
            for (Object[] row : s.apologyBacking) {
                ActEvent e = (ActEvent) row[0];
                BotMemory.Note earlier = (BotMemory.Note) row[1];
                apologies++;
                if (earlier != null) {
                    backed++;
                    if (shown.size() < 3) {
                        shown.add(String.format("bot %d apologized to bot %d at +%d min, recalling \"%s\" from +%d min",
                                e.actor() - BotStore.BOT_ID_BASE, e.target() - BotStore.BOT_ID_BASE,
                                (e.now() - SocialPilotSim.T0) / 60_000L, earlier.text(), (earlier.createdAt() - SocialPilotSim.T0) / 60_000L));
                    }
                }
            }
            // and the recall itself, through BotMemory.recall, for a pair that fought
            String recalled = "";
            for (ActEvent e : s.acts) {
                if (e.kind() == SocialActKind.ARGUE && e.outcome().landed()) {
                    List<BotMemory.Note> notes = BotMemory.recall(e.actor() - BotStore.BOT_ID_BASE, e.target(),
                            BotMemory.Query.of(java.util.List.of(BotMemory.Kind.CHAT), 0, 0, SocialActs.Notes.TOPIC, e.target()),
                            now, 5);
                    if (!notes.isEmpty()) {
                        recalled = "BotMemory.recall(bot " + (e.actor() - BotStore.BOT_ID_BASE) + " about bot "
                                + (e.target() - BotStore.BOT_ID_BASE) + ") -> " + notes.stream().map(BotMemory.Note::text).toList();
                        break;
                    }
                }
            }
            shown.forEach(l -> System.out.println("PILOT (5) " + l));
            System.out.println("PILOT (5) " + recalled);
            System.out.println("PILOT (5) apologies: " + apologies + ", each decided from an earlier remembered fight: " + backed);
            assertTrue(apologies >= 3, "apologies happened: " + apologies);
            assertEquals(apologies, backed, "every apology refers to an earlier remembered fight");
            assertFalse(recalled.isEmpty(), "the recall returns the earlier events");
        }

        // (6) bots start conversations with one another
        {
            Set<Integer> starters = new HashSet<>();
            Set<Long> pairs = new HashSet<>();
            int converse = 0, greet = 0;
            for (ActEvent e : s.acts) {
                if (e.outcome().landed() && e.kind() == SocialActKind.CONVERSE) {
                    converse++;
                    starters.add(e.actor());
                    pairs.add(SocialGroups.pairKey(e.actor(), e.target()));
                }
                if (e.outcome().landed() && e.kind() == SocialActKind.GREET) greet++;
            }
            System.out.println("PILOT (6) conversations started by bots: " + converse + " by " + starters.size() + " different bots across "
                    + pairs.size() + " pairs (greetings: " + greet + ")");
            assertTrue(converse >= 100, "bots started conversations: " + converse);
            assertTrue(starters.size() >= 12, "many different bots started them: " + starters.size());
            assertTrue(pairs.size() >= 40, "with many different partners: " + pairs.size());
        }

        // (7) decisions measurably influenced by emotion and memory
        {
            int actor = s.ids[3], target = s.ids[4];
            SocialContext.Self calm = SocialActions.selfOf(actor, now).with(Map.of(), Map.of(Emotion.ANGER, 0.0, Emotion.LONELINESS, 0.0),
                    Map.of(Need.COMPANIONSHIP, 0.1, Need.BELONGING, 0.1));
            SocialContext.Self angry = calm.with(Map.of(), Map.of(Emotion.ANGER, 0.8), Map.of());
            SocialContext.Self lonely = calm.with(Map.of(), Map.of(Emotion.LONELINESS, 0.7), Map.of(Need.COMPANIONSHIP, 0.9, Need.BELONGING, 0.7));
            SocialContext baseCtx = Scenario.NEW_FRIEND.context(actor, target, calm, now);
            double argueCalm = utilities(Scenario.SOUR.context(actor, target, calm, now)).getOrDefault(SocialActKind.ARGUE, -9.0);
            double argueAngry = utilities(Scenario.SOUR.context(actor, target, angry, now)).getOrDefault(SocialActKind.ARGUE, -9.0);
            double spendCalm = utilities(baseCtx).getOrDefault(SocialActKind.SPEND_TIME, -9.0);
            double spendLonely = utilities(Scenario.NEW_FRIEND.context(actor, target, lonely, now)).getOrDefault(SocialActKind.SPEND_TIME, -9.0);
            SocialContext withMemory = Scenario.SOUR_REMEMBERED.context(actor, target, calm, now);
            SocialContext noMemory = Scenario.SOUR.context(actor, target, calm, now);
            Map<SocialActKind, Double> uMem = utilities(withMemory), uNone = utilities(noMemory);
            SocialActKind pickMem = argmax(withMemory), pickNone = argmax(noMemory);
            SocialActKind pickAngry = argmax(Scenario.SOUR.context(actor, target, angry, now)),
                    pickCalm = argmax(Scenario.SOUR.context(actor, target, calm, now));
            System.out.printf("PILOT (7) emotion: ARGUE utility calm %.3f -> angry %.3f; SPEND_TIME utility met %.3f -> lonely %.3f; "
                            + "choice in the sour scene: calm %s, angry %s%n", argueCalm, argueAngry, spendCalm, spendLonely, pickCalm, pickAngry);
            System.out.printf("PILOT (7) memory: the same scene with and without a remembered open fight: legal acts %s vs %s; "
                            + "APOLOGIZE %s vs %s; choice %s vs %s%n", uMem.keySet(), uNone.keySet(), uMem.get(SocialActKind.APOLOGIZE),
                    uNone.get(SocialActKind.APOLOGIZE), pickMem, pickNone);
            assertTrue(argueAngry > argueCalm + 0.05, "anger raises the pull to argue");
            assertTrue(spendLonely > spendCalm + 0.05, "loneliness raises the pull to spend time");
            assertTrue(uMem.containsKey(SocialActKind.APOLOGIZE) && !uNone.containsKey(SocialActKind.APOLOGIZE),
                    "only a bot that remembers the fight can apologize for it");
            assertTrue(pickAngry != pickCalm || pickMem != pickNone, "emotion or memory changed the choice");
        }

        // (8) a familiar real player and a stranger are treated differently
        {
            int familiarPairs = 0, differ = 0;
            Set<Integer> bots = new HashSet<>();
            for (Reaction r : s.reactions) {
                if (r.player() != SocialPilotSim.FAMILIAR || r.state() != AffinityState.FRIEND) continue;
                Reaction stranger = null;
                for (Reaction o : s.reactions) {
                    if (o.visit() == r.visit() && o.bot() == r.bot() && o.player() == SocialPilotSim.STRANGER) stranger = o;
                }
                if (stranger == null) continue;
                familiarPairs++;
                bots.add(r.bot());
                boolean recognised = r.prompt().contains("are friends") && r.card().contains("friends");
                boolean plain = stranger.prompt().isEmpty() && stranger.card().contains("don't really know");
                if (recognised && plain) differ++;
                if (familiarPairs <= 2) {
                    System.out.println("PILOT (8) bot " + (r.bot() - BotStore.BOT_ID_BASE) + " visit " + r.visit() + ": familiar -> \""
                            + r.prompt().trim() + "\" / card \"" + r.card() + "\"");
                    System.out.println("PILOT (8)                       stranger -> \"" + stranger.prompt().trim() + "\" / card \""
                            + stranger.card() + "\"");
                }
            }
            System.out.println("PILOT (8) on the first visit the familiar player partied with and helped " + s.friendedByPlayer.size() + " bots until each read FRIEND"
                    + " (events needed: " + s.friendedByPlayer.values().stream().map(a -> a[0]).toList() + ")");
            System.out.println("PILOT (8) " + differ + " of " + familiarPairs + " (bot, visit) readings treat the familiar player as a friend and"
                    + " the stranger plainly, " + bots.size() + " different bots. Harness limit: AffinityReactions.pickFriendly/pickSideEye"
                    + " (the chosen act) are package-private and need a BotSM; the state, the prompt line and the relationship card they"
                    + " are chosen from are exercised, the act itself is not.");
            assertTrue(familiarPairs >= 2, "bots met the familiar player again: " + familiarPairs);
            assertEquals(familiarPairs, differ, "every known-versus-stranger reading differs");
        }

        // (9) social state survives the restart
        {
            Snap b = s.beforeRestart, a = s.afterRestart;
            assertTrue(b != null && a != null, "the restart happened");
            double worstMind = 0, worstAxes = 0;
            int stageDiff = 0, noteDiff = 0, intentDiff = 0, modeDiff = 0, edges = 0, notePairs = 0, liveIntents = 0;
            for (var e : b.mind.entrySet()) {
                worstMind = Math.max(worstMind, maxDiff(e.getValue(), a.mind.get(e.getKey())));
            }
            int faint = 0, faintLost = 0;
            for (var e : b.axes.entrySet()) {
                double[] before = e.getValue(), after = a.axes.get(e.getKey());
                // BotBonds drops an edge nothing but familiarity holds (no feeling above noise) when it flushes and when it loads:
                // those faint acquaintances are pruned by design and do not survive a restart
                boolean faintEdge = before[Axis.FAM] <= 0.1;
                for (int i = 0; i < before.length; i++) {
                    if (i != Axis.FAM && Math.abs(before[i]) >= 0.05) faintEdge = false;
                }
                if (faintEdge) {
                    faint++;
                    if (maxDiff(before, after) > 1e-5) faintLost++;
                    continue;
                }
                edges++;
                worstAxes = Math.max(worstAxes, maxDiff(before, after));
                if (b.stages.get(e.getKey()) != a.stages.get(e.getKey())) stageDiff++;
            }
            for (var e : b.notes.entrySet()) {
                notePairs++;
                if (!e.getValue().equals(a.notes.get(e.getKey()))) noteDiff++;
            }
            if (!a.notes.keySet().equals(b.notes.keySet())) noteDiff++;
            for (var e : b.intents.entrySet()) {
                liveIntents += e.getValue().size();
                if (!e.getValue().equals(a.intents.get(e.getKey())) || !b.recentIntents.get(e.getKey()).equals(a.recentIntents.get(e.getKey()))) {
                    intentDiff++;
                }
            }
            for (int id : s.onlineAtRestart) {
                if (b.modes.get(id) != a.modes.get(id)) modeDiff++;
            }
            int recentEndings = 0;
            for (var e : b.recentIntents.values()) recentEndings += e.size();
            System.out.printf("PILOT (9) restart at day %d %02d:00: %d life-profile rows and %d bond rows written; after rebuilding from the rows: "
                            + "mind (12 traits, 9 emotions, 8 needs x 20 bots) max difference %.2g; %d directed edges with a feeling, axes max difference %.2g "
                            + "(the table stores floats), stage differences %d; faint edges pruned by design (familiarity only): %d of %d; %d pairs holding memories, differing %d; %d live intents, bots "
                            + "whose book differs %d (plus %d recent endings kept); modes of the %d bots online at the time, differing %d; pilot members equal %s%n", SocialPilotSim.RESTART_DAY + 1, SocialPilotSim.RESTART_HOUR,
                    s.restartReport.get("lifeProfileRows"), s.restartReport.get("bondRowsWritten"), worstMind, edges, worstAxes, stageDiff, faintLost, faint,
                    notePairs, noteDiff, liveIntents, intentDiff, recentEndings, s.onlineAtRestart.size(), modeDiff, b.pilotMembers.equals(a.pilotMembers));
            System.out.println("PILOT (9) what does NOT survive (RAM only, by design or not yet stored):");
            s.lost.forEach((k, v) -> System.out.println("PILOT (9)   - " + k + ": " + v));
            assertTrue(worstMind < 1e-9, "the mind survives: " + worstMind);
            assertTrue(worstAxes < 1e-5, "the axes survive: " + worstAxes);
            assertEquals(0, stageDiff, "the stages survive");
            assertEquals(0, noteDiff, "the memories survive");
            assertEquals(0, intentDiff, "the intents survive");
            assertEquals(0, modeDiff, "the resumed mode survives");
            assertTrue(liveIntents + recentEndings > 0, "there were intents to carry across: " + liveIntents + "+" + recentEndings);
            assertEquals(b.pilotMembers, a.pilotMembers);
            assertEquals(BOTS(), a.pilotStats.get("restartsSurvived").intValue(), "every pilot bot's memory counts as restored");
            int afterRestart = 0;
            for (ActEvent e : s.acts) {
                if (e.now() > s.beforeRestartAt()) afterRestart++;
            }
            assertTrue(afterRestart > 100, "the bots carried on after the restart: " + afterRestart + " acts");
        }

        // (10) interactions with no real player present
        {
            StringBuilder sb = new StringBuilder();
            int quiet = 0, quietDays = 0;
            for (int d = 0; d < s.days; d++) {
                sb.append(d + 1).append(s.playerOnDay[d] ? "(player) " : "(no player) ");
                if (!s.playerOnDay[d]) {
                    quiet += s.landedByDay[d];
                    quietDays++;
                    assertTrue(s.landedByDay[d] >= 40, "day " + (d + 1) + " with no player had acts: " + s.landedByDay[d]);
                }
            }
            System.out.println("PILOT (10) landed acts on the " + quietDays + " days with no real player in the world: " + quiet + " (days "
                    + sb.toString().trim() + ")");
            assertTrue(quiet >= 160);
        }
    }

    // ── the safety properties ──

    private void safety(SocialPilotSim s) {
        // no pair exceeds the budget's limits
        int worst = SocialTestKit.assertWithinBudget(s.acts);
        // memory notes per pair stay under the cap (BotMemory.MAX_PER_PAIR = 10), and per bot
        int worstPair = 0, worstBot = 0;
        for (int a : s.ids) {
            int total = 0;
            for (int b : s.ids) {
                if (a == b) continue;
                int n = BotMemory.about(a - BotStore.BOT_ID_BASE, b).size();
                worstPair = Math.max(worstPair, n);
                total += n;
            }
            worstBot = Math.max(worstBot, total);
        }
        assertTrue(worstPair <= 10, "memory notes per pair under the cap: " + worstPair);
        // no repeated failed action storm: one (actor, target, act) fails at most a few times in any hour
        Map<String, List<Integer>> failed = new HashMap<>();
        for (int[] f : s.failures) {
            failed.computeIfAbsent(f[0] + "/" + f[1] + "/" + f[2], k -> new ArrayList<>()).add(f[3]);
        }
        int storm = 0;
        for (List<Integer> minutes : failed.values()) {
            for (int i = 0; i < minutes.size(); i++) {
                int inHour = 0;
                for (int j = i; j < minutes.size() && minutes.get(j) - minutes.get(i) < 60; j++) inHour++;
                storm = Math.max(storm, inHour);
            }
        }
        assertTrue(storm <= 3, "no failed act repeats in a storm: " + storm);
        // total acts per bot-hour bounded
        Map<String, Integer> perBotHour = new HashMap<>();
        int busiest = 0;
        for (ActEvent e : s.acts) {
            busiest = Math.max(busiest, perBotHour.merge(e.actor() + "/" + (e.now() - SocialPilotSim.T0) / SocialPilotSim.HOUR, 1, Integer::sum));
        }
        assertTrue(busiest <= 40, "acts per bot-hour bounded: " + busiest);
        double botHours = botHoursOnline(s);
        System.out.printf("PILOT safety: per-minute actor limit %d (busiest bot %d), same-pair family gaps kept; memory notes per pair at most %d (cap 10), per bot at most %d;"
                        + " one act failing repeatedly in an hour at most %d times; at most %d acts in one bot-hour (mean %.2f per bot-hour online)%n",
                SocialBudget.Limits.defaults().actorPerMin(), worst, worstPair, worstBot, storm, busiest, s.acts.size() / botHours);
    }

    // ── helpers ──

    private static int BOTS() {
        return SocialPilotSim.BOTS;
    }

    private static double botHoursOnline(SocialPilotSim s) {
        int n = 0;
        for (int[] bot : s.place) {
            for (int c : bot) if (c != 0) n++;
        }
        return Math.max(1, n);
    }

    private static double maxDiff(double[] a, double[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    private static double mean(SocialPilotSim s, SocialActKind kind, Trait trait) {
        double sum = 0;
        int n = 0;
        for (ActEvent e : s.acts) {
            if (e.kind() == kind && e.outcome().happened()) {
                sum += SocialActions.selfOf(e.actor(), s.now()).t(trait);
                n++;
            }
        }
        return n == 0 ? 0.5 : sum / n;
    }

    /** The legal acts of a context and their utilities (the noise-free score the decision tick ranks by). */
    static Map<SocialActKind, Double> utilities(SocialContext c) {
        Map<SocialActKind, Double> out = new EnumMap<>(SocialActKind.class);
        for (SocialActKind kind : SocialActKind.values()) {
            SocialAction a = SocialActions.action(kind);
            if (a.preconditions(c) == null) {
                out.put(kind, a.utility(c));
            }
        }
        return out;
    }

    static SocialActKind argmax(SocialContext c) {
        SocialActKind best = null;
        double top = -1e9;
        for (var e : utilities(c).entrySet()) {
            if (e.getValue() > top) {
                top = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** A situation two bots can be put in, with everything but the bot's own mind fixed. */
    enum Scenario {
        SOUR("an old quarrel, nothing said", Stage.RIVAL, edge(0.8, -0.1, 0.1, 0.1, 0, 0.35, 0.45), SocialContext.Recollection.NONE),
        SOUR_REMEMBERED("the same quarrel, remembered", Stage.RIVAL, edge(0.8, -0.1, 0.1, 0.1, 0, 0.35, 0.45),
                new SocialContext.Recollection(true, true, false, 0, 2)),
        NEW_FRIEND("a friend it likes", Stage.FRIEND, edge(0.8, 0.5, 0.5, 0.4, 0, 0, 0), new SocialContext.Recollection(false, false, false, 3, 0)),
        COOL("an acquaintance", Stage.ACQUAINTANCE, edge(0.3, 0.1, 0.15, 0.1, 0, 0.1, 0.1), SocialContext.Recollection.NONE);

        final String label;
        final Stage stage;
        final double[] edge;
        final SocialContext.Recollection memory;

        Scenario(String label, Stage stage, double[] edge, SocialContext.Recollection memory) {
            this.label = label;
            this.stage = stage;
            this.edge = edge;
            this.memory = memory;
        }

        private static double[] edge(double fam, double trust, double aff, double res, double att, double riv, double ren) {
            double[] v = new double[Axis.COUNT];
            v[Axis.FAM] = fam;
            v[Axis.TRU] = trust;
            v[Axis.AFF] = aff;
            v[Axis.RES] = res;
            v[Axis.ATT] = att;
            v[Axis.RIV] = riv;
            v[Axis.REN] = ren;
            return v;
        }

        SocialContext context(int actor, int target, SocialContext.Self self, long now) {
            SocialWorld.Body a = new SocialWorld.Body(actor, "A", SocialTestKit.MAP, 100000000, 500, 0, 30, false);
            SocialWorld.Body t = new SocialWorld.Body(target, "T", SocialTestKit.MAP, 100000000, 540, 0, 30, false);
            return new SocialContext(now, a, t, self, Axes.of(edge), stage, memory, List.of(), true, true, true, true, false, null);
        }
    }

    private static String[] placeNames(SocialPilotSim s) {
        String[] kinds = {"square", "market", "park", "pet park"};
        String[] out = new String[2 + s.maps.length];
        out[0] = "offline";
        out[1] = "training";
        for (int i = 0; i < s.maps.length; i++) out[2 + i] = kinds[i % 4];
        return out;
    }

    /** Aggregates that compare a pilot run with a switched-off one. */
    private static Map<String, Double> shape(SocialPilotSim s) {
        Map<String, Double> m = new TreeMap<>();
        double online = botHoursOnline(s), town = 0;
        Set<Long> pairsMet = new HashSet<>();
        for (int[] bot : s.place) {
            for (int c : bot) if (c >= 2) town++;
        }
        for (var e : s.pairLedger.entrySet()) {
            if (e.getValue()[0] + e.getValue()[1] + e.getValue()[2] > 0) pairsMet.add(e.getKey());
        }
        int friends = 0;
        for (long[] ignored : s.firstFriend.values()) friends++;
        m.put("bot-hours online", online);
        m.put("bot-hours in a town mode", town);
        m.put("pairs with an interaction", (double) pairsMet.size());
        m.put("directed edges that reached FRIEND", (double) friends);
        m.put("landed acts", (double) Arrays.stream(s.landedByDay).sum());
        m.put("mode switches", (double) s.modeSwitches);
        return m;
    }

    // ── the report ──

    static String report(SocialPilotSim s) {
        StringBuilder out = new StringBuilder();
        long now = s.now();
        String[] names = placeNames(s);
        out.append("PILOT ===== ").append(SocialPilotSim.BOTS).append(" Henesys pilot bots, ").append(s.days).append(" simulated days, seed ")
                .append(s.seed).append(", pilot ").append(s.pilotOn ? "on" : "off").append(" =====\n");
        out.append("PILOT bots (slot: window, traits S=sociability A=agreeableness M=ambition I=impulsiveness C=curiosity):\n");
        for (SocialPilotSim.Bot b : s.bots.values()) {
            SocialContext.Self self = SocialActions.selfOf(b.id, now);
            out.append(String.format("PILOT   slot %3d: online %02d:%02d for %dh   S %.2f A %.2f M %.2f I %.2f C %.2f%n", b.slot, b.windowStart / 60,
                    b.windowStart % 60, b.windowLen / 60, self.t(Trait.SOCIABILITY), self.t(Trait.AGREEABLENESS), self.t(Trait.AMBITION),
                    self.t(Trait.IMPULSIVENESS), self.t(Trait.CURIOSITY)));
        }
        out.append("PILOT where the online bots were, by hour of day (all days; offline / training / ").append(String.join(" / ",
                Arrays.copyOfRange(names, 2, names.length))).append("):\n");
        for (int h = 0; h < 24; h += 2) {
            int[] count = new int[names.length];
            for (int[] bot : s.place) {
                for (int d = 0; d < s.days; d++) count[bot[d * 24 + h]]++;
            }
            out.append(String.format("PILOT   %02d:30  %s%n", h, Arrays.toString(count)));
        }
        out.append("PILOT per day (landed acts / carried out; * = a real player visited):\n");
        for (int d = 0; d < s.days; d++) {
            out.append(String.format("PILOT   day %d%s  %d / %d%n", d + 1, s.playerOnDay[d] ? "*" : " ", s.landedByDay[d], s.carriedByDay[d]));
        }
        out.append("PILOT landed acts by kind: ").append(SocialPilotSim.landedByKind(s.acts)).append('\n');
        Map<String, Integer> notLanded = new TreeMap<>();
        for (ActEvent e : s.acts) {
            if (!e.outcome().landed()) notLanded.merge(e.kind() + ": " + e.outcome().reason(), 1, Integer::sum);
        }
        out.append("PILOT carried out but not landed (conflicts that went nowhere, failures): ").append(notLanded).append('\n');
        out.append("PILOT decisions and budget: ").append(new TreeMap<>(SocialMetrics.actCounters().entrySet().stream()
                .filter(e -> e.getKey().startsWith("decision.")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        Map.Entry::getValue)))).append(' ').append(SocialBudget.counters()).append('\n');
        out.append("PILOT mode switches: ").append(s.modeSwitches).append(", bot-hours online ").append((int) botHoursOnline(s)).append('\n');
        // relationships
        List<String> friendships = new ArrayList<>(), rivalries = new ArrayList<>(), estrangements = new ArrayList<>();
        Map<Stage, Integer> stages = new EnumMap<>(Stage.class);
        for (int a : s.ids) {
            for (int b : s.ids) {
                if (a == b) continue;
                Stage st = BotBonds.stage(a, b);
                stages.merge(st, 1, Integer::sum);
                Axes ax = BotBonds.axes(a, b);
                String edge = String.format("bot %d -> bot %d %s aff %.2f trust %.2f riv %.2f res %.2f", a - BotStore.BOT_ID_BASE,
                        b - BotStore.BOT_ID_BASE, st, ax.affection(), ax.trust(), ax.rivalry(), ax.resentment());
                if (a < b && SocialPilot.isFriendStage(st) && SocialPilot.isFriendStage(BotBonds.stage(b, a))) {
                    friendships.add(String.format("%08.3f|%s mutual with bot %d (%s back)", 10 - Math.min(ax.affection(), BotBonds.axes(b, a).affection()),
                            edge, b - BotStore.BOT_ID_BASE, BotBonds.stage(b, a)));
                }
                if (st == Stage.RIVAL) rivalries.add(String.format("%08.3f|%s", 10 - ax.resentment() - ax.rivalry(), edge));
                if (st == Stage.ESTRANGED) estrangements.add(String.format("%08.3f|%s", 10 - ax.resentment(), edge));
            }
        }
        out.append("PILOT directed edges by stage at the end: ").append(stages).append('\n');
        top(out, "strongest friendships", friendships, 5);
        top(out, "rivalries", rivalries, 5);
        top(out, "estrangements", estrangements, 5);
        out.append("PILOT friend groups (>=3, mutual FRIEND edges): ").append(s.pilotOn ? SocialGroups.describe(now) : "none (pilot off)")
                .append(", most at once ").append(s.groupsPeak).append('\n');
        out.append("PILOT interaction ledger: ").append(s.pairLedger.size()).append(" pairs met; friendship edges reached: ")
                .append(s.firstFriend.size()).append('\n');
        out.append("PILOT stats(): ").append(SocialPilot.stats()).append('\n');
        out.append("PILOT intents: ").append(SocialIntents.counters()).append('\n');
        return out.toString();
    }

    private static void top(StringBuilder out, String what, List<String> rows, int n) {
        rows.sort(String::compareTo);
        out.append("PILOT ").append(what).append(" (").append(rows.size()).append("):\n");
        for (int i = 0; i < Math.min(n, rows.size()); i++) {
            out.append("PILOT   ").append(rows.get(i).substring(rows.get(i).indexOf('|') + 1)).append('\n');
        }
    }
}
