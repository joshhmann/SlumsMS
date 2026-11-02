package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.BossTargetRules.Part;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 130 phase 5 phase B: Horntail, against a fake stage, no server. What the rooms really are, from our scripts:
 * room 1 (240060000) is empty until reactor {@code 2408003} spawns the first head 8810000, room 2 (240060100) the second
 * 8810001, and room 3 (240060200) is empty until reactor {@code 2401000} spawns the eight parts 8810002-8810009 and the
 * body 8810018 ({@code MapleMap.java:4908-4954}). Each door opens on the script's own {@code defeatedHead} count
 * ({@code scripts/portal/hontale_BR.js}), and the run is won when the script sets {@code defeatedBoss=1}.
 */
class HorntailPhasesTest {

    private static final int ROOM_1 = 240060000;
    private static final int ROOM_2 = 240060100;
    private static final int ROOM_3 = 240060200;

    private static final int HEAD_1 = 8810000;
    private static final int HEAD_2 = 8810001;
    private static final int FIRST_PART = 8810002;
    private static final int LAST_PART = 8810009;
    private static final int BODY = 8810018;

    private final BossPhasesSolver solver = new BossPhasesSolver();

    @Test
    void theBodyRoomIsEmptyUntilTheReactorSpawnsItsParts() {
        // 2401000.js: nothing in 240060200 at all until the reactor is hit - so the bots hit it themselves, which is
        // what lets a bot-only party get into the fight at all (its own "no 8810026 yet" guard makes a repeat harmless)
        SolverStep step = solver.next(new View(3, List.of(), Map.of()), member());

        SolverStep.HitReactor hit = assertInstanceOf(SolverStep.HitReactor.class, step);
        assertEquals(2401000, hit.reactorId(), "the reactor that spawns the whole body room");
        assertFalse(step instanceof SolverStep.Done, "an empty room is not a win");
    }

    @Test
    void thePartsAreTheFightOnceTheReactorSpawnedThem() {
        List<Part> parts = new ArrayList<>();
        for (int id = FIRST_PART; id <= LAST_PART; id++) {
            parts.add(part(id, BossTargetRules.Role.ARM));
        }
        parts.add(part(BODY, BossTargetRules.Role.BODY));

        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(new View(3, parts, Map.of()),
                member()));

