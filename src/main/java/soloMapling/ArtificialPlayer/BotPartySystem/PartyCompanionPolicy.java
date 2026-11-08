package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;

/**
 * 1.6.7 alt overhaul (owner, 8 Nov 2026: "if we get this working super well, then regulars partying can be the
 * same"). Every boss-ready feature is built once, in the party-companion layer that owned alts and party/follower
 * regulars share, and this policy says who it is on for. Owned alts are ON by default and party regulars OFF, so 1.7.1
 * turns a feature on for regulars with a switch and tests, not a rewrite.
 *
 * <p>Switches are {@code -D} properties, read on use: {@code -Dslums.companion.<key>.alts} (default true) and
 * {@code -Dslums.companion.<key>.regulars} (default false). They are {@code -D} properties and not config keys on
 * purpose: an unknown {@code config.yaml} key stops a server booting.
 */
public final class PartyCompanionPolicy {

    private PartyCompanionPolicy() {
    }

    /** One boss-ready feature of the shared layer. */
    public enum Feature {
        /** companion-shared-picker: in-run targets through OwnedAltCombat.target (assist, kill order). */
        SHARED_PICKER("picker"),
        /** alt-supplies-in-boss-fights: autopot thresholds, the out-of-pots warning and the pre-run check. */
        SUPPLIES("supplies"),
        /** alt-revive-respawn (death rules): rest in town after a boss death, then rejoin; sit a fight out. */
        DEATH_RULES("deathrules"),
        /** alt-qol-batch A: chat reports (pots/stats/slots), "did you mean", low-pots note, bag-full, confirm-drop. */
        CHAT_REPORTS("reports"),
        /** alt-qol-batch B: chat toggles (heals/support), per-owner settings that survive relog, the card window. */
        SETTINGS("settings"),
        /** alt-qol-batch C: anti-stuck, reserved spot at portals, rope/ladder following, arrival/welcome-back lines. */
        FOLLOW_PLUS("follow"),
        /** alt-qol-batch D: per-alt loot mode, sell junk with a protection list, upgrade offer. */
        LOOT("loot"),
        /** alt-qol-batch E: quest mirror (party quests the owner takes progress for the alt too). */
        QUEST_MIRROR("quests");

        private final String key;

        Feature(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** The {@code -D} property for owned alts or for party regulars. */
        public String property(boolean alts) {
            return "slums.companion." + key + (alts ? ".alts" : ".regulars");
        }
    }

    /** Whether {@code feature} is on for this companion: an owned alt, else any other bot (a party regular). */
    public static boolean on(Feature feature, Character companion) {
        if (feature == null || companion == null || !BotHelpers.isBot(companion)) {
            return false; // a real player is never driven by the companion layer
        }
        return companion.isOwnedAlt() ? forAlts(feature) : forRegulars(feature);
    }

    public static boolean forAlts(Feature feature) {
        return read(feature.property(true), true);
    }

    public static boolean forRegulars(Feature feature) {
        return read(feature.property(false), false);
    }

    private static boolean read(String property, boolean dflt) {
        try {
            String v = System.getProperty(property);
            return v == null ? dflt : Boolean.parseBoolean(v.trim());
        } catch (RuntimeException | LinkageError e) {
            return dflt;
        }
    }
}
