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
        String[] why = {null};
        Content content = kpq(w -> why[0] = w);
        if (content == null) {
            return new Staged(null, List.of(), why[0]);
        }
        return start(content, on, gm, withGm, bots, perfect, budgetMs, firstStage, lastStage, reporter, afterCleanup);
    }

    /**
     * Stages and starts a run of {@code content} - any party quest row, not Kerning's alone: which map the party forms
     * on, which NPC a bot leader accepts the quest from, the level band and the event are all the row's own fields
     * ({@code recruitMap}, {@code entryNpc}, {@code minLevel}/{@code maxLevel}, {@code eventScript}). Nothing about
     * Kerning lives in this method, which is what lets a second party quest use it instead of a copy of it.
     *
     * <p>The bots are spawned on the row's recruit map at the row's floor level ({@code minLevel}) up to five above it,
     * the party is made, and the run starts either past the GM (he leads, {@code withGm}) or by the bot leader talking
     * to the row's entry NPC - so a bot party enters the way a player's does, through the quest's own script.
     */
    public static Staged start(Content content, Channel on, Character gm, boolean withGm, int bots, boolean perfect,
                               long budgetMs, int firstStage, int lastStage, Consumer<String> reporter,
                               Consumer<MechanicsRuns.Active> afterCleanup) {
        if (!Mechanics.enabled()) {
            return new Staged(null, List.of(), "mechanics is off (start the server with -D" + Mechanics.FLAG + "=true)");
        }
        Channel ch = on != null ? on : gm.getClient().getChannelServer();
        if (withGm && ch != gm.getClient().getChannelServer()) {
            return new Staged(null, List.of(), "a run with you in it is staged on your own channel");
        }
        MapleMap recruit = ch.getMapFactory().getMap(content.recruitMap());
        if (recruit == null) {
            return new Staged(null, List.of(), "map " + content.recruitMap() + " did not load");
        }
        EventManager em = ch.getEventSM().getEventManager(content.eventScript());
        if (em == null) {
            return new Staged(null, List.of(), "no " + content.eventScript() + " event on channel " + ch.getId());
        }
        int partyNow = withGm && gm.getParty() != null ? gm.getParty().getMembers().size() : 0;
        if (withGm) {
            if (gm.getLevel() < content.minLevel()) {
                return new Staged(null, List.of(), content.name() + " takes level " + content.minLevel() + "+ (you are "
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
        String full = withGm ? noRoomForBots(bots, want, gm) : null;
        if (full != null) {
            return new Staged(null, List.of(), full);
        }
        if ((withGm ? Math.max(1, partyNow) : 0) + want < content.minPlayers()) {
            return new Staged(null, List.of(), content.name() + " needs " + content.minPlayers() + "-"
                    + content.maxPlayers() + " in the party");
        }
        if (withGm && gm.getMap() != recruit) {
            gm.changeMap(recruit, recruit.getPortal(0));
        }

        List<Integer> spawned = new ArrayList<>();
        // a bot spawns at the row's floor level and up to five above it: Kerning's are 30-35 as before (its row starts
        // at 21), and a row that starts at 51 gets 51-56 - inside the row's own gate either way
        int botMin = Math.max(content.minLevel(), 30);
        List<Character> botChars = spawnBots(recruit, want, spawned, content.entryNpc(), botMin, botMin + 5);
        if (botChars.size() < want) {
            cleanup(spawned);
            return new Staged(null, List.of(), "only " + botChars.size() + " of " + want + " bots spawned on "
                    + content.recruitMap() + " (channel " + ch.getId() + ")");
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
            return new Staged(null, List.of(), String.valueOf(content.entryNpc()) + " would refuse: "
                    + eligible.size() + " eligible member(s) (all on " + content.recruitMap() + ", level "
                    + content.minLevel() + "+)");
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
            BotNpcTalk.Result r = MechanicsRuns.staging(() -> BotNpcTalk.INSTANCE.converse(leader, content.entryNpc(),
                    List.of(0)));
            lakelisSaid = r.ran() ? r.lastLine() : "script failed: " + r.error();
            started = leader.getEventInstance() != null;
        }
        if (!started) {
            cleanup(spawned);
            return new Staged(null, List.of(), content.entryNpc() + " did not start " + content.name()
                    + " (another party may be in it on channel " + ch.getId() + ")"
                    + (lakelisSaid.isEmpty() ? "" : ": " + lakelisSaid));
        }
        EventInstanceManager eim = leader.getEventInstance();
        if (eim == null) {
            cleanup(spawned);
            return new Staged(null, List.of(), "the PQ started but the leader is not in it");
        }
        // TEST 10-31, t-mx-5: the event started for the party Lakelis' own check returned, but its entry only takes a
        // member it can find on this channel's copy of the recruit map (see bringThePartyIn): a skipped member is put
        // in the way the others went in, and a member the event will not take is a refusal, not a silent short party.
        String notIn = bringThePartyIn(eim, eligible, recruit, reporter);
        if (notIn != null) {
            cleanup(spawned);
            return new Staged(null, List.of(), notIn);
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
        // 163 (TEST 2026-11-01 19:19 UTC, "me perfect stage5"): the leader walked the opened gates to stage 5 and the
        // bots and alts stayed on stage 1, so stage 5 was never cleared and no bonus came. The party follows its leader
        // into the staged stage, in the same instance, or the staging is refused: a party short a member cannot clear.
        if (withGm) {
            List<Straggler> left = bringThePartyOn(eim, gm, line -> reporter.accept("RUN " + line));
            if (!left.isEmpty()) {
                cleanup(spawned);
                return new Staged(null, List.of(), "the party is not on your stage: " + namesOfParty(left)
                        + " stayed behind (a party short a member cannot clear, so the run would not clear either)");
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
     * Brings the members the event did not take in through the event's own entry, one at a time, and returns null when
     * the instance holds every member Lakelis took the party for - or what to say when it does not. The event takes the
     * party in through its own {@code playerEntry} (scripts/event/KerningPQ.js) and {@code
     * EventInstanceManager.registerParty} registers only the members it finds on the map it was handed, skipping the
     * rest without a word, while {@code getEligibleParty} counted them by map id alone. Stage 1 is cleared with one
     * pass per OTHER member (scripts/npc/9020001.js), so a party short a member cannot clear, and a run over it would
     * hold the rest where they stand. Nothing here moves a member onto a stage map (TEST 10-31, t-mx-5).
     */
    static String bringThePartyIn(EventInstanceManager eim, java.util.Collection<PartyCharacter> eligible,
                                  MapleMap recruit, Consumer<String> reporter) {
        List<Outside> outside = outsideTheInstance(eim, eligible, recruit);
        for (Outside missed : outside) {
            if (missed.character() != null) {
                enterThroughTheEvent(eim, missed.character());
            }
        }
        List<Outside> stillOutside = outsideTheInstance(eim, eligible, recruit);
        if (!stillOutside.isEmpty()) {
            return "the party quest started without " + names(stillOutside) + " (the event only takes a member it finds"
                    + " on the " + (recruit == null ? RECRUIT_MAP : recruit.getId()) + " it started from, and stage 1 needs"
                    + " one pass per other member, so it cannot clear)";
        }
        if (!outside.isEmpty() && reporter != null) {
            reporter.accept("RUN the party went in short: " + names(outside)
                    + " came in through the event's own entry (the party warp had left "
                    + (outside.size() == 1 ? "it" : "them") + " outside)");
        }
        return null;
    }

    /**
     * A party member the event did not take in: its character when that character is on the recruit map staging started
     * on (so it can still be brought in), and its name either way (so a refusal can say who it is). TEST 10-31, t-mx-5.
     */
    record Outside(Character character, String name) {
    }

    /**
     * The party members the event did not take in, in party order: the event started for them ({@code
     * EventManager.startInstance} registers {@code party.getEligibleMembers()}, the list Lakelis' own eligibility
     * check returned) but {@code EventInstanceManager.registerParty} registers only the members it finds on the map
     * handed to it, skipping the rest without a word.
     */
    static List<Outside> outsideTheInstance(EventInstanceManager eim, java.util.Collection<PartyCharacter> eligible,
                                            MapleMap recruit) {
        List<Outside> out = new ArrayList<>();
        if (eim == null || eligible == null) {
            return out;
        }
        java.util.Set<Integer> inside = new java.util.HashSet<>();
        for (Character c : eim.getPlayers()) {
            if (c != null) {
                inside.add(c.getId());
            }
        }
        for (PartyCharacter mpc : eligible) {
            if (mpc == null || inside.contains(mpc.getId())) {
                continue;
            }
            out.add(new Outside(recruit == null ? null : recruit.getCharacterById(mpc.getId()), mpc.getName()));
        }
        return out;
    }

    /**
     * Brings one party member into the instance the way every other member went in: {@link
     * EventInstanceManager#registerPlayer(Character)} registers it and runs the event's own {@code playerEntry}, which
     * warps it to the instance's entry map (scripts/event/KerningPQ.js). Nothing here moves a character onto a stage
     * map: one put there without being registered is in no run and clears nothing. Returns whether the event took it.
     */
    static boolean enterThroughTheEvent(EventInstanceManager eim, Character chr) {
        if (eim == null || chr == null || eim.isEventDisposed()) {
            return false;
        }
        eim.registerPlayer(chr);
        for (Character in : eim.getPlayers()) {
            if (in != null && in.getId() == chr.getId()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A party member the leader's stage does not hold yet: its character, the map the leader is on, and the portal the
     * gates land on there (a stage's {@code nextPortal} script warps to {@code st00}).
     */
    record Straggler(Character character, MapleMap to, server.maps.Portal portal) {
    }

    /**
     * 163: the members of the instance that are not on the leader's map, in instance order. GM staging moves only the
     * leader (he walks the gates himself, see {@link #leaderThroughGates}); the bots and alts were left behind on the
     * instance's first map. Empty when the leader is not on a map of this instance (nothing to follow him to).
     */
    static List<Straggler> stragglers(EventInstanceManager eim, Character leader) {
        List<Straggler> out = new ArrayList<>();
        MapleMap to = leader == null ? null : leader.getMap();
        if (eim == null || to == null || to.getEventInstance() != eim || leader.getId() <= 0) {
            return out;
        }
        server.maps.Portal portal = to.getPortal("st00");
        if (portal == null) {
            portal = to.getPortal(0);
        }
        for (Character c : new ArrayList<>(eim.getPlayers())) {
            if (c != null && c.getId() != leader.getId() && c.getMap() != to) {
                out.add(new Straggler(c, to, portal));
            }
        }
        return out;
    }

    /**
     * Takes the whole party up to the leader's map, the way the gates take him (same instance, landing where they land),
     * and says so in {@code reporter} when it did. Returns the members the warp did not take off the leader's map: empty
     * when nobody was behind or the party is with him now.
     */
    static List<Straggler> bringThePartyOn(EventInstanceManager eim, Character leader, Consumer<String> reporter) {
        List<Straggler> behind = stragglers(eim, leader);
        if (behind.isEmpty()) {
            return List.of();
        }
        for (Straggler s : behind) {
            s.character().changeMap(s.to(), s.portal());
        }
        List<Straggler> still = stragglers(eim, leader);
        if (still.isEmpty() && reporter != null) {
            reporter.accept("the party came up to your stage: " + namesOfParty(behind)
                    + " (same instance, the gates' landing)");
        }
        return still;
    }

    /** Who a run line is about, in instance order ("sniperx, candyyy"). */
    static String namesOfParty(List<Straggler> members) {
        StringBuilder sb = new StringBuilder();
        for (Straggler m : members) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m.character().getName());
        }
        return sb.toString();
    }

    /** Who a refusal or a run line is about, in party order ("AqClientAlt1, AqClientAlt2"). */
    static String names(List<Outside> members) {
        StringBuilder sb = new StringBuilder();
        for (Outside m : members) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m.name());
        }
        return sb.toString();
    }

    /**
     * Spawns {@code want} fresh bots (level {@link #BOT_LEVEL_MIN}-{@link #BOT_LEVEL_MAX}) next to Lakelis on the
     * recruit map and waits for them to land. Every id made goes into {@code spawned} (for {@link #cleanup}); returns
     * the ones standing on the map. Blocks (call it off the packet thread).
     */
    /**
     * TEST 10-30: with the GM's party already full (an alt still out, the last run's bots not yet gone) the staging
     * asked for 0 bots, BotStore refused "Cannot reserve 0 on-demand slots" on the worker thread and the GM saw only
     * "Staging KPQ..." forever. Null = there is room; otherwise the line saying who fills the party.
     */
    static String noRoomForBots(int asked, int want, Character gm) {
        if (asked <= 0 || want > 0 || gm.getParty() == null) {
            return null;
        }
        List<String> others = new ArrayList<>();
        for (var m : gm.getParty().getMembers()) {
            if (m.getId() != gm.getId()) {
                others.add(m.getName());
            }
        }
        return "your party is full (" + String.join(", ", others) + " with you): no room for the bots."
                + " @dismiss your alts or leave the party, wait for the last run's bots to go, then try again";
    }

    public static List<Character> spawnBots(MapleMap recruit, int want, List<Integer> spawned, int nearNpc,
                                            int minLevel, int maxLevel) {
        if (want <= 0) {
            return new ArrayList<>();
        }
        NPC npc = nearNpc > 0 ? recruit.getNPCById(nearNpc) : null;
        Point by = npc != null ? npc.getPosition() : recruit.getPortal(0).getPosition();
        int slotBase = BotStore.nextDynamicSlots(want);
        List<Integer> mine = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            Point at = StageSpots.nearLevel(recruit, new Point(by.x + 40 * (i + 1), by.y));
            int id = BotGeneration.createBot(slotBase + i, at, recruit, 0, minLevel, maxLevel);
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
