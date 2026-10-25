package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyChatRoute.Choice;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyChatRoute.Member;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 85: party chat gates, rate limits and the scripted lines. */
class BotPartyChatTest {

    static final List<Member> BOTS = List.of(PartyChatRouteTest.NAYRB, PartyChatRouteTest.KIRA);

    @Test
    void atLeastEightyPercentOfLinesAimedAtTheBotsGetAnAnswer() {
        Random rng = new Random(7);
        String[] aimed = {"nayrb ready?", "KiraStar wait for me", "ready?", "guys go left", "hey", "omw", "who has pots?",
                "nayrb thanks!"};
        int answered = 0;
        int total = 0;
        long now = 1_000_000L;
        for (int i = 0; i < 2000; i++) {
            BotPartyChat.PartyState state = new BotPartyChat.PartyState(); // each line a minute apart: the limits are not the test
            now += 60_000;
            Choice c = BotPartyChat.decide(state, aimed[i % aimed.length], BOTS, BOTS.size(), now, rng);
            total++;
            if (c != null) {
                answered++;
            }
        }
        assertTrue(answered >= total * 0.8, answered + " of " + total);
    }

    @Test
    void plainChatterIsAnsweredOnlySometimes() {
        Random rng = new Random(11);
        int answered = 0;
        long now = 5_000_000L;
        for (int i = 0; i < 2000; i++) {
            now += 60_000;
            if (BotPartyChat.decide(new BotPartyChat.PartyState(), "this map is so crowded today", BOTS, 2, now, rng) != null) {
                answered++;
            }
        }
        assertTrue(answered > 200 && answered < 1000, "answered " + answered + " of 2000");
    }

    @Test
    void aPartyHearsOneBotLineAtATimeAndNoMoreThanEightAMinute() {
        BotPartyChat.PartyState state = new BotPartyChat.PartyState();
        Random rng = new Random(3);
        long now = 10_000_000L;
        assertNotNull(BotPartyChat.decide(state, "nayrb ready?", BOTS, 2, now, rng));
        assertNull(BotPartyChat.decide(state, "nayrb ready?", BOTS, 2, now + 500, rng), "named lines wait 1.5 s");
        assertNull(BotPartyChat.decide(state, "this map is so crowded", BOTS, 2, now + 3_000, new Random(99) {
            @Override
            public double nextDouble() {
                return 0;
            }
        }), "open chatter waits 6 s even when the dice say yes");
        int answered = 1;
        for (int i = 1; i < 30; i++) {
            if (BotPartyChat.decide(state, "nayrb ready?", BOTS, 2, now + i * 1_600L, rng) != null) {
                answered++;
            }
        }
        assertEquals(BotPartyChat.MAX_PER_MINUTE, answered, "the minute window caps a chatty player");
        assertNotNull(BotPartyChat.decide(state, "nayrb ready?", BOTS, 2, now + 125_000, rng), "a new minute opens again");
    }

    @Test
    void commandsTypedInPartyChatReachNoBot() {
        assertTrue(BotPartyChat.isCommand("!warp henesys"));
        assertTrue(BotPartyChat.isCommand("  @dojo"));
        assertFalse(BotPartyChat.isCommand("nayrb ready?"));
    }

    @Test
    void scriptedPartyLinesNeverSayWhatTheBotIs() {
        Pattern disclosure = Pattern.compile("(?i)\\b(ai|a\\.i\\.|bot|bots|robot|npc|language model|llm|assistant|program|script|server)\\b");
        for (String line : PartyLines.IDLE) {
            assertFalse(disclosure.matcher(line).find(), line);
        }
        for (String line : PartyLines.ANSWER) {
            assertFalse(disclosure.matcher(line).find(), line);
        }
    }

    @Test
    void thePartyHookIsWiredIntoTheMultiChatHandler() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/net/server/channel/handlers/MultiChatHandler.java"));
        int party = src.indexOf("type == 1");
        int guild = src.indexOf("type == 2");
        assertTrue(party > 0 && guild > party);
        assertTrue(src.substring(party, guild).contains("BotPartyChat.onPartyChat"), "party chat must call the bot hook");
    }
}
