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
        return start(gm, withGm, bots, perfect, budgetMs, 1, lastStage, reporter, afterCleanup);
    }

    /**
     * As above, the run beginning on stage {@code firstStage} (QA: {@code !mechanics kpq ... stage4} to watch the
     * barrels without playing stages 1-3). The PQ starts at Lakelis as always; then the event is put in the state
     * Cloto's clears leave ({@link #stageEventTo}) and everyone walks through the opened portals.
     */
    public static Staged start(Character gm, boolean withGm, int bots, boolean perfect, long budgetMs, int firstStage,
                               int lastStage, Consumer<String> reporter,
                               Consumer<MechanicsRuns.Active> afterCleanup) {
        return start(null, gm, withGm, bots, perfect, budgetMs, firstStage, lastStage, reporter, afterCleanup);
    }

    /**
     * As above on channel {@code on} (null: the GM's own). A bot-only run can be staged on any channel (QA mx-4 runs
     * two channels at once); a run with the GM in it must be on his channel.
     */
    public static Staged start(Channel on, Character gm, boolean withGm, int bots, boolean perfect, long budgetMs,
                               int firstStage, int lastStage, Consumer<String> reporter,
                               Consumer<MechanicsRuns.Active> afterCleanup) {
        if (!Mechanics.enabled()) {
            return new Staged(null, List.of(), "mechanics is off (start the server with -D" + Mechanics.FLAG + "=true)");
        }
        String[] why = {null};
        Content content = kpq(w -> why[0] = w);
        if (content == null) {
            return new Staged(null, List.of(), why[0]);
        }
        Channel ch = on != null ? on : gm.getClient().getChannelServer();
        if (withGm && ch != gm.getClient().getChannelServer()) {
            return new Staged(null, List.of(), "a run with you in it is staged on your own channel");
        }
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

        List<Integer> spawned = new ArrayList<>();
        List<Character> botChars = spawnBots(recruit, want, spawned);
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
        if (!withGm) {
            // 1.6.2 (owner 10-30, bots yield): a GM's or QA's staged run takes the channel from a bot lobby run, as a
            // player's party does at Lakelis (a GM-led start yields inside EventManager.startInstance)
            KpqLobby.yieldForStaging(em, gm);
        }
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
        if (firstStage > 1) {
            int staged = stageEventTo(eim, content, firstStage);
            reporter.accept("RUN staged: stages 1-" + (firstStage - 1) + " marked cleared (" + staged
                    + " portal(s) opened, no stage rewards); the party walks to stage " + firstStage);
            if (withGm) {
                // 1.6.2 (TEST 10-30: "me stage4" left the GM on stage 1 while the bots went on): the real leader goes
                // through the same opened gates, each portal's own script (kpq0..3) moving him, never a GM warp
                int reached = leaderThroughGates(content, firstStage, gm.getMapId(), (stage, portal) -> {
                    MapleMap from = eim.getMapInstance(stage.mapId());
                    server.maps.Portal p = from == null ? null : from.getPortal(portal);
                    return p != null && gm.getMap() == from && scripting.portal.PortalScriptManager.getInstance()
                            .executePortalScript(p, gm.getClient());
                }, gm::getMapId);
                reporter.accept("RUN staged: you went through the opened portals to stage " + reached
                        + (reached == firstStage ? "" : " (a gate did not let you through: walk on from there)"));
            }
        }
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, content, perfect, budgetMs, firstStage, lastStage, true,
                reporter, done -> {
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

    /**
     * Spawns {@code want} fresh bots (level {@link #BOT_LEVEL_MIN}-{@link #BOT_LEVEL_MAX}) next to Lakelis on the
     * recruit map and waits for them to land. Every id made goes into {@code spawned} (for {@link #cleanup}); returns
     * the ones standing on the map. Blocks (call it off the packet thread).
     */
    public static List<Character> spawnBots(MapleMap recruit, int want, List<Integer> spawned) {
        NPC lakelis = recruit.getNPCById(LAKELIS);
        Point by = lakelis != null ? lakelis.getPosition() : recruit.getPortal(0).getPosition();
        int slotBase = BotStore.nextDynamicSlots(want);
        List<Integer> mine = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            Point at = StageSpots.nearLevel(recruit, new Point(by.x + 40 * (i + 1), by.y));
            int id = BotGeneration.createBot(slotBase + i, at, recruit, 0, BOT_LEVEL_MIN, BOT_LEVEL_MAX);
            if (id > 0) {
                mine.add(id);
                spawned.add(id);
            }
        }
        // createBot plays the arrival drop-down on another thread: let it land before the PQ warps the bots, as
        // BotGeneration warns for OPQ map transitions (a late drop-down packet would play in the wrong map)
        BotHelpers.blockingSleep(BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS);
        List<Character> botChars = new ArrayList<>();
        for (int id : mine) {
            Character c = BotHelpers.getCharFromChannelStorage(id);
            if (c != null && c.getMap() == recruit) {
                botChars.add(c);
            }
        }
        return botChars;
    }

    /**
     * Puts a fresh KPQ instance in the state Cloto's script leaves after clearing stages {@code 1..first-1}
     * ({@code clearStage} in scripts/npc/9020001.js): the {@code <n>stageclear} property, the stage map's gate shown
     * open, its {@code next00} portal linked to {@code kpq<n-1>} (whose own gate checks the property), and stage 1's
     * question grid emptied. Unlike Cloto it gives no stage-clear EXP or mesos (a QA shortcut earns nothing). Nobody
     * is moved: members walk through the portals like a party that cleared them. Returns the portals linked.
     */
    static int stageEventTo(EventInstanceManager eim, Content content, int first) {
        int linked = 0;
        for (int s = 1; s < first; s++) {
            MechanicsModel.Stage stage = content.stage(s).orElse(null);
            if (stage == null) {
                continue;
            }
            eim.setProperty(s + "stageclear", "true");
            if (s == 1) {
                eim.gridClear();
            }
            MapleMap map = eim.getMapInstance(stage.mapId());
            server.maps.Portal portal = map == null || stage.nextPortal() == null ? null
                    : map.getPortal(stage.nextPortal());
            if (portal != null) {
                portal.setScriptName("kpq" + (s - 1));
                linked++;
            }
            eim.showClearEffect(true, stage.mapId());
        }
        return linked;
    }

    /** One gate on the way: enter stage {@code from}'s portal {@code portal}; true when it moved the leader on. */
    interface Gate {
        boolean enter(MechanicsModel.Stage from, String portal);
    }

    /**
     * Takes the leader from stage 1 to stage {@code first} through each earlier stage's {@code nextPortal}, the way
     * the bots walk it, stopping at the first gate that does not let him through. {@code mapNow} reads where he is.
     * Returns the stage he reached.
     */
    static int leaderThroughGates(Content content, int first, int startMap, Gate gate,
                                  java.util.function.IntSupplier mapNow) {
        MechanicsModel.Stage at = content.stageOnMap(startMap).orElse(null);
        if (at == null) {
            return 0;
        }
        while (at.index() < first && at.nextPortal() != null) {
            if (!gate.enter(at, at.nextPortal())) {
                break;
            }
            MechanicsModel.Stage next = content.stageOnMap(mapNow.getAsInt()).orElse(null);
            if (next == null || next.index() <= at.index()) {
                break;
            }
            at = next;
        }
        return at.index();
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
