package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotEpisodes;
import soloMapling.ArtificialPlayer.FakeMemoryTable;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotMemory.Kind;
import soloMapling.ArtificialPlayer.BotMemory.Note;
import soloMapling.ArtificialPlayer.BotMemory.Provenance;
import soloMapling.ArtificialPlayer.BotMemory.Source;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Decision;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Manner;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Plan;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 271 Stage 3: bot-to-bot talk is grounded in what the two really remember of each other. The prompts of
 * {@link BotThreads} and {@link BotTalk} carry the partner's real memories (written only by server events); no memory
 * leaves them as they were; a memory that names anyone else never reaches a prompt; the model cannot write a memory.
 */
class PartnerMemoryTest {

    static final long DAY = TimeUnit.DAYS.toMillis(1);
    private final AtomicLong clock = new AtomicLong(100 * DAY);
    private final List<int[]> asked = new ArrayList<>();
    private final List<Note> aboutBob = new ArrayList<>();
    private final List<Note> aboutAnn = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BotTalk.resetForTest();
        SocialSim.useForTest(clock::get, 5L);
        PartnerMemory.useForTest((speaker, partner, q, now, limit) -> {
            asked.add(new int[]{speaker, partner});
            return speaker == ann().getId() ? aboutBob : speaker == bob().getId() ? aboutAnn : List.of();
        }, map -> map == 101000000 ? "ellinia" : null);
    }

    @AfterEach
    void tearDown() {
        PartnerMemory.useForTest(null, null);
        SocialSim.reset();
        BotTalk.resetForTest();
        BotEpisodes.useForTest(null, null, null);
        FakeMemoryTable.uninstall();
        BotMemory.useForTest(null, null, 0);
    }

    private static Character ann;
    private static Character bob;
    private static Character cat;

    private static Character ann() {
        return ann != null ? ann : (ann = TalkPlannerTest.chr(1, "Ann", 30));
    }

    private static Character bob() {
        return bob != null ? bob : (bob = TalkPlannerTest.chr(2, "Bob", 31));
    }

    private static Character cat() {
        return cat != null ? cat : (cat = TalkPlannerTest.chr(3, "Cat", 32));
    }

    private Note note(int about, Kind kind, String text, double valence, int map) {
        return new Note(0, 1, about, text, Source.DIRECT, 0, 0, clock.get() - DAY, 0, kind, 4, "", 0, null, map, Double.NaN,
                valence, Provenance.WITNESSED, 0);
    }

    // ── BotThreads prompt ──

    @Test
    void aThreadPromptCarriesTheRealMemoriesTheTwoHaveOfEachOther() {
        aboutBob.add(note(bob().getId(), Kind.PARTY, "partied with me", 0.4, 101000000));
        aboutAnn.add(note(ann().getId(), Kind.ASKED, "turned me down for a trade", -0.4, 0));
        String prompt = BotThreads.threadPrompt(ann(), bob(), BotThreads.Topic.GOSSIP, null, "Henesys");
        assertTrue(prompt.contains("Their real shared history"), prompt);
        assertTrue(prompt.contains("Ann remembers Bob: partied with me in ellinia (felt good)"), prompt);
        assertTrue(prompt.contains("Bob remembers Ann: turned me down for a trade (felt bad)"), prompt);
        assertTrue(prompt.contains("must not invent any other favor, fight or shared past"), prompt);
        assertTrue(prompt.contains("name no other player"), "the no-third-party rule is still there: " + prompt);
    }

    @Test
    void withNothingRememberedAThreadPromptIsAsItWas() {
        String prompt = BotThreads.threadPrompt(ann(), bob(), BotThreads.Topic.GOSSIP, null, "Henesys");
        assertFalse(prompt.contains("shared history"), prompt);
        assertTrue(prompt.contains("Topic: gossip") && prompt.contains("name no other player"), prompt);
    }

    @Test
    void aMemoryThatNamesAThirdPartyNeverReachesAPrompt() {
        aboutBob.add(note(bob().getId(), Kind.SCAM, "was scammed by xXSlayerXx on a trade", -0.9, 0));
        aboutBob.add(note(bob().getId(), Kind.PARTY, "partied with me", 0.4, 0));
        String prompt = BotThreads.threadPrompt(ann(), bob(), BotThreads.Topic.GOSSIP, null, "Henesys");
        assertFalse(prompt.contains("xXSlayerXx"), prompt);
        assertTrue(prompt.contains("partied with me"), "the clean memory stays: " + prompt);
        assertEquals("remember when u partied with me lol", PartnerMemory.callback(ann(), bob(), clock.get()),
                "the clean memory is still available as a callback");
    }

    @Test
    void onlyTheTwoSpeakersMemoriesOfEachOtherAreEverAsked() {
        aboutBob.add(note(bob().getId(), Kind.PARTY, "partied with me", 0.4, 0));
        BotThreads.threadPrompt(ann(), bob(), BotThreads.Topic.TRADE, null, "Henesys");
        assertEquals(2, asked.size(), "Ann about Bob and Bob about Ann, nothing about Cat");
        assertEquals(ann().getId(), asked.get(0)[0]);
        assertEquals(bob().getId(), asked.get(0)[1]);
        assertEquals(bob().getId(), asked.get(1)[0]);
        assertEquals(ann().getId(), asked.get(1)[1]);
    }

    @Test
    void aSourceThatFailsMeansNoMemoryNotAFailedPrompt() {
        PartnerMemory.useForTest((s, p, q, n, l) -> {
            throw new IllegalStateException("the store is down");
        }, null);
        String prompt = BotThreads.threadPrompt(ann(), bob(), BotThreads.Topic.GOSSIP, null, "Henesys");
        assertFalse(prompt.contains("shared history"));
        assertTrue(prompt.contains("Ann (level 30) and Bob (level 31)"));
    }

    @Test
    void theBankOpensAGossipThreadWithARememberWhenLineWhenThereIsNoStory() {
        aboutBob.add(note(bob().getId(), Kind.HELP, "came to help me train", 0.5, 0));
        List<String[]> lines = BotThreads.fallback(BotThreads.Topic.GOSSIP, ann(), bob(), null, new Random(1));
        assertEquals("Ann", lines.get(0)[0]);
        assertEquals("remember when u came to help me train lol", lines.get(0)[1]);
        // no memory: the bank as before (whatever it says, it is not a memory)
        aboutBob.clear();
        List<String[]> plain = BotThreads.fallback(BotThreads.Topic.GOSSIP, ann(), bob(), null, new Random(1));
        assertTrue(plain.isEmpty() || !plain.get(0)[1].startsWith("remember when u"), plain.toString());
    }

    @Test
    void aSourStillRemembersASourMemory() {
        aboutBob.add(note(bob().getId(), Kind.ASKED, "turned me down for a party", -0.4, 0));
        assertEquals("ngl i still remember when u turned me down for a party",
                PartnerMemory.callback(ann(), bob(), clock.get()));
    }

    @Test
    void hearsayAndSmallTalkAreNeverACallback() {
        aboutBob.add(new Note(0, 1, bob().getId(), "heard they killed Zakum", Source.HEARSAY, 3, 1, clock.get(), 0, Kind.NOTE,
                2, "", 0));
        aboutBob.add(note(bob().getId(), Kind.CHAT, "chatted", 0.2, 0));
        assertEquals(null, PartnerMemory.callback(ann(), bob(), clock.get()));
    }

    // ── BotTalk ──

    @Test
    void aBotTalkPromptCarriesTheOpenersAndTheFirstListenersMemories() {
        aboutBob.add(note(bob().getId(), Kind.GIFT, "gave me a potion", 0.6, 0));
        Plan plan = plan(ann(), bob());
        String prompt = BotTalk.prompt(plan, "Henesys");
        assertTrue(prompt.contains("Ann remembers Bob: gave me a potion (felt good)"), prompt);
        assertTrue(prompt.contains("Name no player who is not in this list: Ann + Bob"), prompt);
        aboutBob.clear();
        assertFalse(BotTalk.prompt(plan, "Henesys").contains("shared history"));
    }

    @Test
    void aGreetingBetweenBotsWithASharedPastCanOpenWithIt() {
        aboutBob.add(note(bob().getId(), Kind.PARTY, "partied with me", 0.4, 0));
        Plan plan = plan(ann(), bob());
        Step hello = step(plan, ann(), bob(), TalkIntent.greet(Manner.WARM, bob().getId()), "hey");
        int swapped = 0;
        for (long seed = 0; seed < 40; seed++) { // the draw is per speaker and seeded: some greetings use it, some do not
            SocialSim.useForTest(clock::get, seed);
            hello.text = "hey";
            hello.source = "bank";
            if (PartnerMemory.greetFromMemory(plan, clock.get())) {
                swapped++;
                assertEquals("remember when u partied with me lol", hello.text);
                assertEquals("memory", hello.source);
            } else {
                assertEquals("hey", hello.text, "an unswapped greeting is untouched");
            }
        }
        assertTrue(swapped > 5 && swapped < 35, "about half the time: " + swapped);
    }

    @Test
    void noMemoryARudeGreetingOrAnAskIsNeverSwapped() {
        Plan plan = plan(ann(), bob());
        Step hello = step(plan, ann(), bob(), TalkIntent.greet(Manner.WARM, bob().getId()), "hey");
        Step rude = step(plan, bob(), ann(), TalkIntent.greet(Manner.RUDE, ann().getId()), "sup");
        Step ask = step(plan, ann(), bob(), TalkIntent.ask(Ask.PARTY, Manner.WARM, bob().getId(), "KPQ"), "party?");
        for (long seed = 0; seed < 20; seed++) {
            SocialSim.useForTest(clock::get, seed);
            assertFalse(PartnerMemory.greetFromMemory(plan, clock.get()), "nothing remembered");
        }
        aboutBob.add(note(bob().getId(), Kind.PARTY, "partied with me", 0.4, 0));
        aboutAnn.add(note(ann().getId(), Kind.PARTY, "partied with me", 0.4, 0));
        plan.steps.remove(hello);
        for (long seed = 0; seed < 20; seed++) {
            SocialSim.useForTest(clock::get, seed);
            assertFalse(PartnerMemory.greetFromMemory(plan, clock.get()), "a rude greeting and an ask stay as planned");
        }
        assertEquals("sup", rude.text);
        assertEquals("party?", ask.text);
    }

    // ── what code writes, and what the model cannot ──

    private FakeMemoryTable memory() {
        FakeMemoryTable m = new FakeMemoryTable(false);
        m.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        BotEpisodes.useForTest(Runnable::run, map -> null, null);
        return m;
    }

    @Test
    void anActThatReallyHappenedLeavesAMemoryInBothBotsAndTheNextPromptCarriesIt() {
        FakeMemoryTable m = memory();
        PartnerMemory.useForTest(null, map -> null); // the real source: what is in memory
        BotTalk.Live live = new BotTalk.Live(7, plan(ann(), bob()), 1, 101000000, null, "TALK_PARTY");
        BotTalk.rememberAct(live, Ask.PARTY, ann(), bob(), clock.get());
        Note annOfBob = BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId()).get(0);
        assertEquals(Kind.PARTY, annOfBob.kind());
        assertEquals("partied with me", annOfBob.text());
        assertEquals(BotMemory.SubjectType.BOT, annOfBob.subjectType());
        assertEquals(101000000, annOfBob.locationMapId());
        assertTrue(annOfBob.valence() > 0);
        assertEquals(Provenance.WITNESSED, annOfBob.provenance());
        assertEquals("partied with me", BotMemory.about(bob().getId() - BotStore.BOT_ID_BASE, ann().getId()).get(0).text());
        assertEquals(2, m.rows.size());

        // the pair is warm now, so the next conversation of the two has it in its prompt
        String prompt = BotThreads.threadPrompt(ann(), bob(), BotThreads.Topic.GOSSIP, null, "Henesys");
        assertTrue(prompt.contains("Ann remembers Bob: partied with me"), prompt);
        assertEquals("remember when u partied with me lol",
                PartnerMemory.callback(ann(), bob(), clock.get() + 1000));
    }

    @Test
    void everyAskKindLeavesItsOwnNoteWithoutNamingAnyone() {
        FakeMemoryTable m = memory();
        BotTalk.Live live = new BotTalk.Live(7, plan(ann(), bob()), 1, 100000000, null, "TALK");
        for (Ask ask : Ask.values()) {
            BotTalk.rememberAct(live, ask, ann(), bob(), clock.get());
        }
        List<Note> annOfBob = BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId());
        assertEquals(java.util.Set.of("partied with me", "traded with me", "came to help me train", "came along with me",
                "listened to my gossip"), annOfBob.stream().map(Note::text).collect(java.util.stream.Collectors.toSet()),
                "one note for each kind of act");
        for (Note n : annOfBob) {
            assertTrue(NameGuard.check(n.text(), java.util.Set.of("Ann", "Bob")).clean(), n.text());
            assertFalse(n.text().contains("Bob") || n.text().contains("Ann"), n.text());
        }
        assertTrue(annOfBob.stream().anyMatch(n -> n.text().equals("listened to my gossip")),
                "a gossip pass is remembered as gossip, never as the story: " + annOfBob);
    }

    @Test
    void aRepeatedActFoldsIntoOneNoteInsteadOfFillingThePair() {
        FakeMemoryTable m = memory();
        BotTalk.Live live = new BotTalk.Live(7, plan(ann(), bob()), 1, 100000000, null, "TALK");
        for (int day = 0; day < 6; day++) {
            BotTalk.rememberAct(live, Ask.TRADE, ann(), bob(), clock.get() + day * DAY);
        }
        List<Note> notes = BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId());
        assertEquals(1, notes.size());
        assertEquals("traded with me (x6)", notes.get(0).text());
        assertTrue(notes.get(0).weight() > 3);
        assertEquals(2, m.rows.size(), "one note each way, six trades");
    }

    @Test
    void aTurnedDownAskIsRememberedByTheAskerAndTheAskedAndAYesIsLeftToTheAct() {
        FakeMemoryTable m = memory();
        Plan p = plan(ann(), bob());
        p.ask = Ask.PARTY;
        p.decisions.put(bob().getId(), new Decision(Answer.NO, Manner.RUDE, "busy"));
        BotTalk.Live live = new BotTalk.Live(7, p, 1, 100000000, null, "TALK_PARTY");
        BotTalk.rememberTalk(live, p, ann(), bob());
        Note byAsker = BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId()).get(0);
        assertEquals(Kind.ASKED, byAsker.kind());
        assertEquals("turned me down for a party", byAsker.text());
        assertEquals(-0.4, byAsker.valence(), 1e-6, "a rude no stings more");
        assertEquals("asked me for a party", BotMemory.about(bob().getId() - BotStore.BOT_ID_BASE, ann().getId()).get(0).text());

        p.decisions.put(bob().getId(), new Decision(Answer.YES, Manner.WARM, "ok"));
        int before = m.rows.size();
        BotTalk.rememberTalk(live, p, ann(), bob());
        assertEquals(before, m.rows.size(), "a yes is remembered when the act really happens");
    }

    @Test
    void helloAndChatAreSmallMemoriesThatMerge() {
        FakeMemoryTable m = memory();
        Plan greet = new Plan(List.of(ann(), bob()), false, true);
        BotTalk.Live live = new BotTalk.Live(7, greet, 1, 100000000, null, "GREET");
        BotTalk.rememberTalk(live, greet, ann(), bob());
        assertEquals("said hi", BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId()).get(0).text());
        Plan chat = plan(ann(), bob());
        BotTalk.Live talk = new BotTalk.Live(8, chat, 1, 100000000, null, "CHAT");
        BotTalk.rememberTalk(talk, chat, ann(), bob());
        assertEquals(2, BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId()).size(), "a hello and a chat");
        assertEquals(Kind.CHAT, BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId()).get(0).kind());
    }

    @Test
    void theModelCannotWriteAMemory() {
        FakeMemoryTable m = memory();
        Plan p = plan(ann(), bob());
        step(p, ann(), bob(), TalkIntent.greet(Manner.WARM, bob().getId()), "hey");
        int kept = BotTalk.reword(p, "Ann: Bob saved my life and we are best friends forever now");
        BotThreads.parse("Ann: bob gave me a rare sword\nBob: we fought last week and i won", "Ann", "Bob");
        assertEquals(0, m.rows.size(), "model words never reach botmemory (kept " + kept + " lines)");
        assertTrue(BotMemory.about(ann().getId() - BotStore.BOT_ID_BASE, bob().getId()).isEmpty());
        assertTrue(BotMemory.about(bob().getId() - BotStore.BOT_ID_BASE, ann().getId()).isEmpty());
    }

    // ── helpers ──

    private static Plan plan(Character a, Character b) {
        return new Plan(List.of(a, b), false, false);
    }

    private static Step step(Plan plan, Character speaker, Character to, TalkIntent intent, String text) {
        Step s = new Step(speaker, to, intent, new TalkLines.Worded(text, "bank"));
        plan.steps.add(s);
        return s;
    }
}
