package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pre-Zakum row and its solver. The row is generated from the mine maps' own door lists, so what is pinned here is
 * the walk's shape and the solver's four decisions: sweep a live chest, loot the floor, place the seven ores on the one
 * big chest, and leave through whichever door leads to the next stage's map.
 */
class ZakumPqSolversTest {

    /**
     * 1.6.6 zpq-fire-ore-name: the mines' three items go by their String.wz names. The code had 4001018 as the
     * "document", 4001016 as the "ore" and 4001015 as the "key"; String.wz says Fire Ore, The Key and Paper Document.
     */
    @Test
    void theMinesItemsAreNamedAsTheGameNamesThem() throws Exception {
        soloMapling.WzTestData.assumePresent("String.wz");
        String wz = System.getProperty("wz-path", "wz");
        String etc = java.nio.file.Files.readString(Path.of(wz, "String.wz", "Etc.img.xml"));
        assertEquals("Fire Ore", etcName(etc, ZakumMinesSolver.FIRE_ORE));
        assertEquals("The Key", etcName(etc, ZakumMinesSolver.KEY));
        assertEquals(7, ZakumMinesSolver.KEYS_NEEDED, "Reactor.wz 2112014 takes seven");
        assertEquals("Paper Document", etcName(etc, 4001015));
    }

