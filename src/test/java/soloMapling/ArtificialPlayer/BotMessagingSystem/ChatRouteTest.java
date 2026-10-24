package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.BotThreads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc6: the owner's routing order for an unnamed player line (ChatRoute). */
class ChatRouteTest {

    static final int MAP = 100000000;
    static final int A = 20_001, B = 20_002, C = 20_003, D = 20_004, JOSH = 7;
    static final long T = 10_000_000;

    final List<MapChatLog.Line> lines = new ArrayList<>();
    final Map<Integer, ChatRoute.Bot> bots = new LinkedHashMap<>();

    @AfterEach
    void reset() {
        System.clearProperty("solomapling.convo.hearPx");
    }

    void bot(int id, String name, int x, int y) {
        bots.put(id, new ChatRoute.Bot(id, name, x, y, true));
    }

    void said(int id, String text, long at) {
        ChatRoute.Bot b = bots.get(id);
        lines.add(new MapChatLog.Line(id, b == null ? "Josh" : b.name(), text, at, b != null));
    }

    ChatRoute.Decision route(String line, Boolean facingLeft, BotThreads.View thread, long now) {
        return route(line, facingLeft, thread, -1, 0, now);
    }

    ChatRoute.Decision route(String line, Boolean facingLeft, BotThreads.View thread, int talkingTo, long since, long now) {
        lines.add(new MapChatLog.Line(JOSH, "Josh", line, now, false));
        List<BotAddressee.Candidate> cands = new ArrayList<>();
        for (ChatRoute.Bot b : bots.values()) {
            cands.add(new BotAddressee.Candidate(b.id(), b.name(), 0));
        }
        return ChatRoute.decide(lines, new ChatRoute.Player(JOSH, "Josh", line, 0, 0, facingLeft, talkingTo, since), bots,
                thread, BotAddressee.resolve(line, cands), id -> "neutral", now);
    }

    BotThreads.View zakumThread() {
        bot(A, "Zephyrr", 120, 0);
        bot(B, "Mochiko", 220, 0);
        bot(C, "Kimbap", -150, 0);
        said(A, "we wiped at zakum again lol", T);
        said(B, "ya the arms one shot our bishop", T + 5_000);
        return new BotThreads.View(9, List.of(A, B), List.of("Zephyrr", "Mochiko"), B, T - 1_000, T + 5_000, "gossip");
    }

    @Test
    void ownersExampleTheThreadAnswersWaitWhoWiped() {
        BotThreads.View thread = zakumThread();
        said(C, "selling ilbis 30m", T + 9_000);
        ChatRoute.Decision d = route("wait who wiped?", null, thread, T + 12_000);
        assertEquals(ChatRoute.Step.THREAD_WORDS, d.step());
        assertEquals(B, d.botId(), "the thread's last speaker answers");
        assertNotNull(d.thread());
        assertEquals("thread:9", d.chosenKey());
    }

    /**
     * auto-QA t-cv-5 (rc6 run 21095df97): the thread's own lines ("lpq?", "need 2 more tbh u got a cleric?") said
     * nothing the player's line shared, and a passing question in a busy town took the line. The thread's subject
     * ("looking for a party (Ludi PQ)") counts: "ludi pq? i can come too" clearly answers the thread.
     */
    @Test
    void aLineAboutTheThreadsSubjectJoinsItOverAPassingQuestion() {
        bot(A, "Princessy", 85, 0);
        bot(B, "SkillSpamz", -98, 0);
        bot(C, "iiSkaterz", 150, 0);
        said(A, "need 2 more tbh", T);
        said(B, "u got a cleric", T + 4_000);
        said(C, "anyone got a bless?", T + 6_000);
        BotThreads.View thread = new BotThreads.View(9, List.of(A, B), List.of("Princessy", "SkillSpamz"), B, T - 1_000,
                T + 4_000, "looking for a party (Ludi PQ)");
        ChatRoute.Decision d = route("Ludi PQ? i can come too", null, thread, T + 8_000);
        assertEquals(ChatRoute.Step.THREAD_WORDS, d.step());
        assertEquals(B, d.botId(), "the thread's last speaker answers");
        assertEquals("thread:9", d.chosenKey());
        BotThreads.View bare = new BotThreads.View(9, List.of(A, B), List.of("Princessy", "SkillSpamz"), B, T - 1_000,
                T + 4_000, "looking for a party");
        assertEquals(ChatRoute.Step.REPLY_QUESTION, route("Ludi PQ? i can come too", null, bare, T + 8_000).step(),
                "without the subject the passing question took it (the run's failure)");
    }

