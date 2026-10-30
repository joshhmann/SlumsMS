package soloMapling.companions;

import client.Character;
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
}
