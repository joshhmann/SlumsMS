package soloMapling.ArtificialPlayer.BotProgressionSystem;

import server.expeditions.ExpeditionBossLog.BossLogEntry;
import server.expeditions.ExpeditionType;

/**
 * 1.6.1 ticket 30 (promise audit): where the rebirth keys' ingredients really come from, read off the player scripts.
 *
 * <p>craft.js makes each key from two items. Five of the six come from a boss clear: after the boss dies, Plunk
 * (NPC 3004006, {@code scripts/npc/3004006.js}) hands every member in the room its item (Zakum 4032133, Horntail
 * 4001094, Von Leon 4001693, Cygnus 4000659) and the Pink Bean exit NPC (2141002) hands each member 4021010. The
 * boss's {@code drop_data} rows may drop the same item on the floor too (stock Cosmic: Zakum 42%, Horntail ~100%,
 * Pink Bean 4%); a bot loots those as any drop. The sixth, the Cygnus Knight Emblem (4032922), is no boss item: the
 * stronghold guards drop it (quest 31125) and Chief Alex gives a new one for quest 31126 (20 each of four Mutant
 * mobs, level 165).
 *
 * <p>The players' rules per boss are the event scripts' and {@link ExpeditionType}/{@link BossLogEntry}: Zakum
 * level 50, 6-30 members, 25 clears a day; Horntail level 100, 6-30, 20 a day; Pink Bean level 120, 6-30, 20 a day;
 * Von Leon level 120, 1-6, 18 a day; Cygnus level 120, 1-6, 16 a day (clears are logged in {@code bosslog_daily}
 * when the boss dies, {@code EventInstanceManager.logBossClear}). Time limits: 120 minutes, Pink Bean 140.
 *
 * <p>{@link Boss#botsRunIt}: Von Leon and Cygnus are one room, summoned by the room's NPC, and runnable by 1-6: bots
 * form those runs themselves ({@link RebirthBossRuns}). Zakum (the Eye of Fire on the altar, eight arms first),
 * Horntail (two head rooms, then the body's parts) and Pink Bean (the statue stage and its Jr. bosses) need a
 * player's hand on their stages (ticket 130's mechanics work, 1.7.0): bots join a real player's expedition for them
 * on screen, and otherwise run them off screen ({@link OffscreenBossRun}) on the same rules and drop rows.
 */
public final class RebirthBosses {

    private RebirthBosses() {
    }

    /** The Cygnus Knight Emblem: the stronghold's pass, the 3rd key's second ingredient. */
    public static final int CYGNUS_EMBLEM = 4032922;
    /** Quest 31126 "Lost Emblem": 20 each of these for Chief Alex's new emblem (Quest.wz Check 31126). */
    public static final int[] EMBLEM_QUEST_MOBS = {8600000, 8600001, 8600002, 8600003};
    public static final int EMBLEM_QUEST_KILLS = 20 * 4;
    public static final int EMBLEM_QUEST_LEVEL = 165;
    /** Future Ereve, the way into the Cygnus Knights' stronghold. */
    static final int FUTURE_EREVE = 271000000;
    /** The room's leftover placeholder the summon NPCs kill (2161008.js, 3003903.js). */
    static final int SUMMON_PLACEHOLDER_MOB = 8840010;