    @Test
    void aReplyThatNamesTheThreadsSubjectJoinsItOverANearbyBotThatEchoesTheWords() {
        // cv-5 on the TEST server (logs/qa/cv-5-20261024T054725Z.log): the QA thread's own lines never said the PQ
        // out loud - its subject carried it ("looking for a party (Orbis PQ)") - while axeperion's line said
        // "come orbis", so reply-word matching outscored the thread the player was plainly answering.
        bot(A, "Princessy", 85, 0);
        bot(B, "SkillSpamz", -98, 0);
        bot(C, "axeperion", 150, 0);
        said(A, "anyone up for a party tonight", T);
        said(B, "sure, need a cleric though", T + 2_000);
        said(C, "come orbis, i can carry", T + 5_000);
        BotThreads.View thread = new BotThreads.View(9, List.of(A, B), List.of("Princessy", "SkillSpamz"), B,
                T - 1_000, T + 2_000, "looking for a party (Orbis PQ)");
        ChatRoute.Decision d = route("Orbis PQ? i can come too", null, thread, T + 7_000);
        assertEquals(ChatRoute.Step.THREAD_WORDS, d.step(), "naming the thread's subject joins the thread");
        assertEquals(B, d.botId(), "the thread's last speaker answers");
        assertEquals("thread:9", d.chosenKey());
    }

    @Test
    void anAnswerToAnotherBotsQuestionGoesToThatBotWhileAThreadRuns() {
        BotThreads.View thread = zakumThread();
        said(C, "anyone selling ilbis?", T + 9_000);
        ChatRoute.Decision d = route("how much", null, thread, T + 12_000);
        assertEquals(ChatRoute.Step.REPLY_QUESTION, d.step());
        assertEquals(C, d.botId());
        assertNull(d.thread());
    }

    @Test
    void step1ANameWinsEvenAcrossTheMapAndOverFacing() {
        bot(A, "Zephyrr", 80, 0);        // faced, close
        bot(D, "Kimchiz", 3_000, -600);  // far outside the bubble
        ChatRoute.Decision d = route("kimchiz wanna pq", false, null, T);
        assertEquals(ChatRoute.Step.NAME, d.step());
        assertEquals(D, d.botId());
    }

    @Test
    void step2AFacedCloseBotBeatsAThreadAndAMoreRecentSpeaker() {
        BotThreads.View thread = zakumThread();
        bot(D, "Pinkyy", -90, 10);
        said(C, "anyone selling ilbis?", T + 9_000);
        ChatRoute.Decision d = route("who wiped lol", true, thread, T + 12_000); // facing left, at Pinkyy
        assertEquals(ChatRoute.Step.FACING, d.step());
        assertEquals(D, d.botId());
        assertTrue(d.candidates().stream().anyMatch(c -> c.botId() == D && c.facing()));
    }

    @Test
    void facingNeedsTheNearestBotThatWayCloseAndOnTheSameHeight() {
        bot(A, "Zephyrr", 250, 0);   // too far
        bot(B, "Mochiko", 60, 120);  // another platform
        ChatRoute.Decision d = route("sup", false, null, T);
        assertEquals(ChatRoute.Step.CLOSEST, d.step(), "no faced bot: the closest answers");
        assertTrue(d.candidates().stream().noneMatch(ChatRoute.Candidate::facing));
    }

    @Test
    void noFacingInfoChangesNothing() {
        bot(A, "Zephyrr", 80, 0);
        bot(B, "Mochiko", -300, 0);
        said(B, "anyone wanna do kpq?", T);
        ChatRoute.Decision unknown = route("sure", null, null, T + 4_000);
        assertEquals(ChatRoute.Step.REPLY_QUESTION, unknown.step());
        assertEquals(B, unknown.botId());
        lines.remove(lines.size() - 1);
        assertEquals(ChatRoute.Step.FACING, route("sure", false, null, T + 4_000).step(), "facing would change it");
    }

    @Test
    void step3AReplyThatSharesAWordGoesToThatLinesSpeaker() {
        bot(A, "Zephyrr", 50, 0);
        bot(B, "Mochiko", 300, 0);
        said(B, "pigs drop nothing lol", T);
        said(A, "brb pots", T + 3_000);
        ChatRoute.Decision d = route("yeah pigs are trash", null, null, T + 6_000);
        assertEquals(ChatRoute.Step.REPLY_WORDS, d.step());
        assertEquals(B, d.botId(), "not the closest, not the newest: the one it replies to");
        assertEquals("pigs drop nothing lol", d.answers().text());
    }

