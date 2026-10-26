package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.Character;
import client.inventory.WeaponType;
import constants.skills.Pirate;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.SkillPoseFixtureAccess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * A bot idling next to the player swings one of its job's attacks at the air now and then (BotFlavor ->
 * BotAttack.skillSwing). BotAttackClientValidityTest runs every such show-off attack through the client's rules; these
 * are the two cases that broke on a real client.
 *
 * - 1.5.0 rc6 (QA t-rv-4: "saw a skill cannot be used with equipped item"): a skill the weapon does not take is sent
 *   as the weapon's plain attack.
 * - 1.6.0 (TEST, QA rm-2's partner and the Mu Lung Dojo entrance): a gunslinger's Double Shot went out as a MELEE
 *   attack in its doublefire pose (86). A gun's afterimage has no range for it and the viewer's client closed. It now
 *   goes out as the shot a player's client sends.
 */
class FlavorSwingWeaponTest {

    private static final int GUN = 1492000, DAGGER = 1332041, CLAW = 1472023;
    private static final int CLOSE = 0xBA, RANGED = 0xBB;

    private Character bot;

    @BeforeEach
    void skillData() {
        SkillPoseFixtureAccess.install();
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_001);
    }

    /* opcode, skill id and pose of an attack packet (the layout ClientAttackRules decodes). */
    private static int[] decode(Packet p) {
        ByteBuffer b = ByteBuffer.wrap(p.getBytes()).order(ByteOrder.LITTLE_ENDIAN);
        int type = b.getShort() & 0xFFFF;
        b.getInt();
        b.get();
        b.get();
        int slv = b.get() & 0xFF;
        int skill = slv > 0 ? b.getInt() : 0;
        b.get();
        int action = b.getShort() & 0x7FFF;
        return new int[]{type, skill, action};
    }

    @Test
    void aGunslingersDoubleShotIsAShotNotAMeleeSwing() {
        Packet p = BotAttack.showOffPacket(bot, BotAttackProfile.ranged(Pirate.DOUBLE_SHOT, 2), 20, GUN,
                WeaponType.GUN, false);
        int[] d = decode(p);
        assertEquals(RANGED, d[0], "Double Shot goes out on the shoot route");
        assertEquals(Pirate.DOUBLE_SHOT, d[1]);
        assertEquals(86, d[2], "in its doublefire pose");
    }

    @Test
    void aSkillTheWeaponCannotUseIsSentAsThePlainAttack() {
        int[] claw = decode(BotAttack.showOffPacket(bot, BotAttackProfile.ranged(14121001, 5), 10, CLAW,
                WeaponType.CLAW, false));
        assertEquals(14121001, claw[1], "Quintuple Star with a claw");
        int[] dagger = decode(BotAttack.showOffPacket(bot, BotAttackProfile.ranged(14121001, 5), 10, DAGGER,
                WeaponType.DAGGER_OTHER, false));
        assertEquals(CLOSE, dagger[0], "... with a dagger: a plain swing");
        assertEquals(0, dagger[1]);
    }

    @Test
    void aMageWithoutAWandShowsNothing() {
        assertNull(BotAttack.showOffPacket(bot, BotAttackProfile.magic(2001005, 2), 20, 0, null, false));
    }
}
