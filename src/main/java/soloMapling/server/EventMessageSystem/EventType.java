package soloMapling.server.EventMessageSystem;

public enum EventType {
    GACHAPON_REWARD("Gachapon Reward"),
    LEVEL_UP("Level Up"),
    SCROLLING("Scrolling Result"),
    ITEM_MEGAPHONE("Item Megaphone Announcement"),
    CHAT_MEGAPHONE("Chat Megaphone"),
    MASTERY_RESULT("Mastery Result"),
    EMOTE("Emote"),
    CHAT_GENERAL("General Chat"),
    MAP_ENTERED("Player Entered Map"),
    // Ticket 47: world events a bot megaphone can react to (WorldEventMegaphones).
    BOSS_KILL("Boss Killed"),
    RARE_DROP("Rare Drop"),
    PQ_FORMING("Party Quest Forming"),
    // 1.6.0 S3: fired from SocialHooks (EventInstanceManager.clearPQ, Trade.completeTrade).
    PQ_CLEAR("Party Quest Cleared"),
    TRADE_DONE("Trade Completed");

    private final String description;

    EventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