    @Test
    void step3AnOldQuestionIsNoLongerAClearReply() {
        bot(A, "Zephyrr", 50, 0);
        bot(B, "Mochiko", 300, 0);
        said(B, "anyone from the east coast?", T);
        ChatRoute.Decision d = route("ya", null, null, T + ChatRoute.REPLY_MS + 1_000);
        assertEquals(ChatRoute.Step.CLOSEST, d.step());
        assertEquals(A, d.botId());
    }

    @Test
    void step3TheBotThePlayerIsTalkingToKeepsTheChat() {
        bot(A, "Zephyrr", 50, 0);
        bot(B, "Mochiko", 200, 0);
        said(B, "i train at ants", T);
        ChatRoute.Decision d = route("nice", null, null, B, T, T + 5_000);
        assertEquals(ChatRoute.Step.ONGOING, d.step());
        assertEquals(B, d.botId());
    }

    @Test
    void step3ALineRightAfterAThreadJoinsIt() {
        BotThreads.View thread = zakumThread();
        ChatRoute.Decision d = route("i can come too", null, thread, T + 20_000);
        assertEquals(ChatRoute.Step.THREAD_RECENT, d.step());
        assertEquals(B, d.botId());
        assertEquals(ChatRoute.Step.CLOSEST, route("i can come too", null, thread,
                T + 5_000 + ChatRoute.THREAD_JOIN_MS + 1_000).step(), "45 s after its last line it is over");
    }

    @Test
    void aThreadWithNobodyInTheBubbleIsNotACandidate() {
        bot(A, "Zephyrr", 900, 0);
        bot(B, "Mochiko", 1_000, 0);
        bot(C, "Kimbap", 100, 0);
        said(A, "we wiped at zakum again lol", T);
        said(B, "ya the arms one shot us", T + 5_000);
        BotThreads.View thread = new BotThreads.View(9, List.of(A, B), List.of("Zephyrr", "Mochiko"), B, T - 1_000,
                T + 5_000, "gossip");
        ChatRoute.Decision d = route("who wiped?", null, thread, T + 8_000);
        assertEquals(ChatRoute.Step.CLOSEST, d.step());
        assertEquals(C, d.botId());
        assertTrue(d.candidates().stream().anyMatch(c -> c.key().equals("thread:9") && !c.inBubble()));
    }

    @Test
    void step4TheClosestBotInTheBubbleAnswersWhenNothingElseFits() {
        bot(A, "Zephyrr", 350, 0);
        bot(B, "Mochiko", -120, 30);
        bot(C, "Kimbap", 20, 400); // closer in x, but out of the bubble vertically
        ChatRoute.Decision d = route("so bored", null, null, T);
        assertEquals(ChatRoute.Step.CLOSEST, d.step());
        assertEquals(B, d.botId());
    }

    @Test
    void step5aBotOutsideTheBubbleIsNeverChosenForAnUnnamedLine() {
        bot(A, "Zephyrr", 600, 0);
        said(A, "anyone selling ilbis?", T);
        ChatRoute.Decision d = route("how much", null, null, T + 3_000);
        assertEquals(ChatRoute.Step.NONE, d.step(), "its question was heard, but it is too far to be answered");
        assertEquals(-1, d.botId());
        assertFalse(d.candidates().get(0).inBubble());
        System.setProperty("solomapling.convo.hearPx", "700");
        lines.remove(lines.size() - 1);
        assertEquals(ChatRoute.Step.REPLY_QUESTION, route("how much", null, null, T + 3_000).step(), "hearPx");
    }

    @Test
    void aMenuBotIsNeverTheUnnamedDefault() {
        bots.put(A, new ChatRoute.Bot(A, "Shopz", 40, 0, false));
        assertEquals(ChatRoute.Step.NONE, route("hello", null, null, T).step());
    }

    @Test
    void everyCandidateCarriesItsSignals() {
        BotThreads.View thread = zakumThread();
        said(C, "Josh u selling ilbis?", T + 9_000);
        ChatRoute.Decision d = route("wait who wiped?", null, thread, T + 12_000);
        ChatRoute.Candidate c = d.candidates().stream().filter(x -> x.botId() == C && !x.key().startsWith("thread"))
                .findFirst().orElseThrow();
        assertTrue(c.question());
        assertTrue(c.addressedPlayer());
        assertEquals(3, c.secondsAgo());
        assertEquals(-150, c.dx());
        assertEquals(150, c.distance());
        assertTrue(c.inBubble());
        assertEquals("neutral", c.tier());
        ChatRoute.Candidate t = d.candidates().stream().filter(x -> x.key().equals("thread:9")).findFirst().orElseThrow();
        assertEquals(1, t.sharedWords());
        assertTrue(t.inThread());
        assertTrue(d.question());
        assertEquals(15, d.length());
    }
}
