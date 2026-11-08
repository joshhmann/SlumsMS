package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import server.expeditions.Expedition;

import java.util.List;
import java.util.function.BiPredicate;

/** Test support for tests outside this package: party-bot registration without the boss-log DB read. */
public final class ExpeditionBotsAccess {

    private static final ExpeditionBots.Quota REAL = ExpeditionBots.quota;

    private ExpeditionBotsAccess() {
    }

    public static void noQuotaRead() {
        ExpeditionBots.quota = (bot, exped) -> true;
    }

    public static void quota(BiPredicate<Character, Expedition> hasEntry) {
        ExpeditionBots.quota = hasEntry::test;
    }

    public static void restoreQuota() {
        ExpeditionBots.quota = REAL;
    }

    /** Registers a bot with an expedition the way {@code autoJoin} does, without the boss-log DB read. */
    public static void hold(Character bot, Expedition exped) {
        ExpeditionHold.hold(bot.getId(), exped);
    }

    public static List<String> autoJoin(Character leader, Expedition exped) {
        return ExpeditionBots.autoJoin(leader, exped);
    }

    public static void release(Expedition exped) {
        ExpeditionBots.release(exped);
    }
}
