package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.worldevents.ActiveEvent;
import soloMapling.worldevents.WorldEventReactions;
import soloMapling.worldevents.WorldEvents;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.HABITS;
import static soloMapling.qa.QaLog.Topic.WORLD_EVENT;

/**
 * 1.6.0 world events: ev-1 (a Giant Cake in Henesys: warnings, the notice, bots arrive and whack it, it dies, rewards,
 * gossip afterwards) and ev-2 (a Henesys invasion: bots swarm it, some die and respawn, and grumble). Both stage
 * training bots on the event map that answer the call at once ({@link WorldEvents#qaJoin}); the bots around Henesys
 * may come by themselves too. The event's own lines are on the {@code WORLD_EVENT} topic as {@code WEVENT ...}.
 */
final class WorldEventScenarios {

    static final int HENESYS = 100000000;

    private WorldEventScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Ev1GiantCake(), new Ev2Invasion());
    }

    /** Shared staging: channel 1, Henesys, staged grinders, the event started with a test-sized boss. */
    abstract static class EventScenario implements QaScenario {
        final List<Integer> staged = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile ActiveEvent event;

        abstract String template();

        abstract long bossHp();

        abstract int eventMinutes();

        abstract int botCount();

        abstract int botLevel();

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(WORLD_EVENT, BOT_CHAT, HABITS);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(HENESYS);
        }

        public Set<String> claims(Character gm, String[] args) {
            return Set.of("map:" + HENESYS, "worldevent");
        }

        public int tickSeconds() {
            return 10;
        }

        List<String> stageEvent(QaContext ctx, String code) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            if (!w.onEventChannel(gm)) {
                throw new QaRefusal("World events run on channel 1: change to channel 1 first.");
            }
            WorldEvents events = WorldEvents.getInstance();
            for (ActiveEvent e : events.active()) {
                if (e.mapId() == HENESYS) {
                    events.stop(e.id, "!qa " + id());
                    ctx.log("stopped event #" + e.id + " already running in Henesys");
                }
            }
            if (gm.getMapId() != HENESYS && !w.warp(gm, HENESYS)) {
                throw new QaRefusal("Cannot warp to Henesys.");
            }
            for (int i = 0; i < 20 && gm.getMapId() != HENESYS; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            ctx.log("in Henesys");
            staged.clear();
            staged.addAll(CombatStaging.stage(gm, List.of(), botCount(), botLevel() - 1, null, false));
            List<Integer> ids = List.copyOf(staged);
            ctx.onStop("despawn " + ids.size() + " staged bots", () -> CombatStaging.unstage(ids));
            ctx.run().note(code, code + " STAGED " + ids.size() + " bots lv " + botLevel() + " in Henesys");
            events.qaWarn(template(), HENESYS, 10);
            events.qaWarn(template(), HENESYS, 1);
            WorldEvents.StartResult r = events.startForQa(template(), HENESYS, "!qa " + id(), bossHp(), eventMinutes());
            if (r.error() != null) {
                throw new QaRefusal("The event did not start: " + r.error());
            }
            event = r.event();
            ActiveEvent e = r.event();
            ctx.onStop("stop the event if it still runs", () -> {
                if (!e.finished()) {
                    events.stop(e.id, "!qa stop");
                }
            });
            int joined = events.qaJoin(e, ids);
            ctx.run().note(code, code + " EVENT #" + e.id + " " + template() + " on " + e.mapId() + ", " + joined
                    + " staged bots called");
            return new ArrayList<>(List.of("Event #" + e.id + " (" + template() + ") runs in Henesys for "
                    + eventMinutes() + " min; " + joined + " staged bots answered, bots nearby may come too."));
        }

        /** Staged bots that missed the call (still settling in) are called again. */
        @Override
        public void tick(QaContext ctx) {
            ActiveEvent e = event;
            if (e == null || e.finished()) {
                return;
            }
            for (int id : staged) {
                if (CharacterStorage.getBotById(id) instanceof TrainingBot tb && !tb.isGrinding()
                        && !BotDeaths.isDead(id)) {
                    tb.callToEvent();
                }
            }
        }
    }

    /** ev-1: the friends' Giant Cake. */
    static final class Ev1GiantCake extends EventScenario {
        public String id() { return "ev-1"; }
        public String title() { return "A Giant Cake in Henesys: notice, bots whack it, it dies, rewards, gossip (1.6.0)"; }
        public String usage() { return "; then !qa do gossip"; }
        public int minutes(String[] args) { return 12; }
        String template() { return "giant_cake"; }
        long bossHp() { return 60_000; }
        int eventMinutes() { return 10; }
        int botCount() { return 8; }
        int botLevel() { return 30; }

        public List<String> stage(QaContext ctx) {
            List<String> out = stageEvent(ctx, "EV1");
            out.add("Expect: two warnings (10 min, 1 min) and the [Giant Cake] notice, the cake (60,000 HP for the test)"
                    + " with little cakes around it, bots hitting it.");
            out.add("Hit it yourself for the reward (most damage) - anyone on the map when it falls gets the"
                    + " participation cakes and a little EXP. Then WEVENT BOSS DOWN, the notice, WEVENT NEWS.");
            out.add("!qa do gossip: a bot that was there talks about it (the after-chatter and the world news line).");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"gossip".equals(name)) {
                throw new QaRefusal("ev-1 steps: gossip");
            }
            ActiveEvent e = event;
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            if (e == null || gm == null) {
                return List.of("no event staged");
            }
            if (!e.finished()) {
                return List.of("The cake is still standing (" + e.describe(System.currentTimeMillis()) + ").");
            }
            List<String> lines = WorldEventReactions.qaAftermath(e, gm, ThreadLocalRandom.current());
            for (String line : lines) {
                ctx.run().note("EV1", "EV1 GOSSIP " + line);
            }
            return lines.isEmpty() ? List.of("No bot on your map to talk about it.") : lines;
        }
    }

    /** ev-2: an invasion: bots swarm, some die and respawn. */
    static final class Ev2Invasion extends EventScenario {
        public String id() { return "ev-2"; }
        public String title() { return "A Henesys invasion: bots swarm it, some die, respawn and grumble (1.6.0)"; }
        public String usage() { return "; then !qa do die | status"; }
        public int minutes(String[] args) { return 12; }
        String template() { return "henesys_invasion"; }
        long bossHp() { return 20_000; }
        int eventMinutes() { return 10; }
        int botCount() { return 12; }
        int botLevel() { return 15; }

        public List<String> stage(QaContext ctx) {
            List<String> out = stageEvent(ctx, "EV2");
            out.add("Expect: the [Monster Invasion] notice, mushrooms all over Henesys, the staged lv 15 bots (and"
                    + " any nearby) fighting them. On an invasion map a heavy hit can kill a bot: WEVENT BOT DIED,");
            out.add("then RESPAWN (HABITS) and now and then a grumble (WEVENT GRUMBLE). Then the waves, Mushmom,"
                    + " the end notice. !qa do die drops the 3 staged bots nearest a mob to 1 HP with a certain"
                    + " death; EV2 STATUS every 30 s (or !qa do status) says where everyone is.");
            return out;
        }

        private long lastStatusAt;

        /** Every 30 s while it runs: where the staged bots and the invaders are (a failed run says why). */
        @Override
        public void tick(QaContext ctx) {
            super.tick(ctx);
            ActiveEvent e = event;
            long now = System.currentTimeMillis();
            if (e != null && !e.finished() && now - lastStatusAt >= 30_000) {
                lastStatusAt = now;
                ctx.run().note("EV2", "EV2 STATUS " + WorldEvents.getInstance().qaStatus(e, List.copyOf(staged)));
            }
            if (dieArmed && e != null && !e.finished()) {
                dropNearest(ctx, e);
            }
        }

        /** Set by !qa do die when no invader was up: retried every tick until the next wave brings one. */
        private volatile boolean dieArmed;

        public List<String> action(QaContext ctx, String name, String[] args) {
            ActiveEvent e = event;
            if (e == null) {
                return List.of("no event staged");
            }
            if ("status".equals(name)) {
                String line = "EV2 STATUS " + WorldEvents.getInstance().qaStatus(e, List.copyOf(staged));
                ctx.run().note("EV2", line);
                return List.of(line);
            }
            if (!"die".equals(name)) {
                throw new QaRefusal("ev-2 steps: die | status");
            }
            dieArmed = true;
            String line = dropNearest(ctx, e);
            if (line == null) {
                line = "EV2 DIE armed: no invader is up, the 3 staged bots nearest the next one get it";
                ctx.run().note("EV2", line);
            }
            List<Integer> ids = List.copyOf(staged);
            ctx.onStop("clear the forced deaths", () -> ids.forEach(id -> BotDeaths.forceNextDeath(id, false)));
            return List.of(line);
        }

        /** The three staged bots nearest a live invader: their next hit kills (HP 1, the death certain). Null when
         *  no invader is up yet (a strong crowd can clear a wave in seconds); the caller keeps it armed. */
        private String dropNearest(QaContext ctx, ActiveEvent e) {
            java.util.Map<Integer, Integer> dist = WorldEvents.getInstance().qaMobDistances(e, List.copyOf(staged));
            List<Integer> pick = dist.entrySet().stream().filter(d -> d.getValue() >= 0)
                    .sorted(java.util.Map.Entry.comparingByValue()).limit(3).map(java.util.Map.Entry::getKey).toList();
            List<String> who = new ArrayList<>();
            for (int id : pick) {
                Character bot = soloMapling.ArtificialPlayer.BotHelpers.getCharFromChannelStorage(id);
                if (bot == null) {
                    continue;
                }
                BotDeaths.forceNextDeath(id, true);
                bot.updateHp(1);
                who.add(bot.getName() + " " + dist.get(id) + " px");
            }
            if (who.isEmpty()) {
                return null;
            }
            dieArmed = false;
            String line = "EV2 DIE next lethal hit certain for " + who.size() + " staged bots (HP 1, nearest a mob: "
                    + who + ")";
            ctx.run().note("EV2", line);
            return line;
        }
    }
}
