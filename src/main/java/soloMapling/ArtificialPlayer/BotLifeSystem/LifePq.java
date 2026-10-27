package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 1.6.0 S8: a bot chooses a party quest by itself. It goes to the PQ's lobby when its level fits the PQ's band and it
 * is PQ hours, and stands there looking for a party ("lf kpq"), as 2009 players did; a real player (or a bot leader)
 * can invite it. It never enters the PQ alone: the instance needs a party, which the existing invite path forms.
 */
public final class LifePq {
    private LifePq() {}

    public record Lobby(String name, int minLevel, int maxLevel, int mapId) {
        /** How players say it in full ("Kerning PQ"); the LFG thread's label (1.6.0: LFG and LifePq agree). */
        public String label() {
            return switch (name) {
                case "kpq" -> "Kerning PQ";
                case "lpq" -> "Ludi PQ";
                case "opq" -> "Orbis PQ";
                default -> name.toUpperCase(java.util.Locale.ROOT);
            };
        }
    }

    /** v83 party quests with a town lobby (Kerning City, Eos Tower 101st floor, Orbis tower lobby). */
    public static final List<Lobby> LOBBIES = List.of(
            new Lobby("kpq", 21, 30, 103000000),
            new Lobby("lpq", 35, 50, 221024500),
            new Lobby("opq", 51, 70, 200080101));

    /** The lobby whose band this level fits, or null. */
    public static Lobby lobbyFor(int level) {
        for (Lobby lobby : LOBBIES) {
            if (level >= lobby.minLevel() && level <= lobby.maxLevel()) return lobby;
        }
        return null;
    }

    /** The lobby an LFG label names ("Ludi PQ"), or null. */
    public static Lobby byLabel(String label) {
        for (Lobby lobby : LOBBIES) {
            if (lobby.label().equalsIgnoreCase(label)) return lobby;
        }
        return null;
    }

    /** PQ hours, server-local (the roster's time zone): evenings 16:00-24:00, weekends from 10:00. */
    public static boolean pqHours(ZonedDateTime local) {
        int hour = local.getHour();
        boolean weekend = local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY;
        return hour >= 16 || weekend && hour >= 10;
    }

    /** Whether the PQ hours are on now; false when the clock can't be read (tests without config). */
    public static boolean pqHoursNow() {
        if (Boolean.getBoolean("slums.botlife.pqAlways")) return true; // TEST/QA: any hour
        try {
            return pqHours(soloMapling.ArtificialPlayer.BotPopulationDirector.localNow());
        } catch (RuntimeException noConfig) {
            return false;
        }
    }
}
