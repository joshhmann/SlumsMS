package soloMapling.qa;

import client.Character;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.COMBAT;

/**
 * Mu Lung Dojo {@code !qa} scenarios.
 *
 * <p>1.5.5 hotfix, {@code dj-1}: a player's report "Mu Lung Dojo - bots don't register enemies on party climb so they
 * just watch". The run puts the GM in the dojo hall with follower bots of its level in its party (the bots a player
 * really brings: friend recruits and companions are FollowerBots). The GM takes the party in through So Gong's
 * "challenge him with a party" and the bots fight each floor's monster with it ({@code FollowerFight}).
 *
 * <p>Registered in {@link QaScenarios#all()}; docs/ops/qa-suite.md has the rows.
 */
final class DojoScenarios {

    static final int DOJO_HALL = 925020001;   // So Gong (2091005) takes a party in from here
    static final int SO_GONG = 2091005;
    static final int PARTY_FLOOR_BASE = 925030000;
    static final int MIN_LEVEL = 25;          // So Gong turns anyone lower away

    private DojoScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Dj1PartyFollowersFight());
    }

    static final class Dj1PartyFollowersFight implements QaScenario {

        static final int DEFAULT_BOTS = 2;
        static final int MAX_BOTS = 5;        // a party holds six

        final Map<Integer, List<Integer>> bots = new ConcurrentHashMap<>();

        public String id() {
            return "dj-1";
        }

        public String title() {
            return "Mu Lung Dojo party climb: your follower bots fight each floor's monster with you";
        }

        public String usage() {
            return "[bots 1-5]";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(COMBAT);
        }

        /** The hall and every party floor (38 stages, 5 slots). */
        public Set<Integer> watchedMaps(Character gm, String[] args) {
            Set<Integer> maps = new LinkedHashSet<>();
            maps.add(DOJO_HALL);
            for (int stage = 1; stage <= 38; stage++) {
                for (int slot = 0; slot < 5; slot++) {
                    maps.add(PARTY_FLOOR_BASE + stage * 100 + slot);
                }
            }
            return maps;
        }

        public int minutes(String[] args) {
            return 30;
        }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            if (gm.getLevel() < MIN_LEVEL) {
                throw new QaRefusal("So Gong lets only level " + MIN_LEVEL + "+ into the dojo: level up first.");
            }
            if (gm.getParty() != null && gm.getParty().getLeaderId() != gm.getId()) {
                throw new QaRefusal("Only the party leader can take a party in: leave your party or lead it.");
            }
            int count = count(ctx.args());
            if (!ctx.world().warp(gm, DOJO_HALL)) {
                throw new QaRefusal("The Mu Lung Dojo hall " + DOJO_HALL + " did not load.");
            }
            ctx.log("GM warped to the dojo hall " + DOJO_HALL);
            List<Integer> ids = ctx.world().stageDojoFollowers(gm, count);
            if (ids.isEmpty()) {
                throw new QaRefusal("No follower bot could be staged here (is your party full?).");
            }
            bots.put(ctx.run().gmId(), ids);
            ctx.onStop("take " + ids.size() + " dojo follower(s) out of the party and the world", () -> {
                ctx.world().unstageDojoFollowers(ids);
                bots.remove(ctx.run().gmId());
            });
            ctx.log("staged " + ids.size() + " follower(s) in the GM's party: " + ids);
            List<String> out = new java.util.ArrayList<>();
            for (int id : ids) {
                out.add(ctx.world().botLabel(gm, id) + " follows you, in your party");
            }
            out.add("Talk to So Gong (" + SO_GONG + ") and pick \"I want to challenge him with a party\": the whole"
                    + " party goes to floor 1.");
            out.add("When the floor's monster appears the bots walk up to it and swing (the log: FOLLOWER FIGHT"
                    + " <bot> walks to / hits ...). Before the 1.5.5 fix they stood next to you and watched.");
            out.add("Kill it, take the portal up: the bots follow you to the next floor and fight again. On a rest"
                    + " floor (every sixth) they just follow. !qa do check says where each bot is.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equalsIgnoreCase(name)) {
                throw new QaRefusal(id() + " steps: check.");
            }
            Character gm = ctx.gmNow() == null ? ctx.gm() : ctx.gmNow();
            List<String> out = ctx.world().dojoFollowerStatus(gm, bots.getOrDefault(ctx.run().gmId(), List.of()));
            out.forEach(line -> ctx.run().note("STEP", line));
            return out;
        }

        static int count(String[] args) {
            if (args == null || args.length == 0) {
                return DEFAULT_BOTS;
            }
            try {
                return Math.max(1, Math.min(MAX_BOTS, Integer.parseInt(args[0].trim())));
            } catch (NumberFormatException e) {
                return DEFAULT_BOTS;
            }
        }
    }
}
