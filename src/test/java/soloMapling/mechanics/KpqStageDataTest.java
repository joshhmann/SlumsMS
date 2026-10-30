package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Position;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 KPQ automation: the stage data for KPQ stages 2-5 and the bonus is a copy of our own scripts, and this test
 * keeps it one: the rectangles and combos of stages 2-4 are re-read from {@code scripts/npc/9020001.js} in its order,
 * the pass count of stage 5, the prize warp, the portal gates ({@code scripts/portal/kpq0..3.js}) and Nella's exit.
 */
class KpqStageDataTest {

    static final String CLOTO = "scripts/npc/9020001.js";

    static Content kpq() {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        return r.content().stream().filter(c -> c.id().equals("kpq")).findFirst().orElseThrow();
    }

    static List<int[]> scriptRects(String js, int stage) {
        Matcher m = Pattern.compile("var stage" + stage + "Rects = Array\\((.*?)\\);", Pattern.DOTALL).matcher(js);
        assertTrue(m.find(), "stage" + stage + "Rects not in Cloto's script");
        Matcher r = Pattern.compile("new Rectangle\\((-?\\d+), (-?\\d+), (\\d+), (\\d+)\\)").matcher(m.group(1));
        List<int[]> out = new ArrayList<>();
        while (r.find()) {
            out.add(new int[]{Integer.parseInt(r.group(1)), Integer.parseInt(r.group(2)), Integer.parseInt(r.group(3)),
                    Integer.parseInt(r.group(4))});
        }
        return out;
    }

    static List<List<Integer>> scriptCombos(String js, int stage) {
        Matcher m = Pattern.compile("var stage" + stage + "Combos = Array\\((.*?)\\);", Pattern.DOTALL).matcher(js);
        assertTrue(m.find(), "stage" + stage + "Combos not in Cloto's script");
        Matcher c = Pattern.compile("Array\\(([\\d, ]+)\\)").matcher(m.group(1));
        List<List<Integer>> out = new ArrayList<>();
        while (c.find()) {
            List<Integer> mask = new ArrayList<>();
            for (String v : c.group(1).split(",")) {
                mask.add(Integer.parseInt(v.trim()));
            }
            out.add(mask);
        }
        return out;
    }

    @Test
    void combinationStagesAreTheScriptsRectanglesAndCombosInItsOrder() throws Exception {
        String js = Files.readString(Path.of(CLOTO));
        Content c = kpq();
        for (int stage = 2; stage <= 4; stage++) {
            Stage s = c.stage(stage).orElseThrow();
            assertEquals("combination", s.solver());
            assertEquals(103000800 + stage - 1, s.mapId());
            assertEquals("stg" + stage + "Property", s.answerProperty(), "the property the script keeps");
            assertTrue(js.contains("var stgProperty = \"stg" + stage + "Property\""), "script uses " + s.answerProperty());
            assertEquals(stage + "stageclear", s.clear().eventProperty());
            List<int[]> rects = scriptRects(js, stage);
            assertEquals(rects.size(), s.positions().size(), "stage " + stage);
            for (int i = 0; i < rects.size(); i++) {
                Position p = s.positions().get(i);
                int[] r = rects.get(i);
                assertEquals(List.of(r[0], r[1], r[2], r[3]), List.of(p.x(), p.y(), p.w(), p.h()), "stage " + stage
                        + " position " + (i + 1));
                assertEquals(stage == 2, p.rope(), "stage 2 is ropes");
                // the solver aims at the centre: it must count for the script (java.awt.Rectangle.contains)
                assertTrue(new java.awt.Rectangle(r[0], r[1], r[2], r[3]).contains(p.centerX(), p.centerY()));
            }
            assertEquals(scriptCombos(js, stage), s.combos(), "stage " + stage + " combos");
            assertEquals(3, s.occupied(), "three members on the puzzle");
            assertEquals("next00", s.nextPortal());
        }
    }

    @Test
    void stageFiveTakesTenPassesAndThePrizeSendsToTheBonus() throws Exception {
        String js = Files.readString(Path.of(CLOTO));
        Matcher m = Pattern.compile("cm\\.haveItem\\(4001008, (\\d+)\\)").matcher(js);
        assertTrue(m.find());
        Stage s5 = kpq().stage(5).orElseThrow();
        assertEquals("kill-collect-hand-in", s5.solver());
        assertEquals(103000804, s5.mapId());
        assertEquals("5stageclear", s5.clear().eventProperty());
        var hand = s5.objectives().stream().filter(o -> o.kind() == MechanicsModel.ObjectiveKind.HAND_IN).findFirst()
                .orElseThrow();
        assertEquals(OptionalInt.of(Integer.parseInt(m.group(1))), hand.count().orElseThrow().resolve(4,
                OptionalInt.empty()));
        assertEquals(4001008, hand.itemId());
        assertEquals(List.of(9300000, 9300002, 9300003), s5.mobs());

        MechanicsModel.AfterClear after = kpq().afterClear();
        assertNotNull(after);
        assertEquals(9020001, after.rewardNpc());
        assertEquals(103000804, after.rewardMap());
        assertTrue(js.contains("cm.warp(" + after.bonusMap() + ", \"st00\")"), "Cloto's prize warps to the bonus");
        String nella = Files.readString(Path.of("scripts/npc/" + after.exitNpc() + ".js"));
        assertTrue(nella.contains("cm.warp(" + kpq().exitMap() + ", \"st00\")"), "Nella sends members out");
        assertTrue(after.bonusMinSeconds() > 0 && after.bonusMaxSeconds() >= after.bonusMinSeconds());
    }

    @Test
    void stagePortalsOpenOnTheClearPropertyAndLeadToTheNextStage() throws Exception {
        Content c = kpq();
        for (int stage = 1; stage <= 4; stage++) {
            String js = Files.readString(Path.of("scripts/portal/kpq" + (stage - 1) + ".js"));
            assertTrue(js.contains("eim.getProperty(\"" + stage + "stageclear\") != null"), "kpq" + (stage - 1));
            int next = c.stage(stage + 1).orElseThrow().mapId();
            assertTrue(js.contains("getMapInstance(" + next + ")"), "kpq" + (stage - 1) + " leads to " + next);
            // linkToNextStage names the portal script "kpq" + (stage - 1) on the stage's next00
            assertEquals("next00", c.stage(stage).orElseThrow().nextPortal());
        }
        assertEquals(null, c.stage(5).orElseThrow().nextPortal(), "stage 5 leaves through Cloto's prize");
    }

    @Test
    void comboDataMustFitThePositions() throws Exception {
        String bad = """
                id: x
                name: X
                kind: PQ
                sources: [ours]
                stages:
                  - stage: 1
                    map: 100
                    solver: combination
                    roles: [{ id: leader, who: LEADER, min: 1, max: 1 }]
                    objectives: [{ id: check, kind: ANSWER, role: leader }]
                    positions:
                      - { id: a, x: 0, y: 0, w: 4, h: 4 }
                      - { id: b, x: 10, y: 0, w: 4, h: 4 }
                    combos:
                      - [1, 0, 1]
                      - [1, 2]
                    clear: { eventProperty: 1stageclear }
                """;
        StageData.Result r = StageData.parse(new java.io.StringReader(bad));
        assertTrue(r.content().isEmpty());
        String all = String.join("\n", r.errors());
        assertTrue(all.contains("3 entries for 2 positions"), all);
        assertTrue(all.contains("only 0 and 1"), all);
        assertTrue(all.contains("answerProperty"), all);
    }
}
