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

    /*
     * What a trip to town did about the bot's needs (train#F2): the whole kit, some of it (a budget or a bag that ran
     * out), or nothing at all - a bot with no mesos above its reserve, or a shop with nothing it can use. NOTHING is
     * what has to be told apart from a restock: walking back for more straight away is the town -> shop -> train ->
     * town loop (BotSupplies refuses the next trip for a while after one).
     */
    public enum RestockOutcome { BOUGHT, PARTIAL, NOTHING }

    @FunctionalInterface
    public interface Resupplier {
        /* Restocks the bot from what it needs: what the trip got done. */
        RestockOutcome resupply(Character bot, SupplyNeeds needs);
    }

    /* The pre-09 hook: buys nothing. */
    public static final Resupplier NONE = (bot, needs) -> RestockOutcome.NOTHING;

    private static volatile Resupplier handler = NONE;

    /* Ticket 09 installs its shop run here; null puts the default back. */
    public static void install(Resupplier resupplier) {
        handler = resupplier != null ? resupplier : NONE;
    }

    static Resupplier installed() {
        return handler;
    }

    /* Offers the bot's needs to the installed resupplier; NOTHING when it got nothing (or the resupplier failed). */
    public static RestockOutcome request(Character bot, SupplyNeeds needs) {
        try {
            RestockOutcome outcome = handler.resupply(bot, needs);
            if (log.isDebugEnabled()) {
                log.debug("Resupply: bot {} ({}) {} -> {}", bot.getId(), bot.getName(), needs,
                        outcome != null ? outcome : RestockOutcome.NOTHING);
            }
            return outcome != null ? outcome : RestockOutcome.NOTHING;
        } catch (RuntimeException e) {
            log.warn("Resupply failed for bot {} ({})", bot.getId(), bot.getName(), e);
            return RestockOutcome.NOTHING;
        }
    }
}
