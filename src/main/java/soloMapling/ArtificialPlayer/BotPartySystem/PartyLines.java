package soloMapling.ArtificialPlayer.BotPartySystem;

import java.util.List;
import java.util.Random;

/** The few scripted lines bots say to each other in a party with no player in it. Nothing here names what a bot is. */
final class PartyLines {

    static final List<String> IDLE = List.of("brb pots", "wait mp", "omw", "this map is slow lol", "anyone need pots?",
            "ready when u are", "ok going left", "need a rest", "good exp here", "back", "afk 1 min", "nice run");
    static final List<String> ANSWER = List.of("k", "ok", "lol", "same", "np", "ya", "sure", "ok ty", "nice");

    private PartyLines() {
    }

    static String pick(List<String> bank, Random rng) {
        return bank.get(rng.nextInt(bank.size()));
    }
}
