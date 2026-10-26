package soloMapling.server.EventMessageSystem;

import client.Character;
import client.inventory.Item;

/*
Creates events based on type
 */

public class EventFactory {

    public static GameEvent createGachaponEvent(Character mapleCharacter, Item item) {
        return new GameEvent(mapleCharacter, EventType.GACHAPON_REWARD, null, item, null);
    }

    public static GameEvent createLevelUpEvent(Character mapleCharacter) {
        return new GameEvent(mapleCharacter, EventType.LEVEL_UP, null, null, null);
    }

    public static GameEvent createScrollEvent(Character mapleCharacter, Item item, boolean success) {
        return new GameEvent(mapleCharacter, EventType.SCROLLING, null, item, success);
    }

    public static GameEvent createItemMegaphoneEvent(Character mapleCharacter, Item item, String message) {
        return new GameEvent(mapleCharacter, EventType.ITEM_MEGAPHONE, message, item, null);
    }

    public static GameEvent createChatMegaphoneEvent(Character mapleCharacter, String message) {
        return new GameEvent(mapleCharacter, EventType.CHAT_MEGAPHONE, message, null, null);
    }

    public static GameEvent createChatGeneralEvent(Character mapleCharacter, String message) {
        return new GameEvent(mapleCharacter, EventType.CHAT_GENERAL, message, null, null);
    }

    /** Ticket 47: {@code killer} killed a boss; message = the boss's name, detail = its level. */
    public static GameEvent createBossKillEvent(Character killer, String bossName, int bossLevel) {
        return new GameEvent(killer, EventType.BOSS_KILL, bossName, null, null, bossLevel);
    }

    /** Ticket 47: a rare item dropped for {@code owner}; message = the monster's name, detail = the base chance. */
    public static GameEvent createRareDropEvent(Character owner, String mobName, Item item, int baseChance) {
        return new GameEvent(owner, EventType.RARE_DROP, mobName, item, null, baseChance);
    }

    /** Ticket 47: {@code recruiter} is actually forming a party for {@code pqName} and can be invited. */
    public static GameEvent createPqFormingEvent(Character recruiter, String pqName) {
        return new GameEvent(recruiter, EventType.PQ_FORMING, pqName, null, null);
    }

    /**
     * 1.6.0 S3: a PQ or boss instance was cleared. {@code member} is one of the people in it (a real player when
     * there is one); message = the PQ's short name ("KPQ", "zakum"), detail = how many were in the instance. While
     * subscribers run, {@code member.getEventInstance()} still holds everyone who cleared it.
     */
    public static GameEvent createPqClearEvent(Character member, String pqName, int memberCount) {
        return new GameEvent(member, EventType.PQ_CLEAR, pqName, null, null, memberCount);
    }

    /** 1.6.0 S3: a trade window went through between {@code a} and {@code b}; message = b's name, detail = b's id. */
    public static GameEvent createTradeDoneEvent(Character a, Character b) {
        return new GameEvent(a, EventType.TRADE_DONE, b.getName(), null, null, b.getId());
    }

}
