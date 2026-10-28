package soloMapling.achievements;

import client.Character;

/**
 * Who an event is about: the character id (a bot's is 1,000,000,000 or more), whether it is a bot, and the live
 * {@link Character} when there is one (null when offline: a medal owed then waits for the next login).
 */
public record Subject(int id, boolean bot, Character chr) {

    public static Subject of(Character chr) {
        return new Subject(chr.getId(), soloMapling.ArtificialPlayer.BotHelpers.isBot(chr), chr);
    }
}
