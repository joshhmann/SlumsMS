package soloMapling.companions;

import client.Character;
import client.Client;
import config.YamlConfig;
import net.packet.InPacket;
import net.server.channel.handlers.AcceptFamilyHandler;
import net.server.channel.handlers.FamilyAddHandler;
import net.server.channel.handlers.GuildOperationHandler;
import net.server.coordinator.world.InviteCoordinator;
import net.server.guild.Guild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * e02: an owned alt answers family and guild invites from its own owner when
 * {@code -Dslums.ownedalts.acceptownerinvite=true}, and nothing else changes: population bots, another player's alt
 * and any other owner still get the refusal (or the packet that fizzles) they got before.
 */
class OwnedAltOwnerInviteTest {

    private String before;
    private boolean familyBefore;

    @BeforeEach
    void setUp() {
        before = System.getProperty(OwnedAltInvite.OWNER_INVITE_SWITCH);
        familyBefore = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = true;
        System.clearProperty(OwnedAltInvite.OWNER_INVITE_SWITCH);
    }

    @AfterEach
    void tearDown() {
        if (before == null) {
            System.clearProperty(OwnedAltInvite.OWNER_INVITE_SWITCH);
        } else {
            System.setProperty(OwnedAltInvite.OWNER_INVITE_SWITCH, before);
        }
        YamlConfig.config.server.USE_FAMILY_SYSTEM = familyBefore;
    }

    private static Character owner(int id) {
        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(id);
        when(owner.getName()).thenReturn("Owner" + id);
        return owner;
    }

    private static Character alt(String name, Character owner) {
        Character alt = mock(Character.class, RETURNS_DEEP_STUBS);
        when(alt.getId()).thenReturn(2);
        when(alt.getName()).thenReturn(name);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(owner);
        return alt;
    }

