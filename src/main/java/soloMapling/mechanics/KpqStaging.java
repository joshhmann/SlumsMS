package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.life.NPC;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.StageSpots;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Stages a Kerning PQ run for ticket 130 phase 1 ({@code !mechanics kpq}, {@code !qa run mx-1} / auto-QA t-mx-1):
 * fresh bots on Kerning City's PQ map (103000000, next to Lakelis), a party (the GM's, with him leading, or a bot's),
 * the PQ started the way Lakelis starts it ({@code KerningPQ} event, the same eligibility check), then a
 * {@link MechanicsRuns} run over the instance. The bots it spawned leave the party and the world a little after the
 * run ends; the event script ends the PQ when the party is too small.
 */
public final class KpqStaging {

    public static final int RECRUIT_MAP = 103000000;
    public static final int LAKELIS = 9020000;
    public static final String EVENT = "KerningPQ";
    public static final int BOT_LEVEL_MIN = 30;
    public static final int BOT_LEVEL_MAX = 35;
    /** How long the spawned bots stay after a run ends (the GM sees the clear, the QA log gets its lines). */
    public static final long DESPAWN_AFTER_MS = 20_000;

    /** What staging did: the run (null when it could not start), the bots it spawned, why it failed. */
    public record Staged(MechanicsRuns.Active active, List<Integer> spawned, String error) {
        public boolean ok() {
            return error == null && active != null && !active.run().ended();
        }
    }

    private KpqStaging() {
    }

    /** The shipped KPQ stage data, or null (the reason goes to {@code why}). */
    static Content kpq(Consumer<String> why) {
        StageData.Result r = StageData.load();
        for (Content c : r.content()) {
            if (c.id().equals("kpq")) {
                return c;
            }
        }
        why.accept("kpq stage data did not load: " + r.errors());
        return null;
    }

    /**
     * Stages and starts a run. Blocks while bots spawn (call it off the packet thread).
     *
     * @param withGm   the GM plays too (he leads the party and must be level 21+); otherwise a bot leads
     * @param bots     how many bots to add (the party ends up 3-4 strong)
     * @param perfect  no wrong tries
     * @param budgetMs the run's time budget (0 = KPQ's 30 minutes)
     */
    public static Staged start(Character gm, boolean withGm, int bots, boolean perfect, long budgetMs,
                               Consumer<String> reporter, Consumer<MechanicsRuns.Active> afterCleanup) {
        return start(gm, withGm, bots, perfect, budgetMs, 0, reporter, afterCleanup);
    }

