package soloMapling.ArtificialPlayer.BotProgressionSystem;

import java.util.Arrays;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A bot's stored build (real-bots ticket 05): base stats, base HP/MP pools and current HP/MP, unspent AP and SP, the
 * level the build was last settled at, and its skills. Exactly what the {@code botstore} stat columns and the
 * {@code botskills} rows hold for one bot. A bot with no stored build has no {@code BotRealStats} at all.
 */
public record BotRealStats(int str, int dex, int int_, int luk, int maxHp, int maxMp, int hp, int mp, int remainingAp,
                           int[] sp, int buildLevel, SortedMap<Integer, SkillLevel> skills) {

    /** Skill books the server keeps unspent SP in ({@code AbstractCharacterObject.remainingSp}). */
    public static final int SKILL_BOOKS = 10;

    /** One stored skill: its level and master level (0 below 4th job). */
    public record SkillLevel(int level, int masterLevel) {
    }

    public BotRealStats {
        sp = Arrays.copyOf(sp == null ? new int[0] : sp, SKILL_BOOKS);
        skills = skills == null ? new TreeMap<>() : skills;
    }

    /** The same stats with this skill set (the store reads the two from separate tables). */
    public BotRealStats withSkills(SortedMap<Integer, SkillLevel> stored) {
        return new BotRealStats(str, dex, int_, luk, maxHp, maxMp, hp, mp, remainingAp, sp, buildLevel,
                new TreeMap<>(stored));
    }

    public SortedMap<Integer, SkillLevel> skillsView() {
        return Collections.unmodifiableSortedMap(skills);
    }

    /** The SP column's form, the same as {@code characters.sp}: one count per skill book, comma separated. */
    public String encodeSp() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < SKILL_BOOKS; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(sp[i]);
        }
        return out.toString();
    }

    /** Reads the SP column; a missing or malformed column is no unspent SP rather than a failed load. */
    public static int[] decodeSp(String column) {
        int[] books = new int[SKILL_BOOKS];
        if (column == null || column.isBlank()) {
            return books;
        }
        String[] parts = column.split(",");
        for (int i = 0; i < Math.min(parts.length, SKILL_BOOKS); i++) {
            try {
                books[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
            } catch (NumberFormatException e) {
                books[i] = 0;
            }
        }
        return books;
    }
}