    /** The fail-before case: switched off, an alt is refused exactly as 1.6.0.4 refused it. */
    @Test
    void offByDefaultTheOwnerInviteIsStillRefused() {
        Character owner = owner(1);
        Character alt = alt("Alt2", owner);
        Client c = mock(Client.class, RETURNS_DEEP_STUBS);
        when(c.getPlayer()).thenReturn(owner);
        when(c.getChannelServer().getPlayerStorage().getCharacterByName("Alt2")).thenReturn(alt);
        assertFalse(OwnedAltInvite.acceptsOwnerInvite(owner, alt));

        try (MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class);
             MockedStatic<GuildOperationHandler> joins = mockStatic(GuildOperationHandler.class)) {
            Guild.sendInvitation(c, "Alt2");
            verify(owner).yellowMessage("Alt2 is your alt and can't answer a guild invitation while it travels with you."
                    + " Use @dismiss Alt2 and log in on it.");
            joins.verifyNoInteractions();
            invites.verifyNoInteractions();
        }
    }

    /** The after case, guild: the alt joins through the accept packet's own body. */
    @Test
    void anAltAnswersItsOwnOwnersGuildInvite() {
        System.setProperty(OwnedAltInvite.OWNER_INVITE_SWITCH, "true");
        Character owner = owner(1);
        when(owner.getGuildId()).thenReturn(7);
        Character alt = alt("Alt2", owner);
        Client c = mock(Client.class, RETURNS_DEEP_STUBS);
        when(c.getPlayer()).thenReturn(owner);
        when(c.getChannelServer().getPlayerStorage().getCharacterByName("Alt2")).thenReturn(alt);
        assertTrue(OwnedAltInvite.acceptsOwnerInvite(owner, alt));

        try (MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class);
             MockedStatic<GuildOperationHandler> joins = mockStatic(GuildOperationHandler.class)) {
            invites.when(() -> InviteCoordinator.createInvite(any(), any(), any(), anyInt())).thenReturn(true);
            Guild.sendInvitation(c, "Alt2");
            joins.verify(() -> GuildOperationHandler.joinAfterInvite(alt, 7));
            verify(owner, never()).yellowMessage(anyString());
        }
    }

    /** The after case, family: the invite reaches the alt as a packet only when nobody answers for it. */
    @Test
    void anAltAnswersItsOwnOwnersFamilyInvite() {
        System.setProperty(OwnedAltInvite.OWNER_INVITE_SWITCH, "true");
        Character owner = owner(1);
        when(owner.getLevel()).thenReturn(60);
        Character alt = alt("Alt2", owner);
        when(alt.getLevel()).thenReturn(50);
        MapleMap map = mock(MapleMap.class);
        when(owner.getMap()).thenReturn(map);
        when(alt.getMap()).thenReturn(map);
        assertTrue(OwnedAltInvite.acceptsOwnerInvite(owner, alt));

        try (MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class);
             MockedStatic<AcceptFamilyHandler> answers = mockStatic(AcceptFamilyHandler.class)) {
            invites.when(() -> InviteCoordinator.createInvite(any(), any(), any(), anyInt())).thenReturn(true);
            invites.when(() -> InviteCoordinator.hasInvite(any(), anyInt())).thenReturn(false);
            new FamilyAddHandler().handlePacket(request("Alt2"), client(owner, alt));
            answers.verify(() -> AcceptFamilyHandler.answer(alt, owner, true));
        }
    }

    /** Off, the family invite is still the packet that fizzles on a headless client. */
    @Test
    void offByDefaultTheFamilyInviteIsStillSentToAHeadlessClient() {
        Character owner = owner(1);
        when(owner.getLevel()).thenReturn(60);
        Character alt = alt("Alt2", owner);
        when(alt.getLevel()).thenReturn(50);
        MapleMap map = mock(MapleMap.class);
        when(owner.getMap()).thenReturn(map);
        when(alt.getMap()).thenReturn(map);

        try (MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class);
             MockedStatic<AcceptFamilyHandler> answers = mockStatic(AcceptFamilyHandler.class)) {
            invites.when(() -> InviteCoordinator.createInvite(any(), any(), any(), anyInt())).thenReturn(true);
            invites.when(() -> InviteCoordinator.hasInvite(any(), anyInt())).thenReturn(false);
            new FamilyAddHandler().handlePacket(request("Alt2"), client(owner, alt));
            answers.verifyNoInteractions();
            verify(alt.getClient()).sendPacket(any());
        }
    }

    /** Every other inviter is still refused: a population bot, another player's alt, a different owner's alt. */
    @Test
    void everyOtherInviterIsStillRefused() {
        System.setProperty(OwnedAltInvite.OWNER_INVITE_SWITCH, "true");
        Character owner = owner(1);
        Character alt = alt("Alt2", owner);

        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(3);
        when(bot.isBot()).thenReturn(true);
        assertFalse(OwnedAltInvite.acceptsOwnerInvite(bot, alt), "a population bot does not answer for an alt");

        Character otherAlt = mock(Character.class, RETURNS_DEEP_STUBS);
        when(otherAlt.getId()).thenReturn(4);
        when(otherAlt.isOwnedAlt()).thenReturn(true);
        assertFalse(OwnedAltInvite.acceptsOwnerInvite(otherAlt, alt), "an alt does not invite its sibling");

        Character stranger = owner(5);
        Character theirAlt = alt("Alt9", stranger);
        assertFalse(OwnedAltInvite.acceptsOwnerInvite(owner, theirAlt), "another player's alt is not ours");
        assertFalse(OwnedAltInvite.acceptsOwnerInvite(owner, null));
        assertFalse(OwnedAltInvite.acceptsOwnerInvite(null, alt));
    }

    private static Client client(Character owner, Character alt) {
        Client c = mock(Client.class, RETURNS_DEEP_STUBS);
        when(c.getPlayer()).thenReturn(owner);
        when(c.getChannelServer().getPlayerStorage().getCharacterByName("Alt2")).thenReturn(alt);
        return c;
    }

    private static InPacket request(String name) {
        InPacket p = mock(InPacket.class);
        when(p.readString()).thenReturn(name);
        return p;
    }
}
