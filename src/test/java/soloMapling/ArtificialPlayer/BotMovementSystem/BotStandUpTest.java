package soloMapling.ArtificialPlayer.BotMovementSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.0 rc6 (QA t-af-1): "when playerbot sits and you double click em, their chair still there when they're
 * standing". A seated bot that turns to a player, starts moving or leaves for a scene gets up properly first: the
 * chair is cleared for everyone watching (show-chair with chair 0).
 */
class BotStandUpTest {

    final List<Integer> cancelled = new ArrayList<>();

    @AfterEach
    void tearDown() {
        MovementCommands.chairCancel = MovementCommands::botCancelChair;
    }

    Character bot(int id, int chair) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getChair()).thenReturn(chair);
        when(c.getPosition()).thenReturn(new Point(100, 0));
        when(c.getStance()).thenReturn(4); // facing right
        return c;
    }

    @Test
    void aSeatedBotGetsUpAndAStandingOneIsLeftAlone() {
        MovementCommands.chairCancel = c -> cancelled.add(c.getId());
        MovementCommands.standUp(bot(1, 3010000));
        MovementCommands.standUp(bot(2, -1));
        MovementCommands.standUp(null);
        assertEquals(List.of(1), cancelled);
    }

    @Test
    void turningToAPlayerFromAChairStandsUpFirst() {
        MovementCommands.chairCancel = c -> cancelled.add(c.getId());
        MovementCommands.botFaceTowardsPoint(bot(3, 3010000), new Point(200, 0)); // to its right: no turn needed
        assertEquals(List.of(3), cancelled);
    }

    @Test
    void everyWayABotLeavesItsSeatStandsItUp() throws Exception {
        String gc = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java"));
        assertTrue(gc.contains("MovementCommands.standUp(bot); // rc6: never walk off seated"), "move and follow");
        String social = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java"));
        int menu = social.indexOf("public void openMenuFor(Character player)");
        assertTrue(social.indexOf("botFaceTowardsPoint(getChr(), player.getPosition());", menu) > menu,
                "the double-click menu turns (and so stands) the bot");
        String kit = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/DramaKit.java"));
        assertTrue(kit.contains("MovementCommands.standUp(bot); // rc6: no chair along"), "a scene takes it standing");
    }
}
