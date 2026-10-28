package soloMapling.companions;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What a viewer is told when an alt is summoned: a headless alt never moves, so the spawn packet is all it sees. */
class OwnedAltSpawnPacketTest {
    private static Character character(int id) {
        BotClient c = new BotClient(1, 1);
        c.setAccID(7);
        Character chr = Character.getDefault(c);
        chr.setID(id);
        chr.setWorld(1);
        c.setPlayer(chr);
        return chr;
    }

    @Test void summonedAltIsSentStandingOnItsGroundedPositionNotMidJump() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(1);
        when(owner.getAccountID()).thenReturn(7);
        when(owner.getWorld()).thenReturn(1);
        Character alt = character(2);
        alt.markOwnedAlt(owner);
        alt.setPosition(new Point(100, -151)); // OwnedAltBackend.attach: groundedSpawn
        alt.setStance(OwnedAltService.standingStance(7)); // owner mid-jump facing left
        // MapleMap.addPlayer broadcasts the spawn with enteringField = true
        assertArrayEquals(new int[]{100, -151, 5}, PacketCreator.spawnPose(alt, true)); // not 42 px up, jumping (6)
    }

    @Test void aNormalCharacterStillEntersTheFieldAsBefore() {
        Character chr = character(3);
        chr.setPosition(new Point(100, -151));
        chr.setStance(4);
        assertArrayEquals(new int[]{100, -193, 6}, PacketCreator.spawnPose(chr, true));
        assertArrayEquals(new int[]{100, -151, 4}, PacketCreator.spawnPose(chr, false));
    }

    @Test void standingStanceKeepsOnlyTheOwnersFacing() {
        assertEquals(4, OwnedAltService.standingStance(0));
        assertEquals(4, OwnedAltService.standingStance(6));
        assertEquals(5, OwnedAltService.standingStance(7));
        assertEquals(5, OwnedAltService.standingStance(3));
    }
}
