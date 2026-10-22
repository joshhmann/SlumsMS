package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChatPromptTest {

    private static Character character(String name, int level, int job) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setName(name);
        chr.setLevel(level);
        chr.setJob(Job.getById(job));
        return chr;
    }

    @Test
    void aPersonaIsStableAndSurvivesItsColumn() {
        BotPersona.Persona a = BotPersona.generate(557241);
        assertEquals(a, BotPersona.generate(557241));
        assertEquals(a, BotPersona.parse(a.toJson()));
        assertEquals(2, a.interests().size());
        assertTrue(BotPersona.describe(a, 0.1).contains("a few words"));
        assertTrue(BotPersona.describe(a, 0.9).contains("1-2 short sentences"));
    }

    @Test
    void theSystemPromptCarriesWhoWhereWhoTheyAreTalkingToFactsAndRules() {
        Character bot = character("xXSkyBladeXx", 34, 310);
        Character player = character("Josh", 52, 211);
        String system = BotChatPrompt.system(bot, player, BotPersona.generate(1), 0.5, "grinding",
                List.of("said they were saving for a pink adventurer cape"));

        assertTrue(system.startsWith("You are xXSkyBladeXx, a real person playing MapleStory in 2008."));
        assertTrue(system.contains("level 34"));
        assertTrue(system.contains("currently grinding"));
        assertTrue(system.contains("talking to Josh, a level 52"));
        assertTrue(system.contains("pink adventurer cape"));
        assertTrue(system.contains("Ellinia station"), "world facts are included");
        assertTrue(system.contains("Never: say you are an AI"));
        assertTrue(system.contains("No emojis, no markdown"));
    }

    @Test
    void theConversationStartsWithTheSystemPromptThenTheHistory() {
        List<BotBrain.Turn> turns = BotChatPrompt.conversation("sys", List.of(BotBrain.Turn.user("yo"),
                BotBrain.Turn.assistant("sup"), BotBrain.Turn.user("nice hat")));
        assertEquals(4, turns.size());
        assertEquals(BotBrain.Role.SYSTEM, turns.get(0).role());
        assertEquals("nice hat", turns.get(3).content());
        assertEquals("hanging out in town", BotChatPrompt.activityPhrase("social"));
    }

    @Test
    void theGroundedPromptCarriesMoodPartyRecentChatAndTheActionOutcome() {
        BotChatPrompt.Context c = new BotChatPrompt.Context("Kimchiz", 41, "assassin", "Ant Tunnel I", "grinding",
                "in a party with them", "focused on grinding, a bit distracted", "Josh", 30, "page", List.of(),
                List.of("Josh: anyone here", "Kimchiz: ya"), "You agreed to party: you are waiting for their invite.");
        String system = BotChatPrompt.system(c, BotPersona.generate(3), 0.3);
        assertTrue(system.contains("level 41 assassin in Ant Tunnel I, currently grinding, in a party with them."));
        assertTrue(system.contains("Mood: focused on grinding"));
        assertTrue(system.contains("You have not talked to Josh before."));
        assertTrue(!system.contains("Recent chat around you"), "the map's chat goes in the final turn now");
        assertTrue(system.contains("your reply must say so: You agreed to party"));
        assertEquals("wary of this player after how they acted before, short with them",
                BotChatPrompt.mood(3, "grinding", true, 0));
    }

    @Test
    void theFinalTurnCarriesTheTranscriptWhatTheBotJustSaidAndTheInstructionLast() {
        // owner report 2026-10-22: "anyone from the east coast?" -> "im from the east" -> a reply about the west coast
        String transcript = "Kiki (you): anyone from the east coast?\nJosh: im from the east";
        List<BotBrain.Turn> turns = BotChatPrompt.conversation("sys", List.of(BotBrain.Turn.user("yo"),
                BotBrain.Turn.assistant("sup"), BotBrain.Turn.assistant("anyone from the east coast?"),
                BotBrain.Turn.user("im from the east")), transcript, "Josh", "anyone from the east coast?");
        assertEquals(5, turns.size(), "system + the earlier turns + one final user turn");
        assertEquals(BotBrain.Role.ASSISTANT, turns.get(3).role());
        assertEquals("anyone from the east coast?", turns.get(3).content(), "its question is its own turn");
        String last = turns.get(4).content();
        assertEquals(BotBrain.Role.USER, turns.get(4).role());
        assertTrue(last.startsWith("Public chat on this map just now, oldest first:\nKiki (you): anyone from the east"
                + " coast?\nJosh: im from the east\n\n"), last);
        assertTrue(last.contains("You just said: \"anyone from the east coast?\". Josh answered: \"im from the east\"."),
                last);
        assertTrue(last.endsWith("Reply to what Josh actually said, stay on the same topic. If they asked you something,"
                + " answer it. One short line, only your own words."), last);

        String plain = BotChatPrompt.finalTurn("Josh: yo", "Josh", null, "yo");
        assertTrue(plain.contains("Josh says to you: \"yo\""), plain);
        assertEquals("hi", BotChatPrompt.finalTurn("", "Josh", null, "hi"), "a whisper is the line alone");
    }

    @Test
    void worldFactsOnlyWhenTheLineAsksAboutTheWorld() {
        BotChatPrompt.Context c = new BotChatPrompt.Context("Kimchiz", 41, "assassin", "Henesys", null, null, null,
                "Josh", 30, "page", List.of(), List.of(), null);
        assertTrue(!BotChatPrompt.system(c, BotPersona.generate(3), 0.3, null, null, false).contains("World facts"));
        assertTrue(BotChatPrompt.system(c, BotPersona.generate(3), 0.3, null, null, true).contains("World facts"));
        assertTrue(BotChatPrompt.asksAboutTheWorld("how do i get to ellinia"));
        assertTrue(BotChatPrompt.asksAboutTheWorld("where do u train at lv 30"));
        assertTrue(!BotChatPrompt.asksAboutTheWorld("im from the east"));
        assertTrue(!BotChatPrompt.asksAboutTheWorld("lol same"));
    }

    @Test
    void aMerchantsStockAndARecognisedPlayerGoIntoThePrompt() {
        BotChatPrompt.Context c = new BotChatPrompt.Context("Shopz", 60, "hermit", "Free Market", "running a shop",
                null, "chill", "Josh", 30, "page", List.of("said they're saving for a pink adventurer cape"),
                List.of(), null);
        String plain = BotChatPrompt.system(c, BotPersona.generate(3), 0.3);
        assertTrue(!plain.contains("You know Josh from before") && !plain.contains("You are selling"), plain);

        MerchantStock.Stock stock = new MerchantStock.Stock(true, "Scroll for Gloves for ATT 60%", 12_000_000, "gloves");
        String system = BotChatPrompt.system(c.with(stock.fact(), true), BotPersona.generate(3), 0.3);
        assertTrue(system.contains("You are selling Scroll for Gloves for ATT 60% for 12m right now"), system);
        assertTrue(system.contains("Do not make up other items or prices"), system);
        assertTrue(system.contains("You know Josh from before"), system);
        assertTrue(system.contains("never bring up anything bad"), system);

        // A stranger is never "recognised", whatever the caller says: the hint rides on the memories.
        BotChatPrompt.Context stranger = new BotChatPrompt.Context("Shopz", 60, "hermit", null, null, null, null,
                "Josh", 30, "page", List.of(), List.of(), null).with(null, true);
        assertTrue(!BotChatPrompt.system(stranger, BotPersona.generate(3), 0.3).contains("You know Josh"));
    }
}
