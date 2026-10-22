package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59 (owner requirement 2026-10-22): the pre-generated line bank. */
class ChatBankTest {

    private static ChatBank bank(String... rows) throws Exception {
        return ChatBank.read(new ByteArrayInputStream(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
    }

    private static ChatBank.Query q(String intent, String affinity, ConvoPersona.Voice voice, long situation) {
        return new ChatBank.Query(intent, null, affinity, voice, situation, Map.of("player", "Pinky", "map", "henesys"));
    }

    @Test
    void readsTheFileAndPicksByIntentAffinityAndVoice() throws Exception {
        ChatBank b = bank("#chatbank v1",
                "1\tcome_yes\tany\tfriend\tlower\t-\tomw {player}",
                "2\tcome_yes\tany\tneutral\tproper\t-\tOn my way.",
                "3\tcome_no\tany\tneutral\tany\t-\tcant rn");
        assertEquals(3, b.size());
        assertEquals("omw Pinky", b.pick(q("come_yes", "friend", ConvoPersona.Voice.LOWER, 0), null, null, new Random(1)).text());
        assertEquals(2, b.pick(q("come_yes", "neutral", ConvoPersona.Voice.PROPER, 0), null, null, new Random(1)).id());
        assertNull(b.pick(q("party_yes", "neutral", null, 0), null, null, new Random(1)), "no such intent");
    }

    @Test
    void relaxesVoiceThenAffinity() throws Exception {
        ChatBank b = bank("1\tgreeting\tany\tneutral\tproper\t-\tHello.");
        ChatBank.Pick p = b.pick(q("greeting", "nemesis", ConvoPersona.Voice.LEET, 0), null, null, new Random(1));
        assertNotNull(p, "any voice, then neutral feelings");
    }

    @Test
    void aLineNeedingAMissingSlotIsSkipped() throws Exception {
        ChatBank b = bank("1\ttalk\tany\tneutral\tany\t-\tlol {mob} again", "2\ttalk\tany\tneutral\tany\t-\tlol ya");
        for (int i = 0; i < 20; i++) {
            assertEquals(2, b.pick(q("talk", "neutral", null, 0), null, null, new Random(i)).id());
        }
        assertNull(ChatBank.fill("hi {price}", Map.of()));
    }

    @Test
    void situationTagsKeepLinesWhereTheyFit() throws Exception {
        ChatBank b = bank("1\ttalk\tany\tneutral\tany\tboss\tthis boss hits so hard",
                "2\ttalk\tany\tneutral\tany\ttown\tso many ppl in town");
        long town = ChatBank.Tag.TOWN.bit;
        for (int i = 0; i < 30; i++) {
            assertEquals(2, b.pick(q("talk", "neutral", null, town), null, null, new Random(i)).id(), "no boss line in town");
        }
    }

    @Test
    void recentLinesAndBlacklistedLinesAreNotRepeated() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            rows.add(i + "\ttalk\tany\tneutral\tany\t-\tline number " + "abcdefghijklmnopqrstuvwxyz".charAt(i % 26) + i);
        }
        ChatBank b = ChatBank.read(new ByteArrayInputStream(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
        RecentLines.resetForTest();
        RecentLines.Window w = new RecentLines.Window(9001, 77);
        Set<Integer> got = new HashSet<>();
        Random rng = new Random(3);
        for (int i = 0; i < 50; i++) {
            ChatBank.Pick p = b.pick(q("talk", "neutral", null, 0), w, id -> id == 7 ? 0 : 1, rng);
            if (p == null) {
                break;
            }
            assertTrue(got.add(p.id()), "repeated " + p.id());
            RecentLines.used(9001, 77, p.id());
        }
        assertTrue(!got.contains(7), "blacklisted");
        assertEquals(49, got.size(), "every other line before any repeat");
    }

    @Test
    void heapPerHundredThousandLinesStaysSmall() throws Exception {
        Random rng = new Random(5);
        List<String[]> rows = new ArrayList<>();
        String[] intents = {"talk", "talk_q", "greeting", "come_yes", "party_no", "thread_lfg", "insult_back"};
        for (int i = 0; i < 100_000; i++) {
            StringBuilder t = new StringBuilder();
            while (t.length() < 34) {
                t.append((char) ('a' + rng.nextInt(26)));
                if (rng.nextInt(5) == 0) {
                    t.append(' ');
                }
            }
            rows.add(new String[]{String.valueOf(i + 1), intents[i % intents.length], "any",
                    ChatBank.AFFINITIES.get(1 + i % 4), ConvoPersona.Voice.values()[i % 6].label(), i % 3 == 0 ? "town" : "-",
                    t.toString()});
        }
        ChatBank b = ChatBank.build(rows);
        long mb = b.heapBytes() / (1024 * 1024);
        System.out.println("ChatBank heap for 100k lines (avg 35 chars): ~" + b.heapBytes() / 1024 + " KB");
        assertTrue(mb <= 12, mb + " MB per 100k lines");
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            assertNotNull(b.pick(q("talk", "friend", ConvoPersona.Voice.LOWER, 0), null, null, rng));
        }
        long perPickUs = (System.nanoTime() - t0) / 1000 / 1000;
        assertTrue(perPickUs < 5000, perPickUs + " us per pick");
    }

    @Test
    void theShippedBankLoadsAndEveryLinePassesTheGuard() throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of(ChatBank.RESOURCE);
        assertTrue(java.nio.file.Files.exists(file), "the first batch ships");
        try (var in = new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file))) {
            ChatBank b = ChatBank.read(in);
            assertTrue(b.size() >= 200, "" + b.size());
            for (String intent : List.of("greeting", "talk", "come_yes", "party_no", "brush_scam")) {
                assertTrue(b.intents().contains(intent), intent);
            }
            for (int i = 0; i < b.size(); i++) {
                String filled = ChatBank.fill(b.row(i), Map.of("player", "Pinky", "map", "henesys", "mob", "slime",
                        "item", "work gloves", "level", "30", "job", "hermit", "guild", "Maplers", "price", "1m",
                        "partner", "Bob"));
                assertNotNull(filled, b.row(i));
                assertNull(soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard.chatProblem(filled), filled);
            }
        }
    }
}