        assertEquals(partIds(), fight.mobIds(), "all eight parts, in the data's order");
        assertFalse(fight.mobIds().contains(BODY), "the body is only offered once no part is left standing");
    }

    @Test
    void theBodyIsOnlyOfferedOnceNoPartIsLeft() {
        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class,
                solver.next(new View(3, List.of(part(BODY, BossTargetRules.Role.BODY)), Map.of()), member()));

        assertEquals(List.of(BODY), fight.mobIds());
    }

    @Test
    void theCorpsePartsAndTheIntroAreNeverATarget() {
        List<Integer> listed = List.of(8810015, 8810026, FIRST_PART, BODY, 8810019);

        assertNull(LiveStageView.partRole(8810015, listed), "8810010-8810017 are corpses (MobId.isDeadHorntailPart)");
        assertNull(LiveStageView.partRole(8810026, listed), "the intro is the summon animation, not a fight");
        assertEquals(BossTargetRules.Role.ARM, LiveStageView.partRole(FIRST_PART, listed));
        assertEquals(BossTargetRules.Role.BODY, LiveStageView.partRole(BODY, listed));
        assertEquals(BossTargetRules.Role.ADD, LiveStageView.partRole(8810019, listed),
                "only a stage that lists an id can make it a fight at all; ours lists none of the add family");
        assertEquals(BossTargetRules.Role.BODY, LiveStageView.partRole(HEAD_1, List.of(HEAD_1)));
        assertEquals(BossTargetRules.Role.BODY, LiveStageView.partRole(HEAD_2, List.of(HEAD_2)));
    }

    @Test
    void theFirstDoorOpensOnTheScriptsOwnHeadCount() {
        assertFalse(new View(1, List.of(), Map.of("defeatedHead", "0")).cleared(), "the count starts at 0");
        assertTrue(new View(1, List.of(), Map.of("defeatedHead", "1")).cleared());
        assertFalse(new View(1, List.of(), Map.of()).cleared());
    }

    @Test
    void theSecondDoorWaitsForTheSecondHead() {
        assertFalse(new View(2, List.of(), Map.of("defeatedHead", "1")).cleared(), "one head is not two");
        assertTrue(new View(2, List.of(), Map.of("defeatedHead", "2")).cleared());
    }

    @Test
    void aMemberStillInThePreviousRoomIsSentIntoTheNextOne() {
        View inRoom1 = new View(2, List.of(), Map.of("defeatedHead", "1"));
        inRoom1.at = ROOM_1;

        SolverStep.WarpTo warp = assertInstanceOf(SolverStep.WarpTo.class, solver.next(inRoom1, member()));

        assertEquals(ROOM_2, warp.mapId());
        assertInstanceOf(SolverStep.Fight.class, solver.next(new View(2, List.of(part(HEAD_2, BossTargetRules.Role.BODY)),
                Map.of("defeatedHead", "1", "summoned", "true")), member()),
                "already there, reactor touched: it fights, it is not moved again");
    }

    @Test
    void theRunIsWonOnceTheBodyFalls() {
        SolverStep step = solver.next(new View(3, List.of(part(BODY, BossTargetRules.Role.BODY)),
                Map.of("defeatedBoss", "1")), member());

        assertInstanceOf(SolverStep.Done.class, step);
    }

    @Test
    void theShippedRowIsThreeRoomsWithTheScriptsGates() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("HorntailBattle");

        assertTrue(content != null, "content/horntail.yaml must be listed in content/index.txt");
        assertEquals(MechanicsModel.Kind.BOSS, content.kind());
        assertEquals(240050400, content.recruitMap());
        assertEquals(240050600, content.exitMap(), "where a run ended by us sends a member; the script's own exit");
        assertEquals(6, content.minPlayers(), "HorntailBattle.js:5");
        assertEquals(30, content.maxPlayers());
        assertEquals(100, content.minLevel());
        assertEquals(255, content.maxLevel());
        assertEquals(120, content.timeLimitMinutes());
        assertEquals(List.of(ROOM_1, ROOM_2, ROOM_3),
                content.stages().stream().map(MechanicsModel.Stage::mapId).toList());
        for (MechanicsModel.Stage stage : content.stages()) {
            assertEquals(BossPhasesSolver.ID, stage.solver());
            assertNull(stage.nextPortal(), "the rooms are entered by map id, never by a portal name");
            assertEquals(0, stage.killTarget(), "no Horntail part is a kill target the script waits for");
        }
        assertEquals("summoned", content.stages().get(0).summonProperty(), "reactor 2408003 sets it");
        assertEquals("summoned", content.stages().get(1).summonProperty());
        assertNull(content.stages().get(2).summonProperty(), "reactor 2401000 sets no property at all");
        assertEquals("defeatedHead", content.stages().get(0).clear().eventProperty());
        assertEquals("1", content.stages().get(0).clear().equals());
        assertEquals("defeatedHead", content.stages().get(1).clear().eventProperty());
        assertEquals("2", content.stages().get(1).clear().equals());
        assertEquals("defeatedBoss", content.stages().get(2).clear().eventProperty());
        assertEquals("1", content.stages().get(2).clear().equals());
        assertEquals(9, content.stages().get(2).mobs().size(), "eight parts and the body; no corpses, no intro");
        assertFalse(content.stages().get(2).mobs().contains(8810026), "the intro is never a stage mob");
        for (int add = 8810019; add <= 8820023; add++) {
            for (MechanicsModel.Stage stage : content.stages()) {
                assertFalse(stage.mobs().contains(add), "the add family " + add + " is not in our scripts");
            }
        }
    }

    private static List<Integer> partIds() {
        List<Integer> ids = new ArrayList<>();
        for (int id = FIRST_PART; id <= LAST_PART; id++) {
            ids.add(id);
        }
        return ids;
    }

    private static Part part(int mobId, BossTargetRules.Role role) {
        return new Part(mobId, role, false, true, false, false, 0);
    }

    private static RunMember member() {
        return new RunMember(1, true, false, MechanicsPersona.PERFECT);
    }

    /** One of the shipped Horntail rooms with nothing live on it but what the test puts there. */
    private static final class View implements StageView {

        private final MechanicsModel.Stage stage;
        private final List<Part> parts;
        private final Map<String, String> props = new HashMap<>();
        /** The map the member stands on: the stage's own unless a test says it is still in the last room. */
        private int at;

        View(int stageNumber, List<Part> parts, Map<String, String> props) {
            this.stage = MechanicsRuns.contentFor("HorntailBattle").stages().get(stageNumber - 1);
            this.parts = parts;
            this.props.putAll(props);
            this.at = stage.mapId();
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public int mapOf(int characterId) {
            return at;
        }

        @Override
        public List<RunMember> members() {
            return List.of(member());
        }

        @Override
        public String roleOf(int characterId) {
            return "head";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public List<Part> bossParts() {
            return parts;
        }

        @Override
        public BossTargetRules.Member fighterOf(int characterId) {
            return new BossTargetRules.Member(false, true);
        }
    }
}