    private static String etcName(String etcXml, int itemId) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<imgdir name=\"" + itemId
                + "\">\\s*<string name=\"name\" value=\"([^\"]*)\"").matcher(etcXml);
        return m.find() ? m.group(1) : null;
    }

    private static final int HUB = 280_010_000;
    private static final int ROOM = 280_010_010;

    private static MechanicsModel.Content row() {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "the data folder parses: " + r.errors());
        return r.content().stream().filter(c -> c.id().equals("zakumpq")).findFirst().orElseThrow();
    }

    @Test
    void theRowStartsAtTheHubAndWalksThirtyRoomsOnce() {
        MechanicsModel.Content c = row();
        assertEquals("ZakumPQ", c.eventScript());
        assertEquals(1, c.minPlayers());
        assertEquals(6, c.maxPlayers());
        assertEquals(50, c.minLevel());
        assertEquals(30, c.timeLimitMinutes());
        assertEquals(HUB, c.stages().get(0).mapId(), "the party spawns on the hub");

        // every stage's clear names the map the next stage stands on, which is how the party walks at all
        List<MechanicsModel.Stage> stages = c.stages();
        for (int i = 0; i < stages.size() - 1; i++) {
            assertEquals(stages.get(i + 1).mapId(), stages.get(i).clear().partyOnMap(),
                    "stage " + (i + 1) + " hands the party to stage " + (i + 2));
        }
        // and the walk covers every mine map the client has
        Set<Integer> visited = new java.util.HashSet<>(stages.stream().map(MechanicsModel.Stage::mapId).toList());
        assertEquals(30, visited.size(), "thirty distinct rooms, the hub included");
        assertTrue(visited.contains(280_011_005), "the big chest's room is in the walk");
        assertTrue(stages.get(stages.size() - 1).clear().onInstance(), "the last stage is the hand-in");
    }

    /**
     * 1.6.5 b331: a chest yields ONE drop, as it does for a real party (the bots' old fifth hit was on a state no client
     * can hit, gave a second drop and crashed clients). The big chest eats seven 4001016 and the maze has exactly seven
     * ore rocks (WZ Map.wz: 2112004 on 280010091/110/140 and 280011002/003, 2112011 on 280010041 and on 280011005
     * itself), so every one of them must be mined before the walk stands in front of 2112014. A rock met twice in the
     * walk (280010041) yields once.
     */
    @Test
    void enoughOreRoomsComeBeforeTheBigChestToFeedIt() {
        MechanicsModel.Content c = row();
        int big = -1;
        java.util.Set<String> rocksBefore = new java.util.TreeSet<>();
        for (int i = 0; i < c.stages().size(); i++) {
            MechanicsModel.Stage st = c.stages().get(i);
            if (st.hitReactors().contains(2_112_014)) {
                big = i;
                break;
            }
            for (int ore : List.of(2_112_004, 2_112_011)) {
                if (st.hitReactors().contains(ore)) {
                    rocksBefore.add(st.mapId() + ":" + ore);
                }
            }
        }
        assertTrue(big > 0, "the big chest is in the walk");
        java.util.Set<String> all = new java.util.TreeSet<>(List.of("280010091:2112004", "280010110:2112004",
                "280010140:2112004", "280011002:2112004", "280011003:2112004", "280010041:2112011",
                "280011005:2112011"));
        assertEquals(all, rocksBefore, "all seven ore rocks, one ore each, are mined before the big chest (stage "
                + (big + 1) + ")");
    }

    @Test
    void theChestIsHitUntilItAnswersMinusOne() {
        View v = new View();
        v.cleared = false;
        v.reactor = 0;
        SolverStep s = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.HitReactor.class, s);
        assertEquals(v.stage.hitReactors().get(0), ((SolverStep.HitReactor) s).reactorId(), "the room's own chest");
        v.reactor = -1; // spent: the fifth hit was the last one that dropped anything
        SolverStep out = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.EnterPortalTo.class, out, "a spent room is left through the next stage's door");
        assertEquals(row().stages().get(1).clear().partyOnMap(), ((SolverStep.EnterPortalTo) out).mapId(),
                "the door is the one that leads to the stage the row walks into next");
    }

    /** 1.6.5 b331: a chest standing in a state no client can hit (2112007's state 4) is spent: the miner moves on. */
    @Test
    void aChestInAStateNoClientCanHitIsSpentAndTheMinerMovesOn() {
        View v = new View();
        v.reactor = 4;
        v.hittable = false;
        SolverStep out = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.EnterPortalTo.class, out, "no HitReactor for a chest no client could hit");
    }

    @Test
    void theFloorIsLootedBeforeTheDoorIsTaken() {
        View v = new View();
        v.reactor = -1;
        v.floor = 2;
        SolverStep s = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.Loot.class, s);
        assertEquals(0, ((SolverStep.Loot) s).itemId(), "zero means the whole floor, mesos included");
    }

    /**
     * 1.6.5 b344e (TEST 7 Nov, run b344-zpq-stage-clear-20261107T023636Z, stage 33): the alt put its seven ores on
     * 2112014 and then held none, so it said "this chest wants seven ores and I have 0" every minute for good - the
     * want-ore wait came before anything else and never let it pick up the document or walk out. An opened chest
     * (past state 0) is done with ore: loot what it dropped, then the door.
     */
    @Test
    void anOpenedBigChestIsLootedAndLeftNotWaitedOnForMoreOre() {
        View v = new View();
        v.mapId = 280_011_005;
        v.stage = bigChestStage();
        v.ore = 0;
        v.reactor = 1; // the seven ores took: Reactor.wz/2112014 event 0 moves it to state 1, the document drops
        v.hittable = false;
        v.floor = 1;
        SolverStep s = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.Loot.class, s, "the document on the floor is picked up, not waited beside: " + s);

        v.floor = 0;
        v.document = 1;
        SolverStep out = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.EnterPortalTo.class, out, "then the miner walks out: " + out);
        assertEquals(bigChestStage().clear().partyOnMap(), ((SolverStep.EnterPortalTo) out).mapId());
    }

    /**
     * b344e: ores that lie on the floor in front of a chest still at state 0 are a drop the server has not taken yet
     * (it takes them 5 s after they land), so the miner leaves them there; only once that window is long past are they
     * a drop that did not take, and picked up to try again.
     */
    @Test
    void oresInFrontOfAClosedChestAreLeftForTheServerThenPickedUpToTryAgain() {
        View v = new View();
        v.mapId = 280_011_005;
        v.stage = bigChestStage();
        v.reactor = 0;
        v.hittable = false;
        v.ore = 7;
        v.now = 1_000;
        assertInstanceOf(SolverStep.PlaceItemOn.class, new ZakumMinesSolver().next(v, v.me));

        v.ore = 0;
        v.floor = 7;
        v.now = 4_000;
        SolverStep settling = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.Wait.class, settling, "inside the server's window the drop is left alone");

        v.now = 1_000 + ZakumMinesSolver.KEY_TAKE_WINDOW_MS + 1;
        assertInstanceOf(SolverStep.Loot.class, new ZakumMinesSolver().next(v, v.me),
                "past it, the ores did not take: pick them up and place again");
    }

    @Test
    void theBigChestWantsSevenOresAndRefusesFewer() {
        View v = new View();
        v.mapId = 280_011_005;
        v.stage = bigChestStage();
        v.reactor = 0;
        v.ore = 6;
        SolverStep short_ = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.Wait.class, short_, "six ores is not seven");

        v.ore = 7;
        SolverStep s = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.PlaceItemOn.class, s);
        SolverStep.PlaceItemOn place = (SolverStep.PlaceItemOn) s;
        assertEquals(2_112_014, place.reactorId());
        assertEquals(400_1016, place.itemId());
        assertEquals(7, place.count());
    }

    @Test
    void theHandInBelongsToTheLeaderAndNeedsTheDocument() {
        View v = new View();
        v.mapId = HUB;
        v.stage = handInStage();
        v.leader = true;
        v.document = 0;
        assertInstanceOf(SolverStep.Abandon.class, new ZakumMinesSolver().next(v, v.me),
                "no document means the big chest was never opened");

        v.document = 1;
        SolverStep s = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.TalkNpc.class, s);
        assertEquals(2_032_002, ((SolverStep.TalkNpc) s).npcId());

        v.leader = false;
        assertInstanceOf(SolverStep.Wait.class, new ZakumMinesSolver().next(v, v.me), "the hand-in is the leader's");
    }

    /**
     * 1.6.5 b344e hand-in: the owner's case is a real player leading Pre-Zakum with regulars. Aura (2032002.js:67-71)
     * takes the fire ore only from the event leader, and the regular that opened the big chest holds it, so the
     * regular walks it to the leader and drops it at his feet (the KPQ pass handover, {@link SolverStep.DropFor}).
     * Before this it said "the hand-in is the leader's" for good and nobody got a Piece of Fire Ore.
     */
    @Test
    void aRegularHoldingTheFireOreDropsItForARealLeaderOnTheHub() {
        View v = new View();
        v.mapId = HUB;
        v.stage = handInStage();
        v.document = 1;
        v.human = new RunMember(46, true, false, MechanicsPersona.PERFECT);
        SolverStep s = new ZakumMinesSolver().next(v, v.me);
        assertInstanceOf(SolverStep.DropFor.class, s, "the ore goes to the leader who can hand it in: " + s);
        SolverStep.DropFor d = (SolverStep.DropFor) s;
        assertEquals(400_1018, d.itemId());
        assertEquals(1, d.count());
        assertEquals(46, d.toCharacterId());
        assertEquals("[Zakum] Jinx dropped the Fire Ore at your feet. Pick it up and bring it to Aura.",
                StepExecutor.noteFor(d.note(), "Jinx"), "the leader is told what to do, by name");

        v.document = 0; // dropped: the leader has it now, and the regular waits for Aura instead of giving up the run
        assertInstanceOf(SolverStep.Wait.class, new ZakumMinesSolver().next(v, v.me));

        v.document = 1;
        v.humanMap = 280_010_010; // the leader is not on the hub yet: hold the ore till he is
        assertInstanceOf(SolverStep.Wait.class, new ZakumMinesSolver().next(v, v.me));
    }

    private static MechanicsModel.Stage bigChestStage() {
        return row().stages().stream().filter(st -> st.hitReactors().contains(2_112_014)).findFirst().orElseThrow();
    }

    private static MechanicsModel.Stage handInStage() {
        List<MechanicsModel.Stage> stages = row().stages();
        return stages.get(stages.size() - 1);
    }

    /** A stage view with just the fields this solver reads. */
    private static final class View implements StageView {
        final RunMember me = new RunMember(1, true, true, MechanicsPersona.PERFECT);
        MechanicsModel.Stage stage = row().stages().get(1);
        int mapId = stage.mapId();
        int reactor = 0;
        boolean hittable = true;
        int floor;
        int ore;
        int document;
        boolean leader;
        boolean cleared;
        long now;
        RunMember human; // a real-player leader, when the party has one
        int humanMap = HUB;
        final java.util.Map<String, Object> memory = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public String roleOf(int characterId) { return "miner"; }
        @Override public List<RunMember> members() { return human == null ? List.of(me) : List.of(human, me); }
        @Override public Optional<RunMember> leader() {
            return human != null ? Optional.of(human) : leader ? Optional.of(me) : Optional.empty();
        }
        @Override public int mapOf(int characterId) { return human != null && characterId == human.characterId() ? humanMap : mapId; }
        @Override public int reactorState(int reactorId) {
            return reactorId > 0 && stage.hitReactors().contains(reactorId) ? reactor : -1; // only this room's chest answers
        }
        @Override public boolean reactorHittable(int reactorId) { return hittable && reactorState(reactorId) >= 0; }
        @Override public int itemCount(int characterId, int itemId) {
            return switch (itemId) {
                case 400_1016 -> ore;
                case 400_1018 -> document;
                default -> 0;
            };
        }
        @Override public int floorItems(int itemId, int forCharacterId) { return floor; }
        @Override public boolean cleared() { return cleared; }
        @Override public long nowMs() { return now; }
        @Override public int partySize() { return 1; }
        @Override public OptionalInt answerFor(int characterId) { return OptionalInt.empty(); }
        @Override public Optional<String> eventProperty(String key) { return Optional.empty(); }
        @Override public int[] positionOf(int characterId) { return new int[]{0, 0}; }
        @Override public Optional<int[]> npcPosition(int npcId) { return Optional.empty(); }
        @Override public java.util.Map<String, Object> stageMemory() { return memory; }
    }
}
