package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import server.maps.MapleMap;
import server.maps.MiniDungeonInfo;
import soloMapling.qa.QaLog;

/** Shared delivery-time gate. Suppressed social actions are dropped, never accumulated for later. */
public final class PlayerBusy {
    private PlayerBusy() { }

    public static boolean isBusy(Character player) {
        if (player == null) {
            return true;
        }
        if (BotHelpers.isBot(player)
                || !Boolean.parseBoolean(System.getProperty("solomapling.botHoldOffBusy", "true"))) {
            return false;
        }
        String reason = reason(player, System.currentTimeMillis());
        if (reason != null && QaLog.on(QaLog.Topic.BOT_CHAT)
                && QaLog.every("busy:" + player.getId(), 30_000)) {
            QaLog.map(QaLog.Topic.BOT_CHAT, player.getMapId(),
                    "HOLD OFF " + player.getName() + ": " + reason + " (action dropped)");
        }
        return reason != null;
    }

    static String reason(Character player, long now) {
        MapleMap map = player.getMap();
        if (player.getEventInstance() != null || map != null && (map.getEventInstance() != null || map.isEventMap())
                || MiniDungeonInfo.isDungeonMap(player.getMapId())) {
            return "event/dungeon instance";
        }
        if (BotChatTuning.isBossMap(player.getMapId()) || BotChatTuning.hasBoss(map)) {
            return "boss map";
        }
        if (player.getTrade() != null || player.getPlayerShop() != null || player.getHiredMerchant() != null
                || player.getShop() != null || player.getUsingOreStorage()
                || player.getStorage() != null && player.getStorage().isOpen()) {
            return "trade/shop/storage";
        }
        Client client = player.getClient();
        if (client != null && client.getCM() != null) {
            return "NPC dialog";
        }
        long last = client == null ? 0 : client.getLastPlayerAction();
        return last > 0 && now - last > 300_000 ? "AFK over five minutes" : null;
    }
}