    public enum Boss {
        ZAKUM(ExpeditionType.ZAKUM, "ZakumBattle", 211042400, 280030000, 211042400, 4032133, 120, false,
                0, 0, 0, 0, 8800002, range(8800000, 8800010)),
        HORNTAIL(ExpeditionType.HORNTAIL, "HorntailBattle", 240050400, 240060200, 240050600, 4001094, 120, false,
                0, 0, 0, 0, 8810018, range(8810000, 8810009)),
        PINKBEAN(ExpeditionType.PINKBEAN, "PinkBeanBattle", 270050000, 270050100, 270050300, 4021010, 140, false,
                0, 0, 0, 0, 8820001, range(8820001, 8820006)),
        VONLEON(ExpeditionType.VONLEON, "VonLeonBattle", 211070000, 211070100, 211070000, 4001693, 120, true,
                8840000, 49, -181, 2161008, 8847777, new int[]{8840000}),
        CYGNUS(ExpeditionType.CYGNUS, "CygnusBattle", 271040000, 271040100, 271040000, 4000659, 120, true,
                8850011, -104, 115, 3003903, 8850012, new int[]{8850011, 8850012, 8850000, 8850001, 8850002,
                8850003, 8850004});

        public final ExpeditionType type;
        /** The event script (EventInstanceManager.scriptName). */
        public final String script;
        public final int recruitMap;
        /** The room the boss dies in (where Plunk hands out the clear reward). */
        public final int roomMap;
        public final int exitMap;
        /** The item every member is handed after the clear (Plunk / the Pink Bean exit NPC). */
        public final int clearItem;
        public final int timeLimitMinutes;
        /** One room, summoned by its NPC: bots run it themselves. */
        public final boolean botsRunIt;
        public final int summonMob;
        public final int summonX;
        public final int summonY;
        public final int summonNpc;
        /** The mob whose death clears the event (the script's isZakum / isHorntail / ...). */
        public final int clearMob;
        /** Every mob a run must chew through (its HP is the run's work). */
        private final int[] fightMobs;

        Boss(ExpeditionType type, String script, int recruitMap, int roomMap, int exitMap, int clearItem,
             int timeLimitMinutes, boolean botsRunIt, int summonMob, int summonX, int summonY, int summonNpc,
             int clearMob, int[] fightMobs) {
            this.type = type;
            this.script = script;
            this.recruitMap = recruitMap;
            this.roomMap = roomMap;
            this.exitMap = exitMap;
            this.clearItem = clearItem;
            this.timeLimitMinutes = timeLimitMinutes;
            this.botsRunIt = botsRunIt;
            this.summonMob = summonMob;
            this.summonX = summonX;
            this.summonY = summonY;
            this.summonNpc = summonNpc;
            this.clearMob = clearMob;
            this.fightMobs = fightMobs;
        }

        public int[] fightMobs() {
            return fightMobs.clone();
        }

        /**
         * The map whose entry rules (BotMapGates: rebirths, level) say whether a bot may go there. Cygnus' recruiter
         * stands in the stronghold, which no Hyper Teleport Rock reaches (HyperTPHandler.isRestrictedMap): a player
         * walks in from Future Ereve through the door that takes the emblem, so Future Ereve's rules and the emblem.
         */
        public int gateMap() {
            return this == CYGNUS ? FUTURE_EREVE : recruitMap;
        }

        /** The players' level floor (ExpeditionType). */
        public int minLevel() {
            return type.getMinLevel();
        }

        /** The daily clear limit a player has (null: none). */
        public BossLogEntry logEntry() {
            return ExpeditionType.typeToEntry(type);
        }

        /** A pickup group of regulars: the event's six (Zakum/Horntail/Pink Bean's minimum, Von Leon/Cygnus' max). */
        public int partySize() {
            return 6;
        }

        public static Boss byScript(String script) {
            if (script == null) return null;
            for (Boss b : values()) if (b.script.equals(script)) return b;
            return null;
        }

        public static Boss byType(ExpeditionType type) {
            for (Boss b : values()) if (b.type == type) return b;
            return null;
        }

        /** The boss whose clear hands out this item, or null (the emblem, or no rebirth item). */
        public static Boss byItem(int itemId) {
            for (Boss b : values()) if (b.clearItem == itemId) return b;
            return null;
        }
    }

    private static int[] range(int from, int to) {
        int[] r = new int[to - from + 1];
        for (int i = 0; i < r.length; i++) r[i] = from + i;
        return r;
    }
}
