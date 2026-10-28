package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Count;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageDataTest {

    private static Content kpq() {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
        return r.content().stream().filter(c -> c.id().equals("kpq")).findFirst().orElseThrow();
    }

    private static List<String> errors(String yaml) throws Exception {
        return StageData.parse(new StringReader(yaml)).errors();
    }

    /** A valid one-stage file; tests break one thing at a time. */
    private static final String GOOD = """
            id: t
            name: Test PQ
            kind: PQ
            minPlayers: 2
            maxPlayers: 4
            sources: [ "ours" ]
            stages:
              - stage: 1
                map: 100
                npc: 9000
                solver: question-coupons
                roles:
                  - { id: leader, who: LEADER, min: 1, max: 1 }
                  - { id: answerer, who: NON_LEADER, min: party-1, max: party-1 }
                items:
                  - { id: 4001007, name: Coupon, cleanup: true }
                objectives:
                  - { id: farm, kind: KILL_FOR_ITEM, role: answerer, mob: 1, item: 4001007, count: answer }
                answers:
                  - { id: 1, answer: 10 }
                clear: { eventProperty: 1stageclear }
            """;

    @Test
    void shippedKpqLoadsClean() {
        Content c = kpq();
        assertEquals(MechanicsModel.Kind.PQ, c.kind());
        assertEquals(3, c.minPlayers());
        assertEquals(4, c.maxPlayers());
        assertEquals(30, c.timeLimitMinutes());
        assertFalse(c.sources().isEmpty());
        Stage s1 = c.stage(1).orElseThrow();
        assertEquals(103000800, s1.mapId());
        assertEquals(9020001, s1.npcId());
        assertEquals("question-coupons", s1.solver());
        assertEquals("1stageclear", s1.clear().eventProperty());
        assertEquals(s1, c.stageOnMap(103000800).orElseThrow());
        assertEquals(List.of("leader", "answerer"), s1.roles().stream().map(MechanicsModel.Role::id).toList());
        assertEquals(List.of(ObjectiveKind.TALK_NPC, ObjectiveKind.KILL_FOR_ITEM, ObjectiveKind.HAND_IN,
                        ObjectiveKind.GIVE_TO_LEADER, ObjectiveKind.ENTER_PORTAL),
                s1.objectivesFor("answerer").stream().map(MechanicsModel.Objective::kind).toList());
        assertEquals(List.of("hand-passes", "next"),
                s1.objectivesFor("leader").stream().map(MechanicsModel.Objective::id).toList());
    }

    @Test
    void kpqAnswersMatchClotosScript() throws Exception {
        String js = Files.readString(Path.of("scripts/npc/9020001.js"));
        Matcher m = Pattern.compile("var stage1Answers = Array\\(([^)]*)\\)").matcher(js);
        assertTrue(m.find(), "stage1Answers not found in Cloto's script");
        List<Integer> script = new ArrayList<>();
        for (String n : m.group(1).split(",")) {
            script.add(Integer.parseInt(n.trim()));
        }
        Stage s1 = kpq().stage(1).orElseThrow();
        assertEquals(script.size(), s1.answers().size());
        for (int i = 0; i < script.size(); i++) {
            // Cloto's grid holds the question number counted from 1
            assertEquals(OptionalInt.of(script.get(i)), s1.answer(i + 1), "question " + (i + 1));
        }
    }

    @Test
    void kpqItemsAndCountsResolvePerParty() {
        Stage s1 = kpq().stage(1).orElseThrow();
        var farm = s1.objectives().stream().filter(o -> o.id().equals("farm")).findFirst().orElseThrow();
        assertEquals(4001007, farm.itemId());
        assertEquals(9300001, farm.mobId());
        assertEquals(OptionalInt.of(25), farm.count().orElseThrow().resolve(4, OptionalInt.of(25)));
        assertTrue(farm.count().orElseThrow().resolve(4, OptionalInt.empty()).isEmpty());
        var passes = s1.objectives().stream().filter(o -> o.id().equals("hand-passes")).findFirst().orElseThrow();
        assertEquals(OptionalInt.of(2), passes.count().orElseThrow().resolve(3, OptionalInt.empty()));
        assertEquals(OptionalInt.of(3), passes.count().orElseThrow().resolve(4, OptionalInt.empty()));
        var answerer = s1.role("answerer").orElseThrow();
        assertEquals(3, answerer.minFor(4));
        assertEquals(2, answerer.maxFor(3));
    }

    @Test
    void normalTierFallsBackToStockNumbers() {
        Content c = kpq();
        var normal = c.rules(Tier.NORMAL).orElseThrow();
        assertEquals(21, normal.minLevel());
        assertEquals(30, normal.timeLimitMinutes());
        assertEquals(1.0, normal.mobHpMultiplier());
        assertTrue(c.rules(Tier.MASTER).isEmpty(), "Hard and up wait for the owner's numbers");
    }

    @Test
    void goodFileParses() throws Exception {
        assertEquals(List.of(), errors(GOOD));
    }

    @Test
    void countParsing() {
        assertTrue(Count.parse("answer").orElseThrow().needsAnswer());
        assertTrue(Count.parse("party - 1").isPresent());
        assertTrue(Count.parse("-3").isEmpty());
        assertTrue(Count.parse("lots").isEmpty());
        assertEquals(OptionalInt.of(0), Count.parse("party-1").orElseThrow().resolve(1, OptionalInt.empty()));
    }

    @Test
    void rejectsMistakes() throws Exception {
        assertHas(errors(GOOD.replace("role: answerer, mob", "role: ghost, mob")), "unknown role ghost");
        assertHas(errors(GOOD.replace("""
                    answers:
                      - { id: 1, answer: 10 }
                """, "")), "no answers");
        assertHas(errors(GOOD.replace("sources: [ \"ours\" ]", "")), "sources");
        assertHas(errors(GOOD.replace("stage: 1", "stage: 2")), "numbered");
        assertHas(errors(GOOD.replace("item: 4001007, count", "item: 4001008, count")), "not under items");
        assertHas(errors(GOOD.replace("kind: KILL_FOR_ITEM", "kind: DANCE")), "unknown value DANCE");
        assertHas(errors(GOOD.replace("clear: { eventProperty: 1stageclear }", "")), "no clear");
        assertHas(errors(GOOD.replace("{ id: leader, who: LEADER, min: 1, max: 1 }",
                "{ id: leader, who: LEADER, min: 1, max: 2 }")), "only one member is the leader");
        assertHas(errors(GOOD.replace("maxPlayers: 4", "maxPlayers: 1")), "minPlayers <= maxPlayers");
        assertHas(errors(GOOD.replace("solver: question-coupons", "")), "no solver");
        assertHas(errors(GOOD.replace("kind: PQ", "kind: PQ\ntiers:\n  LEGENDARY: { minLevel: 1 }")), "unknown tier");
    }

    @Test
    void bossPhasesMustRunDownWithoutOverlap() throws Exception {
        String boss = GOOD + """
                    phases:
                      - { id: p1, hpFromPct: 100, hpToPct: 60, actions: [ "tank faces left" ] }
                      - { id: p2, hpFromPct: 59, hpToPct: 0, actions: [ "dispel", "burst" ] }
                """;
        StageData.Result ok = StageData.parse(new StringReader(boss));
        assertEquals(List.of(), ok.errors());
        assertEquals(List.of("dispel", "burst"), ok.content().get(0).stage(1).orElseThrow().phases().get(1).actions());
        assertHas(errors(boss.replace("hpFromPct: 59", "hpFromPct: 70")), "HP bands");
    }

    @Test
    void tiersParse() throws Exception {
        String tiered = GOOD.replace("kind: PQ", """
                kind: PQ
                tiers:
                  HARD: { minLevel: 40, mobHpMultiplier: 4, timeLimitMinutes: 25, botMistakeFactor: 1.25 }""");
        StageData.Result r = StageData.parse(new StringReader(tiered));
        assertEquals(List.of(), r.errors());
        var hard = r.content().get(0).rules(Tier.HARD).orElseThrow();
        assertEquals(40, hard.minLevel());
        assertEquals(4.0, hard.mobHpMultiplier());
        assertEquals(1.25, hard.botMistakeFactor());
        assertHas(errors(tiered.replace("mobHpMultiplier: 4", "mobHpMultiplier: 0")), "positive");
    }

    @Test
    void switchedOffByDefault() {
        assertFalse(Mechanics.enabled(), "ticket 114 scaffold must stay off unless -Dslums.mechanics=true");
    }

    private static void assertHas(List<String> errors, String part) {
        assertTrue(errors.stream().anyMatch(e -> e.contains(part)), "expected an error with '" + part + "' in " + errors);
    }
}
