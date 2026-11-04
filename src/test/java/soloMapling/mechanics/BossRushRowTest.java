package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eight Boss Rush rows are <b>generated</b>, so this test is the generator's own assertions rather than a
 * transcription of what someone typed: the row count, every emitted map id, the {@code loopTo}, and that the layout the
 * rows use is the one the scripts describe.
 *
 * <p>The arithmetic under test, quoted so it can be checked without the scripts open:
 *
 * <ul>
 *   <li>the band is the instance index - `scripts/npc/9000037.js:162-166` calls {@code em.startInstance(i, …)} with
 *       {@code i} from {@code detectTeamLobby} (`:40-70`, the party's average level, 0..7);</li>
 *   <li>the event stores it and warps to {@code entryMap + lobby} (`scripts/event/BossRushPQ.js:136`, `:146-148`), so a
 *       band's first wave is {@code 970030100 + band};</li>
 *   <li>each wave is {@code mapId + 100} while the last three digits are at least 100
 *       (`scripts/portal/raid_stage.js:26-32`), so the five waves are {@code +0, +100, +200, +300, +400}, and the fifth
 *       one's remainder ({@code 500 % 500 < 100}) sends the party to the rest spot {@code 970030001}.</li>
 * </ul>
 *
 * <p>Why test-side and not build-side: the repo has no code generator, and a checked-in YAML that only a generator can
 * reproduce would rot the first time someone regenerates it by hand. The rows are generated and committed; this test is
 * what makes the committed files provably the generator's output.
 */
class BossRushRowTest {

    private static final int BANDS = 8;
    private static final int WAVES = 5;
    private static final int FIRST_WAVE = 970_030_100;
    private static final int REST_SPOT = 970_030_001;

    /** Eight rows share one event script, so the lookup is by id over the whole folder, not {@code contentFor}. */
    private static MechanicsModel.Content row(int band) {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "the data folder parses: " + r.errors());
        return r.content().stream().filter(c -> c.id().equals("bossrush" + band)).findFirst()
                .orElseThrow(() -> new AssertionError("no row bossrush" + band));
    }

    /** The maps the generator emits for a band: five waves then the rest spot. */
    static List<Integer> mapsFor(int band) {
        List<Integer> maps = new ArrayList<>();
        for (int wave = 0; wave < WAVES; wave++) {
            maps.add(FIRST_WAVE + band + 100 * wave);
        }
        maps.add(REST_SPOT);
        return maps;
    }

    @Test
    void thereIsOneRowPerBandAndEveryBandIsTheSameShape() {
        for (int band = 0; band < BANDS; band++) {
            MechanicsModel.Content c = row(band);
            assertEquals(WAVES + 1, c.stages().size(), "band " + band + ": five waves and the rest spot");
            assertEquals(mapsFor(band), c.stages().stream().map(MechanicsModel.Stage::mapId).toList(),
                    "band " + band + ": every emitted map id");
            assertEquals("BossRushPQ", c.eventScript());
            assertEquals(1, c.minPlayers());
            assertEquals(6, c.maxPlayers(), "BossRushPQ.js:27");
            assertEquals(1, c.minLevel(), "BossRushPQ.js:28");
            assertEquals(5, c.timeLimitMinutes(), "the five-minute clock is what ends the run");
        }
    }

    @Test
    void everyWaveIsClearedByTheDoorAndNothingElse() {
        for (int band = 0; band < BANDS; band++) {
            for (int stage = 0; stage < WAVES; stage++) {
                MechanicsModel.Stage s = row(band).stages().get(stage);
                assertTrue(s.clear().mapEmpty(),
                        "band " + band + " wave " + (stage + 1) + ": raid_stage.js:28 opens the door while the map is empty");
                assertTrue(s.clear().eventProperty() == null, "no property is involved in a wave");
                assertEquals(0, s.loopTo(), "a wave advances");
            }
        }
    }

    @Test
    void theLastStageLoopsBackToTheFirstWaveAndTheClockEndsIt() {
        for (int band = 0; band < BANDS; band++) {
            List<MechanicsModel.Stage> stages = row(band).stages();
            MechanicsModel.Stage last = stages.get(stages.size() - 1);
            assertEquals(REST_SPOT, last.mapId(), "the rest spot is where the fifth wave lands");
            assertEquals(1, last.loopTo(), "band " + band + ": the set repeats from its first wave");
        }
    }

    @Test
    void noTwoBandsShareAWave() {
        // a guard on the generator itself: the bands are 100 apart, so no two bands may name the same wave map. The rest
        // spot is deliberately shared - raid_stage.js:26-32 sends every band to 970030001, because the remainder it
        // computes is 0 to 7 and divides by 500.
        for (int a = 0; a < BANDS; a++) {
            for (int b = a + 1; b < BANDS; b++) {
                for (int wave = 0; wave < WAVES; wave++) {
                    int map = mapsFor(a).get(wave);
                    assertFalse(mapsFor(b).contains(map), "bands " + a + " and " + b + " share wave map " + map);
                }
            }
        }
        // and every band really does land on the same rest spot, which is what the scripts compute
        for (int band = 0; band < BANDS; band++) {
            assertEquals(REST_SPOT, mapsFor(band).get(WAVES));
        }
    }
}
