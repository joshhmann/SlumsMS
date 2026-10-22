package soloMapling.ArtificialPlayer.BotCombatSystem;

import config.ServerConfig;
import config.YamlConfig;

/**
 * 1.5.0 rc3 "bots fight like people": the switches and numbers of the human combat layer, from config.yaml
 * ({@code BOT_COMBAT_*}, {@code BOT_KS_MANNERS}, {@code BOT_SPREAD}). A test or a GM command may override a value
 * ({@link #override}); {@link #reset} goes back to the file. Read every time (cheap), so a config reload or a GM
 * toggle applies at the next tick.
 */
public final class BotCombatTuning {

    private BotCombatTuning() {
    }

    private static volatile Boolean human;
    private static volatile Integer aoeMinMobs;
    private static volatile Double sloppiness;
    private static volatile Boolean deaths;
    private static volatile Boolean ksManners;
    private static volatile Boolean spread;

    private static ServerConfig server() {
        try {
            YamlConfig c = YamlConfig.config;
            return c != null ? c.server : null;
        } catch (Throwable noConfig) {
            return null; // a unit test without config.yaml: the defaults
        }
    }

    public static boolean human() {
        Boolean o = human;
        if (o != null) {
            return o;
        }
        ServerConfig s = server();
        return s == null || s.BOT_COMBAT_HUMAN;
    }

    public static int aoeMinMobs() {
        Integer o = aoeMinMobs;
        if (o != null) {
            return o;
        }
        ServerConfig s = server();
        return s == null ? 3 : Math.max(1, s.BOT_COMBAT_AOE_MIN_MOBS);
    }

    public static double sloppiness() {
        Double o = sloppiness;
        if (o != null) {
            return o;
        }
        ServerConfig s = server();
        return s == null ? 1.0 : Math.max(0, s.BOT_COMBAT_SLOPPINESS);
    }

    public static boolean deaths() {
        Boolean o = deaths;
        if (o != null) {
            return o;
        }
        ServerConfig s = server();
        return human() && (s == null || s.BOT_COMBAT_DEATHS);
    }

    public static boolean ksManners() {
        Boolean o = ksManners;
        if (o != null) {
            return o;
        }
        ServerConfig s = server();
        return s == null || s.BOT_KS_MANNERS;
    }

    public static boolean spread() {
        Boolean o = spread;
        if (o != null) {
            return o;
        }
        ServerConfig s = server();
        return s == null || s.BOT_SPREAD;
    }

    /**
     * A GM or test override: {@code key} human | aoe | sloppy | deaths | manners | spread; {@code value} null clears
     * it. Returns false for an unknown key or a value that does not parse.
     */
    public static boolean override(String key, String value) {
        try {
            switch (key.toLowerCase(java.util.Locale.ROOT)) {
                case "human" -> human = value == null ? null : Boolean.parseBoolean(value);
                case "aoe" -> aoeMinMobs = value == null ? null : Math.max(1, Integer.parseInt(value));
                case "sloppy" -> sloppiness = value == null ? null : Math.max(0, Double.parseDouble(value));
                case "deaths" -> deaths = value == null ? null : Boolean.parseBoolean(value);
                case "manners" -> ksManners = value == null ? null : Boolean.parseBoolean(value);
                case "spread" -> spread = value == null ? null : Boolean.parseBoolean(value);
                default -> {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Clears every override. */
    public static void reset() {
        human = null;
        aoeMinMobs = null;
        sloppiness = null;
        deaths = null;
        ksManners = null;
        spread = null;
    }

    /** One line for {@code !botcombat}. */
    public static String describe() {
        return "human " + human() + ", aoe at " + aoeMinMobs() + "+ mobs, sloppiness x" + sloppiness() + ", deaths "
                + deaths() + ", ks manners " + ksManners() + ", spread " + spread();
    }
}
