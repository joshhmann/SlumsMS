package soloMapling.companions;

import client.Character;
import net.server.channel.handlers.AcceptFamilyHandler;
import net.server.channel.handlers.GuildOperationHandler;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionSwitches;
import tools.PacketCreator;

import java.util.function.Consumer;

/**
 * 1.6.2 ticket 99: a party invite naming one of your own offline characters calls it as an alt, as {@code @botme
 * <name>} does - after nutnnut/Cosmic 59f9bc091 ("Bot spawn shortcut via party invite and buddy list"). The party
 * window's invite sends the name as typed, so it works from the client as it is. The buddy list's "invite to party"
 * only shows for buddies the client sees online, and an offline alt shows offline: upstream marks its bots online in
 * the buddy list, which we do not do (it would make whispers and /find lie). Off unless
 * OWNED_ALTS_INVITE_SUMMON_ENABLED.
 */
public final class OwnedAltInvite {

    private OwnedAltInvite() {
    }

    /**
     * The party invite found no one online named {@code name}. True when it was taken as a summon (the answer comes
     * later, off the packet thread); false leaves the normal "cannot find" answer to the caller.
     */
    public static boolean onInviteNotFound(Character inviter, String name) {
        return onInviteNotFound(inviter, name, OwnedAltService.getInstance(),
                notFound -> inviter.sendPacket(PacketCreator.partyStatusMessage(19)));
    }

    static boolean onInviteNotFound(Character inviter, String name, OwnedAltService alts, Consumer<Character> notFound) {
        if (!CompanionSwitches.inviteSummon() || inviter == null || name == null || name.isBlank()
                || inviter.isOwnedAlt() || inviter.isBot() || !alts.summoningOpen()) {
            return false;
        }
        alts.runAsync(() -> {
            try {
                boolean mine = alts.list(inviter).stream().anyMatch(cand -> cand.name().equalsIgnoreCase(name));
                if (!mine) {
                    notFound.accept(inviter);
                    return;
                }
                Character alt = alts.summon(inviter, name);
                inviter.yellowMessage(alt.getName() + " has joined you and follows you. Changing channel sends your alts home.");
            } catch (IllegalStateException e) {
                inviter.yellowMessage(e.getMessage());
            }
        });
        return true;
    }

    /**
     * e02: {@code -Dslums.ownedalts.acceptownerinvite=true}, off unless a server starts with it - the same shape as
     * {@code -Dslums.alts.lanes} (a {@code -D} property and not a config key: an unknown {@code config.yaml} key stops
     * a server booting). Switched on, one class of invite stops waiting for a human: family and guild invites from a
     * character to its own summoned alt are answered by the alt.
     */
    public static final String OWNER_INVITE_SWITCH = "slums.ownedalts.acceptownerinvite";

    public static boolean ownerInvitesAccepted() {
        try {
            return Boolean.getBoolean(OWNER_INVITE_SWITCH);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * e02: true when {@code target} is one of {@code inviter}'s own alts and {@link #OWNER_INVITE_SWITCH} is on - the
     * only case where a headless character answers an invite itself. Every population bot, every other player's alt
     * and an alt whose owner is not the inviter stay refused, exactly as {@link OwnedAltService#interactionRefusal}
     * says.
     */
    public static boolean acceptsOwnerInvite(Character inviter, Character target) {
        if (!ownerInvitesAccepted() || inviter == null || target == null) {
            return false;
        }
        if (!target.isOwnedAlt() || inviter.isOwnedAlt() || inviter.isBot()) {
            return false;
        }
        Character owner = target.getOwnedAltOwner();
        return owner != null && owner.getId() == inviter.getId();
    }

    /**
     * e02: the alt answers {@code owner}'s "be my junior" invite the way its client would have. The family rules above
     * the invite (same map, level, generations) are the same ones a human invite meets; the answer runs on this thread
     * as the accept packet's body does.
     */
    public static void acceptFamily(Character alt, Character owner) {
        AcceptFamilyHandler.answer(alt, owner, true);
    }

    /**
     * e02: the alt answers {@code owner}'s guild invite and joins through the accept packet's own body, so the member
     * row, the guild packets and the bot guildmates' welcome are the ones a human accept produces.
     */
    public static boolean acceptGuild(Character alt, Character owner) {
        if (owner == null || owner.getGuildId() <= 0) {
            return false;
        }
        return GuildOperationHandler.joinAfterInvite(alt, owner.getGuildId());
    }
}
