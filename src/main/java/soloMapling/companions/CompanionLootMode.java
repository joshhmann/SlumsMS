package soloMapling.companions;

// Ported from https://github.com/Incluwin/BotStory.git
//   src/main/java/server/agents/capabilities/looting/AgentLootPolicy.java @ 81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364
// changed: their Mode/Policy record is one small enum read through our per-companion settings store instead of a
// host-installed Rule; their "the policy only narrows what the loot rules allow" contract is kept by asking this
// AFTER the drop is already ours (OwnedAltLoot.mayTake, GrindLoot.botMayTake); quest needs come from our own
// Character.needQuestItem (the drop's own quest id) rather than a host QuestNeeds callback; LIKE_OWNER reads our
// GrindLoot.isJunk (the etc junk a junk-skipping bot leaves) because this codebase stores no per-owner loot habits;
// NONE picks up nothing at all, which is what the owner asked for in the card.

import client.Character;
import server.maps.MapItem;
import server.life.Monster;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;

import java.util.Locale;

/**
 * 1.6.7 alt-qol-batch group D: what a companion picks up of the drops the loot rules already allow it.
 * {@link PartyCompanionPolicy.Feature#LOOT} says who this is on for (owned alts on, party regulars off).
 *
 * <p>An owned alt with no saved choice runs on {@link #MY_KILLS} (owner decision 8 Nov 2026, "own-kills default
 * OK"); a party regular with the switch on and no saved choice keeps today's behaviour ({@link #EVERYTHING}), so
 * turning the switch on for regulars never narrows anything until an owner chooses. With the switch off for a
 * companion this answers "everything" and nothing changes at all.
 *
 * <p>A mode only ever narrows: it is asked after the drop has passed the ownership, party and settle rules.
 */
public enum CompanionLootMode {

    /** Every drop the loot rules allow: today's behaviour. */
    EVERYTHING("everything"),
    /** Only drops of a mob it hit (or that were credited to it), plus mesos and the quest items it needs. */
    MY_KILLS("my kills"),
    /** Only mesos and the quest items it needs. */
    QUEST_ONLY("quest only"),
    /** Everything but the etc junk a junk-skipping bot leaves, plus mesos and quest items. */
    LIKE_YOU("like you"),
    /** Nothing at all, not even a meso. */
    NOTHING("nothing");

    private final String stored;

    CompanionLootMode(String stored) {
        this.stored = stored;
    }

    /** The name {@link CompanionSettings#lootMode()} holds. */
    public String stored() {
        return stored;
    }

    /** The mode a stored name means, or null for "" (no choice) and for a name this branch does not know. */
    public static CompanionLootMode parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String name = stored.trim().toLowerCase(Locale.ROOT);
        for (CompanionLootMode mode : values()) {
            if (mode.stored.equals(name) || mode.name().toLowerCase(Locale.ROOT).equals(name)) {
                return mode;
            }
        }
        return null;
    }

    /** The mode in force for this companion, resolving "no saved choice" (see the class comment). */
    public static CompanionLootMode of(Character companion) {
        if (!PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.LOOT, companion)) {
            return EVERYTHING; // the switch is off for it: the loot rules alone decide, exactly as before
        }
        CompanionLootMode chosen = parse(CompanionSettingsStore.stored(companion).lootMode());
        if (chosen != null) {
            return chosen;
        }
        return companion.isOwnedAlt() ? MY_KILLS : EVERYTHING;
    }

    /**
     * Whether this companion picks up {@code drop}, which the loot rules already allow it: the mode in force for it
     * ({@link #of}) says so.
     */
    public static boolean allows(Character companion, MapItem drop) {
        if (companion == null || drop == null) {
            return false;
        }
        return of(companion).permits(companion, drop);
    }

    /** This mode's own answer for a drop the loot rules already allow (BotStory's {@code permits}). */
    public boolean permits(Character companion, MapItem drop) {
        if (this == EVERYTHING) {
            return true;
        }
        if (this == NOTHING) {
            return false;
        }
        // meso and the quest items its own active quests still need are taken in every remaining mode
        if (drop.getMeso() > 0) {
            return true;
        }
        if (drop.getItem() != null && drop.getQuest() > 0
                && companion.needQuestItem(drop.getQuest(), drop.getItem().getItemId())) {
            return true;
        }
        return switch (this) {
            case MY_KILLS -> itsOwnKill(companion, drop);
            case QUEST_ONLY -> false;
            case LIKE_YOU -> drop.getItem() != null && !junk(drop);
            default -> true;
        };
    }

    /**
     * BotStory {@code OWN_KILLS}: the drop is credited to the companion, or it came from a mob that is dead and
     * whose damage record lists the companion ({@code Monster.isAttackedBy}). A living mob's drop (a pickpocket's) is
     * no kill of anybody's.
     */
    static boolean itsOwnKill(Character companion, MapItem drop) {
        if (drop.getOwnerId() == companion.getId()) {
            return true;
        }
        if (!(drop.getDropper() instanceof Monster mob)) {
            return false;
        }
        return !mob.isAlive() && mob.isAttackedBy(companion);
    }

    /**
     * The etc junk a junk-skipping bot leaves, and what {@link #LIKE_YOU} leaves: an etc drop that is no quest's.
     * {@code GrindLoot.isJunk} is this rule (it delegates here), so a bot's own habit and this mode never disagree.
     */
    public static boolean junk(MapItem drop) {
        if (drop == null || drop.getMeso() > 0 || drop.getItem() == null || drop.getQuest() > 0) {
            return false;
        }
        return constants.inventory.ItemConstants.getInventoryType(drop.getItem().getItemId())
                == client.inventory.InventoryType.ETC;
    }

    /** The mode one step on, for the card's one clickable line. */
    public CompanionLootMode next() {
        return switch (this) {
            case EVERYTHING -> MY_KILLS;
            case MY_KILLS -> QUEST_ONLY;
            case QUEST_ONLY -> LIKE_YOU;
            case LIKE_YOU -> NOTHING;
            case NOTHING -> EVERYTHING;
        };
    }

    /** How the card and a chat answer name it. */
    public String said() {
        return switch (this) {
            case EVERYTHING -> "loot everything";
            case MY_KILLS -> "loot my kills";
            case QUEST_ONLY -> "loot quest items";
            case LIKE_YOU -> "loot like you";
            case NOTHING -> "loot nothing";
        };
    }
}
