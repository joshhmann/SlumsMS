package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.3 cv-13 (TEST log 2026-11-01): a level 34 bot answered "where should i train" with "i train at swampy land in a
 * deep forest" - map 105040000, which LevelingPath never lists for any band. The bot's own spot came from the map it
 * stood on, so any grind map it happened to be on became a training claim. A TRAIN_SPOT reply may only name spots on
 * the path: the asker's band for the tip, the bot's own band for what it says about itself.
 */
class TrainSpotReplyTest {

    private static final int BOT_LEVEL = 34;
    private static final int BOT_JOB = 3100000; // hunter
    private static final int PLAYER_LEVEL = 34;
    private static final int PLAYER_JOB = 1000000; // warrior
    private static final int SWAMPY_LAND = 105040000; // "Swampy Land in a Deep Forest", not on the path
    private static final int DEEP_FOREST_HUNTING = 105040100; // path entry for the 27-35 band

    private static Character bot(int level, int jobId, int mapId, String mapName) {
        Character chr = mock(Character.class);
        when(chr.getLevel()).thenReturn(level);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(jobId);
        when(chr.getJob()).thenReturn(job);
        when(chr.getMapId()).thenReturn(mapId);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapName()).thenReturn(mapName);
        when(map.getAllMonsters()).thenReturn(List.of());
        when(chr.getMap()).thenReturn(map);
        return chr;
    }

    /** The path's own names for a bot of this level and job, lower-case. */
    private static Set<String> pathNames(int level, int jobId) {
        Set<String> out = new TreeSet<>();
        for (TrainingTips.Spot s : TrainingTips.spotsFor(level, jobId, 10)) {
            out.add(s.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static ReplyPlanner.View view(String mySpot) {
        List<String> spots = BotReplyView.spots(PLAYER_LEVEL, PLAYER_JOB);
        return new ReplyPlanner.View("toasttty", BOT_LEVEL, "hunter", "deep forest", false, List.of("stirge"),
                "grinding", false, false, "Slayer", PLAYER_LEVEL, "warrior", false, ReplyPlanner.Bond.STRANGER,
                null, null, null, 0, spots, mySpot, false, "where should i train", ChatTriage.Kind.TALK,
                ChatTriage.Intent.NONE, null);
    }

    @Test
    void aBotGrindingOffThePathStillNamesATrainingSpotFromItsOwnBand() {
        String mine = BotReplyView.mySpot(bot(BOT_LEVEL, BOT_JOB, SWAMPY_LAND, "Swampy Land in a Deep Forest"),
                false, "grinding");
        assertNotNull(mine, "the bot has a path spot for its level, so it has something true to say");
        assertFalse(mine.toLowerCase(Locale.ROOT).contains("swampy"),
                () -> "the map it stands on is not a training spot: " + mine);
        assertTrue(pathNames(BOT_LEVEL, BOT_JOB).contains(mine.toLowerCase(Locale.ROOT)),
                () -> mine + " is not on LevelingPath for level " + BOT_LEVEL);
    }

    @Test
    void aBotGrindingOnAPathMapNamesThatMap() {
        int aran = 2100; // the 21-30 band lists the deep forest hunting ground for aran
        String mine = BotReplyView.mySpot(bot(28, aran, DEEP_FOREST_HUNTING, "Deep Forest I"),
                false, "grinding");
        assertEquals("Hunting Ground in the Deep Forest", mine,
                "a bot standing on a real spot names it, in the path's words");
    }

    @Test
    void aTrainSpotReplyOnlyNamesSpotsOnThePath() {
        ReplyPlanner.View v = view(BotReplyView.mySpot(
                bot(BOT_LEVEL, BOT_JOB, SWAMPY_LAND, "Swampy Land in a Deep Forest"), false, "grinding"));
        ReplyPlanner.Plan p = ReplyPlanner.plan(v);
        assertEquals(ReplyPlanner.Move.TRAIN_SPOT, p.move());

        Set<String> allowed = new TreeSet<>(pathNames(BOT_LEVEL, BOT_JOB));
        allowed.addAll(new ArrayList<>(v.playerSpots()).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());

        for (String fact : p.facts()) {
            assertFalse(fact.toLowerCase(Locale.ROOT).contains("swampy"), () -> "off-path fact: " + fact);
        }
        String own = p.facts().stream().filter(f -> f.startsWith("you train at ")).findFirst().orElse(null);
        assertNotNull(own, () -> "the plan must say where this bot trains: " + p.facts());
        assertTrue(allowed.contains(own.substring("you train at ".length()).toLowerCase(Locale.ROOT)),
                () -> "off-path spot: " + own);

        List<String> scripts = ReplyPlanner.scripts(v, ReplyPlanner.Move.TRAIN_SPOT);
        assertFalse(scripts.isEmpty());
        for (String line : scripts) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains("swampy"), () -> "off-path line: " + line);
        }
        assertTrue(scripts.contains("i train at " + v.mySpot()),
                () -> "the scripted fallback must name the bot's own spot: " + scripts);
    }
}