    /** As above, the run playing stages up to {@code lastStage} only (0 = the whole PQ, prize and bonus included). */
    public static Staged start(Character gm, boolean withGm, int bots, boolean perfect, long budgetMs, int lastStage,
                               Consumer<String> reporter, Consumer<MechanicsRuns.Active> afterCleanup) {
        if (!Mechanics.enabled()) {
            return new Staged(null, List.of(), "mechanics is off (start the server with -D" + Mechanics.FLAG + "=true)");
        }
        String[] why = {null};
        Content content = kpq(w -> why[0] = w);
        if (content == null) {
            return new Staged(null, List.of(), why[0]);
        }
        Channel ch = gm.getClient().getChannelServer();
        MapleMap recruit = ch.getMapFactory().getMap(RECRUIT_MAP);
        if (recruit == null) {
            return new Staged(null, List.of(), "map " + RECRUIT_MAP + " did not load");
        }
        EventManager em = ch.getEventSM().getEventManager(EVENT);
        if (em == null) {
            return new Staged(null, List.of(), "no " + EVENT + " event on channel " + ch.getId());
        }
        int partyNow = withGm && gm.getParty() != null ? gm.getParty().getMembers().size() : 0;
        if (withGm) {
            if (gm.getLevel() < content.minLevel()) {
                return new Staged(null, List.of(), "KPQ takes level " + content.minLevel() + "+ (you are "
                        + gm.getLevel() + ")");
            }
            if (gm.getParty() != null && gm.getParty().getLeaderId() != gm.getId()) {
                return new Staged(null, List.of(), "lead your party, or leave it");
            }
            if (gm.getEventInstance() != null) {
                return new Staged(null, List.of(), "you are already in an event");
            }
        }
        int want = Math.max(0, Math.min(bots, content.maxPlayers() - (withGm ? Math.max(1, partyNow) : 0)));
        if ((withGm ? Math.max(1, partyNow) : 0) + want < content.minPlayers()) {
            return new Staged(null, List.of(), "KPQ needs " + content.minPlayers() + "-" + content.maxPlayers()
                    + " in the party");
        }
        if (withGm && gm.getMap() != recruit) {
            gm.changeMap(recruit, recruit.getPortal(0));
        }

        NPC lakelis = recruit.getNPCById(LAKELIS);
        Point by = lakelis != null ? lakelis.getPosition() : recruit.getPortal(0).getPosition();
        List<Integer> spawned = new ArrayList<>();
        int slotBase = BotStore.nextDynamicSlots(want);
        for (int i = 0; i < want; i++) {
            Point at = StageSpots.nearLevel(recruit, new Point(by.x + 40 * (i + 1), by.y));
            int id = BotGeneration.createBot(slotBase + i, at, recruit, 0, BOT_LEVEL_MIN, BOT_LEVEL_MAX);
            if (id > 0) {
                spawned.add(id);
            }
        }
        // createBot plays the arrival drop-down on another thread: let it land before the PQ warps the bots, as
        // BotGeneration warns for OPQ map transitions (a late drop-down packet would play in the wrong map)
        BotHelpers.blockingSleep(BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS);
        List<Character> botChars = new ArrayList<>();
        for (int id : spawned) {
            Character c = BotHelpers.getCharFromChannelStorage(id);
            if (c != null && c.getMap() == recruit) {
                botChars.add(c);
            }
        }
        if (botChars.size() < want) {
            cleanup(spawned);
            return new Staged(null, List.of(), "only " + botChars.size() + " of " + want + " bots spawned on "
                    + RECRUIT_MAP + " (channel " + ch.getId() + ")");
        }

        Character leader = withGm ? gm : botChars.get(0);
        if (leader.getParty() == null && !Party.createParty(leader, true) || leader.getParty() == null) {
            cleanup(spawned);
            return new Staged(null, List.of(), "the party could not be made");
        }
        Party party = leader.getParty();
        for (Character c : botChars) {
            if (c != leader && !Party.joinParty(c, party.getId(), true)) {
                cleanup(spawned);
                return new Staged(null, List.of(), c.getName() + " could not join the party");
            }
        }
        List<PartyCharacter> eligible = em.getEligibleParty(party);
        if (eligible.size() < content.minPlayers()) {
            cleanup(spawned);
            return new Staged(null, List.of(), "Lakelis would refuse: " + eligible.size() + " eligible member(s)"
                    + " (all on " + RECRUIT_MAP + ", level " + content.minLevel() + "+)");
        }
        boolean started;
        String lakelisSaid = "";
        if (withGm) {
            // the GM leads: staging presses Lakelis' button for him (a GM tool), the same call the script makes
            started = MechanicsRuns.staging(() -> em.startInstance(party, recruit, 1));
        } else {
            // a bot leads: it talks to Lakelis itself, through his own script ("I want to participate")
            BotNpcTalk.Result r = MechanicsRuns.staging(() -> BotNpcTalk.INSTANCE.converse(leader, LAKELIS, List.of(0)));
            lakelisSaid = r.ran() ? r.lastLine() : "script failed: " + r.error();
            started = leader.getEventInstance() != null;
        }
        if (!started) {
            cleanup(spawned);
            return new Staged(null, List.of(), "Lakelis did not start KPQ (another party may be in it on channel "
                    + ch.getId() + ")" + (lakelisSaid.isEmpty() ? "" : ": " + lakelisSaid));
        }
        EventInstanceManager eim = leader.getEventInstance();
        if (eim == null) {
            cleanup(spawned);
            return new Staged(null, List.of(), "the PQ started but the leader is not in it");
        }
        List<Integer> ours = List.copyOf(spawned);
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, content, perfect, budgetMs, lastStage, reporter, done -> {
            ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
                cleanup(ours);
                if (afterCleanup != null) {
                    afterCleanup.accept(done);
                }
            }, DESPAWN_AFTER_MS, TimeUnit.MILLISECONDS);
        });
        if (a.run().ended()) {
            cleanup(spawned);
            return new Staged(a, ours, a.run().endedBecause());
        }
        return new Staged(a, ours, null);
    }

    /** Takes the staged bots out of their party (the event script ends the PQ when it is short) and the world. */
    public static void cleanup(List<Integer> botIds) {
        for (int id : botIds) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null) {
                continue;
            }
            try {
                if (bot.getParty() != null) {
                    Party.leaveParty(bot.getParty(), bot);
                }
            } catch (RuntimeException e) {
                soloMapling.BotLogger.log("[Mechanics] " + bot.getName() + " could not leave its party: " + e);
            }
        }
        for (int id : botIds) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null) {
                continue;
            }
            BotSM sm = CharacterStorage.getBotById(id);
            if (sm != null) {
                sm.setRunning(false);
                sm.stopScheduledTask();
            }
            MechanicsHold.release(id);
            BotGeneration.removeBotFromServer(bot);
        }
    }
}
