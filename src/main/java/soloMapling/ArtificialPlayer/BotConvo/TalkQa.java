package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Plan;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Step;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.StageSpots;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.6.1 (ticket 114): staging and checks for {@code !qa bt-1} and auto-QA t-bt-1 / t-bt-2. Fresh bots are spawned
 * next to the player with bonds staged between them (friends, a rival), so the talk tree runs on known minds: a pair
 * greets, chats and asks for a party, the other answers from its goals and a yes becomes a real party; a group of
 * three takes turns; a crew's leader picks a town and its friends head there. Teardown despawns the bots and forgets
 * their bonds.
 */
public final class TalkQa {

    private TalkQa() {
    }

    /** Spawns {@code count} bots at the player's height next to them, as social bots (talkers) or training bots. */
    public static List<Character> spawn(Character player, int count, boolean training, int level) {
        List<Character> out = new ArrayList<>();
        MapleMap map = player.getMap();
        int base = BotStore.nextDynamicSlots(count);
        for (int i = 0; i < count; i++) {
            Point at = StageSpots.nearLevel(map, player.getPosition());
            int id = BotGeneration.createBot(base + i, at, map, 0, level, level);
            Character bot = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (bot == null) {
                continue;
            }
            BotBonds.forgetBot(id); // a fresh slot: no bonds from an earlier run
            BotTalk.qaHold(id, true); // the talk tick leaves staged bots to the check
            if (training) {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);
            } else {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
                StageSpots.pin(id); // it stays next to the player
            }
            out.add(bot);
        }
        return out;
    }

    /** Despawns the staged bots and forgets their bonds. */
    public static String teardown(List<Character> bots) {
        int n = 0;
        for (Character c : bots) {
            try {
                if (c.getParty() != null) {
                    soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(c);
                }
                StageSpots.unpin(c.getId());
                BotTalk.qaHold(c.getId(), false);
                BotBonds.forgetBot(c.getId());
                BotSM sm = CharacterStorage.getBotById(c.getId());
                if (sm != null) {
                    sm.setRunning(false);
                    sm.stopScheduledTask();
                }
                BotGeneration.removeBotFromServer(c);
                n++;
            } catch (RuntimeException e) {
                // keep going: the next bot still goes
            }
        }
        return "despawned " + n + " staged bot(s)";
    }

    /** Makes every pair of {@code bots} friends both ways (a crew). */
    public static void friends(List<Character> bots) {
        for (int i = 0; i < bots.size(); i++) {
            for (int j = i + 1; j < bots.size(); j++) {
                BotBonds.stage(bots.get(i).getId(), bots.get(j).getId(), AffinityState.FRIEND);
            }
        }
    }

    /** One talk now among {@code who} (opener first), from the bank, with the ask forced (null: the opener's pick). */
    public static Plan talk(List<Character> who, Ask forced, String why) {
        Character a = who.get(0);
        return BotTalk.run(a.getMap(), who, false, forced, true, why, System.currentTimeMillis(),
                ThreadLocalRandom.current());
    }

    /** The plan as QA lines: one per step, with the decisions. */
    public static List<String> describe(String tag, Plan p) {
        List<String> out = new ArrayList<>();
        if (p == null) {
            out.add(tag + " did not start (a bot is busy in another talk)");
            return out;
        }
        out.add(tag + " " + BotTalk.names(p.who) + ": " + p.summary());
        for (Step s : p.steps) {
            out.add(tag + "   " + s.speaker.getName() + " [" + s.intent.label() + "] " + s.text
                    + (s.act != null ? "  -> then " + s.act.ask() + " with " + BotTalk.names(s.act.yes()) : ""));
        }
        p.decisions.forEach((id, d) -> out.add(tag + "   decision " + nameOf(p, id) + ": " + d.answer() + " "
                + d.manner() + " (" + d.why() + ")"));
        return out;
    }

    static String nameOf(Plan p, int id) {
        for (Character c : p.who) {
            if (c.getId() == id) {
                return c.getName();
            }
        }
        return String.valueOf(id);
    }

    /** Waits (up to {@code maxMs}) until every plan has said its last line and done its act; true when they all did. */
    public static boolean awaitDone(List<Plan> plans, long maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            boolean all = true;
            for (Plan p : plans) {
                all &= p == null || p.done;
            }
            if (all) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    /** How long a planned talk takes to say (typing time per line), with a margin. */
    public static long sayMs(Plan p) {
        return p == null ? 0 : 4_000L + 3_200L * p.steps.size();
    }

    /**
     * Judges a talk after its lines went out: every step's speaker said its line (noted with its intent), no one
     * spoke twice in a row in a group's small talk, and the act matches the decisions (a yes to a party: a party
     * holding the asker and every yes; a no: none). Returns the problems (empty: all good).
     */
    public static List<String> judge(Plan p, long since) {
        List<String> bad = new ArrayList<>();
        if (p == null) {
            bad.add("the talk did not start");
            return bad;
        }
        long now = System.currentTimeMillis();
        Set<Integer> spoke = new HashSet<>();
        for (Character c : p.who) {
            BotLineIntents.Said s = BotLineIntents.of(c.getId(), now);
            if (s != null && s.at() >= since) {
                spoke.add(c.getId());
            }
        }
        for (Step s : p.steps) {
            if (!spoke.contains(s.speaker.getId())) {
                bad.add(s.speaker.getName() + " never said its line(s) (no intent noted)");
                break;
            }
        }
        if (p.group) {
            Character prev = null;
            for (Step s : p.steps) {
                if (s.intent.move() == TalkIntent.Move.SMALL_TALK && prev != null && prev == s.speaker) {
                    bad.add(s.speaker.getName() + " spoke twice in a row in the group");
                }
                prev = s.speaker;
            }
            if (spoke.size() < 3) {
                bad.add("only " + spoke.size() + " of the group spoke");
            }
        }
        if (p.ask == Ask.PARTY && p.offer != null && p.offer.payload() == null) {
            Character a = p.who.get(0);
            for (Character c : p.who.subList(1, p.who.size())) {
                TalkBrain.Decision d = p.decisions.get(c.getId());
                boolean together = a.getParty() != null && c.getParty() != null
                        && a.getParty().getId() == c.getParty().getId();
                if (d != null && d.yes() && !together) {
                    bad.add(c.getName() + " said yes but is not in " + a.getName() + "'s party");
                }
                if (d != null && !d.yes() && together) {
                    bad.add(c.getName() + " said no but joined the party anyway");
                }
            }
        }
        return bad;
    }

    /** A town for a crew at this level other than {@code here} (Henesys, else Ellinia). */
    public static int crewTown(int here, int level) {
        for (int town : new int[]{100000000, 101000000, 102000000, 103000000}) {
            LifeTowns.Town t = LifeTowns.townOf(town);
            if (town != here && t != null && t.fits(level)) {
                return town;
            }
        }
        return 100000000;
    }

    /** The crew check's first half: the leader's pick reaches each follower's next hang-out (CREW). */
    public static List<String> crewFollow(List<Character> crew, int town) {
        List<String> bad = new ArrayList<>();
        Character leader = crew.get(0);
        for (Character c : crew) {
            if (c.getId() < leader.getId()) {
                leader = c;
            }
        }
        long nudged = BotCrews.stats().get("nudged");
        BotCrews.led(leader, town);
        long now = System.currentTimeMillis();
        if (BotCrews.stats().get("nudged") - nudged < Math.min(crew.size(), BotCrews.maxMovers()) - 1L) {
            bad.add("the followers were not nudged (" + (BotCrews.stats().get("nudged") - nudged) + ")");
        }
        for (Character c : crew) {
            if (c == leader) {
                continue;
            }
            int f = BotCrews.follow(c, now);
            LifeRuntime life = BotTalk.lifeOf(c.getId());
            int dest = life == null ? -1 : life.townDestination();
            if (f != town || dest != town) {
                bad.add(c.getName() + " does not head to the crew's town (follow " + f + ", next hang-out " + dest + ")");
            }
        }
        return bad;
    }

    /** Lines for a GM about who stands where. */
    public static String where(List<Character> bots) {
        StringBuilder b = new StringBuilder();
        for (Character c : bots) {
            b.append(b.length() == 0 ? "" : ", ").append(c.getName()).append(" @ ").append(c.getMapId());
        }
        return b.toString();
    }

    /** Lets a Random in for tests. */
    static Random rng() {
        return ThreadLocalRandom.current();
    }

    /** Stats for QA lines. */
    public static String stats() {
        Map<String, Object> s = BotTalk.stats();
        return "talks " + s.get("talks") + ", greets " + s.get("greets") + ", groups " + s.get("groups") + ", lines "
                + s.get("lines") + " (bank " + s.get("bankLines") + ", lines " + s.get("builtInLines") + ", llm "
                + s.get("llmLines") + "), acts " + s.get("actsOk") + " ok / " + s.get("actsFailed") + " not, intents "
                + s.get("intents") + ", crews " + s.get("crews") + ", cpu " + ((Long) s.get("cpuNanos")) / 1_000_000
                + " ms over " + s.get("ticks") + " ticks";
    }
}
