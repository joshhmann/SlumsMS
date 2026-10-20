package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Real bots 08: the hook a bot's town stop calls with what it needs. Ticket 09 (buying at NPC shops with the bot's
 * own mesos) installs the real one; until then nothing is bought: the default declines, and the bot keeps what it has
 * (it rests to full in town either way, and a bot without ammo fights in melee - see BotSupplies).
 *
 * A resupplier runs on the bot's macro tick while it stands in town. It must account for everything through
 * BotHoldings (spendMeso, grantItem) so the ledger sees the mesos go and the items come.
 */
public final class BotResupply {

    private BotResupply() {}

    private static final Logger log = LoggerFactory.getLogger(BotResupply.class);

    @FunctionalInterface
    public interface Resupplier {
        /* Restocks the bot from what it needs; true when it got something. */
        boolean resupply(Character bot, SupplyNeeds needs);
    }

    /* The pre-09 hook: buys nothing. */
    public static final Resupplier NONE = (bot, needs) -> false;

    private static volatile Resupplier handler = NONE;

    /* Ticket 09 installs its shop run here; null puts the default back. */
    public static void install(Resupplier resupplier) {
        handler = resupplier != null ? resupplier : NONE;
    }

    static Resupplier installed() {
        return handler;
    }

    /* Offers the bot's needs to the installed resupplier; false when it got nothing (or the resupplier failed). */
    public static boolean request(Character bot, SupplyNeeds needs) {
        try {
            boolean got = handler.resupply(bot, needs);
            if (log.isDebugEnabled()) {
                log.debug("Resupply: bot {} ({}) {} -> {}", bot.getId(), bot.getName(), needs, got ? "restocked" : "nothing");
            }
            return got;
        } catch (RuntimeException e) {
            log.warn("Resupply failed for bot {} ({})", bot.getId(), bot.getName(), e);
            return false;
        }
    }
}
