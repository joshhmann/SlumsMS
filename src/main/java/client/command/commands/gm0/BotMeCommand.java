package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionBossRevive;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.companions.OwnedAltService;
import soloMapling.server.SoloMaplingUtilities;

public class BotMeCommand extends Command {
    { setDescription("Call one of your other characters: @botme <name>, @botme to list them, @botme here to bring them to you, @botme revive <name> to get a downed one up now."); }
    @Override public void execute(Client c, String[] params) {
        Character owner = c.getPlayer();
        OwnedAltService alts = OwnedAltService.getInstance();
        if (params.length == 2 && params[0].equalsIgnoreCase("revive")) {
            // 1.6.7 alt-revive-respawn: buy a downed companion in a boss run its revive now; the price is paid from
            // the mesos of the player typing this, and nothing is charged when the companion is not down in a run.
            String name = params[1];
            alts.runAsync(() -> {
                try {
                    Character target = downedNear(owner, name);
                    if (target == null) {
                        owner.yellowMessage("There is nobody called " + name + " with you right now.");
                        return;
                    }
                    CompanionBossRevive.Outcome outcome = CompanionBossRevive.reviveNow(owner, target, System.currentTimeMillis());
                    owner.yellowMessage(outcome.told());
                } catch (IllegalStateException e) { owner.yellowMessage(e.getMessage()); }
            });
            return;
        }
        if (params.length > 1) {
            owner.yellowMessage("Usage: @botme <name> | @botme here | @botme | @botme revive <name>");
            return;
        }
        if (!alts.summoningOpen()) { // 1.6.0.4: summoning switched off (OWNED_ALTS_SUMMON_ENABLED); @dismiss still works
            owner.yellowMessage(OwnedAltService.RESTING);
            return;
        }
        // 1.6.0.4 (M1): listing, loading and placing read the database; off the packet thread, reply when done.
        alts.runAsync(() -> {
            try {
                if (params.length == 0) {
                    var eligible = alts.list(owner);
                    owner.yellowMessage(eligible.isEmpty() ? "No eligible offline characters on this account and world."
                            : "Available characters: " + String.join(", ", eligible.stream().map(OwnedAltService.Candidate::name).toList())
                            + ". Use @botme <name> to call one.");
                } else if (params[0].equalsIgnoreCase("here")) {
                    var moved = alts.bringHere(owner);
                    if (!moved.isEmpty()) owner.yellowMessage(String.join(", ", moved) + (moved.size() == 1 ? " is" : " are") + " here with you.");
                } else {
                    boolean hadParty = owner.getParty() != null;
                    var alt = alts.summon(owner, params[0]);
                    owner.yellowMessage(alt.getName() + " has joined you and follows you."
                            + (hadParty ? "" : " A party was created for you.")
                            + " Changing channel sends your alts home.");
                }
            } catch (IllegalStateException e) { owner.yellowMessage(e.getMessage()); }
        });
    }

    /**
     * The companion this player may buy a revive for, by name: one of their own active alts, or a regular following
     * this player by party (the leader pays for its regulars).
     */
    private static Character downedNear(Character owner, String name) {
        for (OwnedAltService.Active active : OwnedAltService.getInstance().active()) {
            if (active.alt() != null && active.owner() != null && active.owner().getId() == owner.getId()
                    && active.alt().getName() != null && active.alt().getName().equalsIgnoreCase(name)) {
                return active.alt();
            }
        }
        var world = SoloMaplingUtilities.world();
        Character other = world == null ? null : world.getPlayerStorage().getCharacterByName(name);
        if (other == null || other.getId() == owner.getId() || !BotHelpers.isBot(other) || other.isOwnedAlt()) {
            return null;
        }
        BotSM sm = CharacterStorage.getBotById(other.getId());
        return sm instanceof FollowerBot follower && follower.leaderId() == owner.getId() ? other : null;
    }
}
