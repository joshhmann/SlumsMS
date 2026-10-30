package soloMapling.ArtificialPlayer.BotPartySystem;

import config.YamlConfig;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;

import java.util.EnumSet;
import java.util.Set;

/**
 * 1.6.2 ticket 99: the companion switches (ServerConfig, all off by default) and the commands they open. Read on
 * use, so a TEST config edit takes effect on the next order.
 */
public final class CompanionSwitches {

    private CompanionSwitches() {
    }

    public static boolean orders() {
        return read(Switch.ORDERS);
    }

    public static boolean trades() {
        return read(Switch.TRADES);
    }

    public static boolean upgrades() {
        return read(Switch.UPGRADES);
    }

    public static boolean supplies() {
        return read(Switch.SUPPLIES);
    }

    public static boolean inviteSummon() {
        return read(Switch.INVITE_SUMMON);
    }

    /** Any of the four companion command switches is on (owned alts then take party orders too). */
    public static boolean any() {
        return orders() || trades() || upgrades() || supplies();
    }

    /**
     * The commands a player may give now: the ticket 14 three always; movement and formations, item orders, gear
     * sharing and supply calls each with its switch, the item ones only when one of the companions can hand things
     * over ({@code canGive}: the player has an owned alt out).
     */
    public static Set<Verb> allowed(boolean canGive) {
        Set<Verb> out = EnumSet.copyOf(BotCommandParser.BASE);
        if (orders()) {
            out.addAll(BotCommandParser.MOVEMENT);
        }
        if (canGive && trades()) {
            out.addAll(BotCommandParser.ITEMS);
        }
        if (canGive && upgrades()) {
            out.addAll(BotCommandParser.UPGRADE);
        }
        if (canGive && supplies()) {
            out.addAll(BotCommandParser.SUPPLY);
        }
        return out;
    }

    private enum Switch { ORDERS, TRADES, UPGRADES, SUPPLIES, INVITE_SUMMON }

    private static boolean read(Switch s) {
        try {
            var c = YamlConfig.config.server;
            return switch (s) {
                case ORDERS -> c.COMPANION_ORDERS_ENABLED;
                case TRADES -> c.COMPANION_TRADES_ENABLED;
                case UPGRADES -> c.COMPANION_UPGRADES_ENABLED;
                case SUPPLIES -> c.COMPANION_SUPPLIES_ENABLED;
                case INVITE_SUMMON -> c.OWNED_ALTS_INVITE_SUMMON_ENABLED;
            };
        } catch (RuntimeException | LinkageError noConfig) {
            return false;
        }
    }
}
