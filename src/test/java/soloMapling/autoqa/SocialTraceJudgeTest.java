package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.autoqa.SocialTraceEntry.Bond;
import soloMapling.autoqa.SocialTraceEntry.Event;
import soloMapling.autoqa.SocialTraceEntry.Line;
import soloMapling.autoqa.SocialTraceEntry.Stage;
import soloMapling.autoqa.SocialTraceEntry.Status;
import soloMapling.autoqa.SocialTraceEntry.Verdict;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 (owner 2026-10-26): the social trace's stage rules, line reader, window closing and summary. */
class SocialTraceJudgeTest {

    static final long T0 = 1_793_000_000_000L;
    static final int A = 1_000_000_001, B = 1_000_000_002, C = 1_000_000_003;
    static final SocialTraceJudge.Rules RULES = new SocialTraceJudge.Rules(
            line -> line.toLowerCase().contains("i am a bot") ? "ai talk" : null, ChatQuality::stiffFlags);

    static SocialTraceEntry thread(String topic, String about) {
        SocialTraceEntry e = new SocialTraceEntry("t-" + topic + "-" + T0, "thread", topic, T0, 100000000, 1, A, "Shroom93", B,
                "LycanZelda", List.of("AutoQaThree (observer)"));
        e.trigger = "topic roll";
        e.about = about;
        e.minLevel = 25;
        e.closesAt = SocialTraceJudge.closesAt(T0, false);
        return e;
    }

    static void say(SocialTraceEntry e, long offset, int id, String text) {
        e.lines.add(new Line(T0 + offset, id, e.nameOf(id), text, "lower"));
    }

    static EnumMap<Stage, Verdict> judge(SocialTraceEntry e) {
        return SocialTraceJudge.judge(e, RULES);
    }

    static SocialTraceEntry goodLfg() {
        SocialTraceEntry e = thread("LFG", "Kerning PQ");
        e.source = "bank";
        e.fallback = "the model is not live";
        e.planned = 2;
        say(e, 3_000, A, "anyone for kpq? need 2 more");
        say(e, 6_000, B, "ya im down, lv 25");
        e.before = new Bond(10, 8, "neutral", "neutral");
        e.after = new Bond(13, 11, "neutral", "neutral");
        return e;
    }

    // ── the first three stages (t-st-1) ──

    @Test
    void aBankThreadThatSaidBothLinesPassesTriggerComposeAndDeliver() {
        EnumMap<Stage, Verdict> v = judge(goodLfg());
        assertEquals(Status.OK, v.get(Stage.TRIGGER).status());
        assertEquals(Status.OK, v.get(Stage.COMPOSE).status());
        assertTrue(v.get(Stage.COMPOSE).reason().contains("not live"), v.get(Stage.COMPOSE).reason());
        assertEquals(Status.OK, v.get(Stage.DELIVER).status(), v.get(Stage.DELIVER).reason());
        assertEquals(Status.OK, v.get(Stage.SENSE).status(), v.get(Stage.SENSE).reason());
    }

    @Test
    void aThreadNobodyWatchedFailsItsTrigger() {
        SocialTraceEntry e = new SocialTraceEntry("t2", "thread", "GOSSIP", T0, 1, 1, A, "a", B, "b", List.of());
        e.trigger = "topic roll";
        assertEquals(Status.FAIL, judge(e).get(Stage.TRIGGER).status());
    }

    @Test
    void composeFailsWhenTheModelNeverAnsweredOrItsReplyWasUnusable() {
        SocialTraceEntry never = thread("TRADE", null);
        assertEquals(Status.FAIL, judge(never).get(Stage.COMPOSE).status());
        assertEquals(Status.FAIL, judge(never).get(Stage.DELIVER).status());

        SocialTraceEntry unusable = goodLfg();
        unusable.modelLines = 1;
        Verdict v = judge(unusable).get(Stage.COMPOSE);
        assertEquals(Status.FAIL, v.status());
        assertTrue(v.reason().startsWith("model reply unusable (1 usable line(s))"), v.reason());

        SocialTraceEntry llm = goodLfg();
        llm.source = "llm";
        llm.fallback = null;
        llm.composeMs = 2400;
        assertEquals("llm in 2400 ms", judge(llm).get(Stage.COMPOSE).reason());
    }

    @Test
    void deliverFailsOnOneLineAFilterHitARepeatOrACutOffLine() {
        SocialTraceEntry one = thread("LFG", "Kerning PQ");
        one.source = "bank";
        one.planned = 2;
        say(one, 1000, A, "kpq anyone");
        one.dropped.add("say-do guard: a promise nobody keeps (LycanZelda: \"omw\")");
        Verdict v = judge(one).get(Stage.DELIVER);
        assertEquals(Status.FAIL, v.status());
        assertTrue(v.reason().startsWith("only 1 line said (say-do guard"), v.reason());

        SocialTraceEntry ai = goodLfg();
        say(ai, 9_000, A, "lol i am a bot");
        assertTrue(judge(ai).get(Stage.DELIVER).reason().startsWith("filter (ai talk)"));

        SocialTraceEntry twice = goodLfg();
        say(twice, 9_000, A, "Anyone for KPQ? need 2 more!");
        assertTrue(judge(twice).get(Stage.DELIVER).reason().startsWith("said twice"));

        SocialTraceEntry world = goodLfg();
        world.repeats.add("anyone for kpq? need 2 more");
        assertTrue(judge(world).get(Stage.DELIVER).reason().startsWith("repeat of an earlier thread line"));

        SocialTraceEntry cut = goodLfg();
        say(cut, 9_000, B, "ok meet me at the");
        assertTrue(judge(cut).get(Stage.DELIVER).reason().startsWith("looks cut off"));
    }

    // ── SENSE ──

    @Test
    void senseFlagsOffTopicLinesMissingStoryPeopleAndLongLines() {
        SocialTraceEntry off = thread("TRADE", null);
        off.source = "bank";
        off.planned = 2;
        say(off, 1000, A, "nice weather today");
        say(off, 2000, B, "ya true");
        assertTrue(judge(off).get(Stage.SENSE).reason().startsWith("off topic"), judge(off).get(Stage.SENSE).reason());

        SocialTraceEntry gossip = thread("GOSSIP", "Gm ksed Nayrb");
        gossip.factPerson = true;
        gossip.factNames = List.of("Nayrb");
        gossip.source = "llm";
        gossip.planned = 2;
        say(gossip, 1000, A, "did u hear some guy ksed someone");
        say(gossip, 2000, B, "lol wild");
        assertTrue(judge(gossip).get(Stage.SENSE).reason().startsWith("the story's people are never named"));

        SocialTraceEntry longLine = goodLfg();
        say(longLine, 9_000, B, "kpq kpq kpq kpq kpq kpq kpq kpq kpq kpq kpq kpq kpq kpq kpq kpq");
        assertTrue(judge(longLine).get(Stage.SENSE).reason().startsWith("long line"));
    }

    @Test
    void anLfgLabelOutsideThePqLobbyBandsIsFlagged() {
        SocialTraceEntry e = goodLfg();
        e.minLevel = 32;
        e.about = "Ludi PQ"; // BotThreads.about labels level 31-50 Ludi; LifePq's Ludi lobby takes 35-50
        assertEquals("LFG for Ludi PQ at level 32: no PQ lobby takes that level", SocialTraceJudge.lfgBand(e));
        e.minLevel = 40;
        assertNull(SocialTraceJudge.lfgBand(e));
    }

    @Test
    void anLfgLabelIsJudgedOnTheRecruitersLevel() {
        SocialTraceEntry e = goodLfg();
        e.minLevel = 25; // the other bot's level does not pick the PQ
        e.aLevel = 71;
        e.about = "Kerning PQ";
        assertEquals("LFG for Kerning PQ at level 71: no PQ lobby takes that level", SocialTraceJudge.lfgBand(e));
        e.aLevel = 45;
        e.about = "Ludi PQ";
        assertNull(SocialTraceJudge.lfgBand(e));
        e.aLevel = 60;
        assertEquals("LFG for Ludi PQ at level 60 (fits Orbis PQ)", SocialTraceJudge.lfgBand(e));
    }

    // ── DECIDE / ACT ──

    @Test
    void anLfgThreadDecidesNothingInCodeButItsIntentIsRecorded() {
        EnumMap<Stage, Verdict> v = judge(goodLfg());
        assertEquals(Status.NONE, v.get(Stage.DECIDE).status());
        assertTrue(v.get(Stage.DECIDE).reason().contains("no answer decided"), v.get(Stage.DECIDE).reason());
        assertEquals("plan a party for Kerning PQ", v.get(Stage.DECIDE).data().get("intent"));
        assertEquals(Status.NONE, v.get(Stage.ACT).status());
    }

    @Test
    void anLfgYesDecidesAPartyAndActsWhenBothHeadToThePqInsideTheWindow() {
        SocialTraceEntry e = goodLfg();
        e.lfgDecision = "party up for Kerning PQ";
        e.events.add(new Event(T0 + 9_000, A, "party", "LFG party with LycanZelda for Kerning PQ"));
        EnumMap<Stage, Verdict> v = judge(e);
        assertEquals(Status.OK, v.get(Stage.DECIDE).status());
        assertEquals("party up for Kerning PQ", v.get(Stage.DECIDE).reason());
        assertEquals(Status.FAIL, v.get(Stage.ACT).status(), "a party alone is not the trip");

        e.events.add(new Event(T0 + 20_000, A, "lfg",
                "LFG TRIP Shroom93 + LycanZelda -> Kerning PQ: both on their way, one party"));
        Verdict act = judge(e).get(Stage.ACT);
        assertEquals(Status.OK, act.status(), act.reason());
        assertTrue(act.reason().startsWith("lfg after 20 s"), act.reason());
    }

    @Test
    void anLfgTripAfterTheWindowOrAFailedPartyIsAFail() {
        SocialTraceEntry e = goodLfg();
        e.lfgDecision = "party up for Kerning PQ";
        e.lfgFail = "LycanZelda already in a party";
        e.events.add(new Event(T0 + SocialTraceJudge.ACT_WINDOW_MS + 1, A, "lfg", "LFG TRIP late"));
        Verdict act = judge(e).get(Stage.ACT);
        assertEquals(Status.FAIL, act.status());
        assertTrue(act.reason().contains("party up for Kerning PQ: LycanZelda already in a party"), act.reason());
    }

    @Test
    void anLfgNoIsADecisionNotToPartyWithNothingToCarryOut() {
        SocialTraceEntry e = goodLfg();
        e.lfgDecline = "LycanZelda too low for the PQ";
        EnumMap<Stage, Verdict> v = judge(e);
        assertEquals(Status.NA, v.get(Stage.DECIDE).status());
        assertEquals("no party: LycanZelda too low for the PQ", v.get(Stage.DECIDE).reason());
        assertEquals(Status.NA, v.get(Stage.ACT).status());
    }

    @Test
    void everyThreadTopicHasANoPathReason() {
        for (String t : List.of("LFG", "TRADE", "TRAINING", "GOSSIP", "RIVALRY", "GUILD_BEEF", "FRIEND_DRAMA")) {
            assertNotNull(SocialTraceJudge.THREAD_NO_PATH.get(t), t);
        }
    }

    @Test
    void aTradeThreadsIntentNamesTheItem() {
        SocialTraceEntry e = thread("TRADE", null);
        say(e, 1000, A, "S> work gloves 500k");
        assertEquals("offer work gloves", SocialTraceJudge.intent(e));
    }

    @Test
    void aTradeThreadWithADealDecidesInCodeAndActsWhenTheTradeWentThrough() {
        SocialTraceEntry none = thread("TRADE", null);
        assertEquals(Status.NONE, judge(none).get(Stage.DECIDE).status(), "no deal planned: nothing decided");

        SocialTraceEntry deal = thread("TRADE", "Shroom93 sells Work Gloves for 95k");
        deal.decided = "sell Work Gloves for 95,000";
        EnumMap<Stage, Verdict> v = judge(deal);
        assertEquals(Status.OK, v.get(Stage.DECIDE).status());
        assertEquals("sell Work Gloves for 95,000", v.get(Stage.DECIDE).reason());
        assertEquals(Status.FAIL, v.get(Stage.ACT).status(), "agreed but no trade happened");
        deal.events.add(new Event(T0 + 20_000, A, "trade_failed", "the seller no longer has it"));
        assertEquals(Status.FAIL, judge(deal).get(Stage.ACT).status(), "a failed swap is not the act");
        deal.events.add(new Event(T0 + 30_000, A, "trade", "sold Work Gloves to LycanZelda for 95,000"));
        Verdict act = judge(deal).get(Stage.ACT);
        assertEquals(Status.OK, act.status());
        assertTrue(act.reason().startsWith("trade after 30 s"), act.reason());

        SocialTraceEntry no = thread("TRADE", "Shroom93 sells Work Gloves for 95k");
        no.decided = "no deal";
        v = judge(no);
        assertEquals(Status.OK, v.get(Stage.DECIDE).status());
        assertEquals("no deal", v.get(Stage.DECIDE).reason());
        assertEquals(Status.NA, v.get(Stage.ACT).status(), "no deal: nothing to carry out");
    }

    @Test
    void actRecordsTheNextLifeDecisionsEvenWithNoDecision() {
        SocialTraceEntry e = goodLfg();
        e.events.add(new Event(T0 + 60_000, A, "life", "DECISION selected=GRIND (CHOOSE)"));
        e.events.add(new Event(T0 + 90_000, B, "party", "joined a party with Shroom93"));
        Verdict v = judge(e).get(Stage.ACT);
        assertEquals(Status.NONE, v.status());
        assertTrue(v.reason().contains("seen anyway: party: joined a party with Shroom93"), v.reason());
        assertEquals(Map.of("Shroom93", List.of("DECISION selected=GRIND (CHOOSE)")), v.data().get("nextLife"));
    }

    @Test
    void aKsSpatIsCarriedOutWhenTheKserAnswersInsideTheWindowOnly() {
        SocialTraceEntry e = new SocialTraceEntry("d1", "drama", "KS_SPAT", T0, 104040000, 1, A, "Iceninja", B,
                "HorntailRed", List.of());
        e.trigger = "a rival KSed it";
        e.planned = 1;
        say(e, 0, A, "hey thats my mob");
        assertEquals(Status.OK, judge(e).get(Stage.DECIDE).status());
        assertEquals(Status.FAIL, judge(e).get(Stage.ACT).status());
        e.events.add(new Event(T0 + SocialTraceJudge.ACT_WINDOW_MS + 1, B, "answer", "too late"));
        assertEquals(Status.FAIL, judge(e).get(Stage.ACT).status());
        e.events.add(new Event(T0 + 2_000, B, "answer", "not ur mob noob"));
        Verdict v = judge(e).get(Stage.ACT);
        assertEquals(Status.OK, v.status());
        assertEquals("answer after 2 s: not ur mob noob", v.reason());
    }

    // ── REMEMBER / SPREAD ──

    @Test
    void rememberWantsTheBondToMove() {
        assertEquals(Status.OK, judge(goodLfg()).get(Stage.REMEMBER).status());
        SocialTraceEntry still = goodLfg();
        still.after = still.before;
        assertTrue(judge(still).get(Stage.REMEMBER).reason().startsWith("bond unchanged"));
    }

    @Test
    void aThirdBotRepeatingTheStorySpreadsIt() {
        SocialTraceEntry e = thread("GOSSIP", "Gm ksed Nayrb");
        e.spreadKey = "Gm ksed Nayrb";
        e.spreadNames = List.of("Gm", "Nayrb");
        assertFalse(SocialTraceJudge.repeats(e, A, "Gm ksed Nayrb", List.of("Gm", "Nayrb")), "a teller of its own");
        assertTrue(SocialTraceJudge.repeats(e, C, "Gm ksed Nayrb", List.of()));
        assertTrue(SocialTraceJudge.repeats(e, C, "Nayrb got ksed by Gm again", List.of("nayrb", "gm")));
        assertFalse(SocialTraceJudge.repeats(e, C, "prices are up", List.of("Nayrb")));
        assertEquals(Status.FAIL, judge(e).get(Stage.SPREAD).status());
        e.spread.add("x told y");
        Verdict independent = judge(e).get(Stage.SPREAD);
        assertEquals(Status.FAIL, independent.status(), "a GOSSIP story spreads only when someone retells what it heard");
        assertTrue(independent.reason().contains("only an independent telling: x told y"), independent.reason());
        e.retold.add("LycanZelda told Cid (heard from Shroom93): Gm ksed Nayrb");
        Verdict retold = judge(e).get(Stage.SPREAD);
        assertEquals(Status.OK, retold.status());
        assertEquals("retold: LycanZelda told Cid (heard from Shroom93): Gm ksed Nayrb", retold.reason());
        assertEquals(Status.NA, judge(goodLfg()).get(Stage.SPREAD).status());

        SocialTraceEntry drama = thread("FRIEND_DRAMA", "Cid rage quit");
        drama.spreadKey = "Cid rage quit";
        drama.spread.add("x told y");
        assertEquals(Status.OK, judge(drama).get(Stage.SPREAD).status(), "other topics still count a repeat");
    }

    // ── 1.6.0: GOSSIP keeps the story, RIVALRY escalates, threads note "we talked" ──

    @Test
    void aGossipListenerKeepingTheStoryIsTheDecisionAndKeptIsItsAct() {
        SocialTraceEntry e = thread("GOSSIP", "Gm ksed Nayrb");
        e.factAbout = "Gm ksed Nayrb";
        EnumMap<Stage, Verdict> before = judge(e);
        assertEquals(Status.NONE, before.get(Stage.DECIDE).status());
        assertEquals(SocialTraceJudge.THREAD_NO_PATH.get("GOSSIP"), before.get(Stage.DECIDE).reason());

        e.decided = "listener keeps the story";
        assertEquals(Status.OK, judge(e).get(Stage.DECIDE).status());
        assertEquals("story now known by LycanZelda: Gm ksed Nayrb", judge(e).get(Stage.DECIDE).data().get("intent"));
        assertEquals(Status.FAIL, judge(e).get(Stage.ACT).status());
        e.events.add(new Event(T0 + 9_000, B, "kept", "LycanZelda heard from Shroom93: Gm ksed Nayrb (hop 1)"));
        Verdict act = judge(e).get(Stage.ACT);
        assertEquals(Status.OK, act.status());
        assertTrue(act.reason().startsWith("kept after 9 s"), act.reason());

        SocialTraceEntry small = thread("GOSSIP", "prices in the FM");
        small.decideWhy = "the listener keeps nothing: small talk, nobody in it";
        assertEquals(Status.NONE, judge(small).get(Stage.DECIDE).status());
        assertEquals(small.decideWhy, judge(small).get(Stage.DECIDE).reason());
    }

    @Test
    void aSkippedTipOrDealSaysWhichCheck() {
        // TEST 2026-10-27: 5 of 5 TRAINING and 1 of 1 TRADE gave the table's generic reason
        SocialTraceEntry tip = thread("TRAINING", "good training spots around level 42");
        assertEquals(SocialTraceJudge.THREAD_NO_PATH.get("TRAINING"), judge(tip).get(Stage.DECIDE).reason());
        tip.decideWhy = "no tip followed: Shroomkid hears Path of Time: not a training bot";
        assertEquals(Status.NONE, judge(tip).get(Stage.DECIDE).status());
        assertEquals(tip.decideWhy, judge(tip).get(Stage.DECIDE).reason());
        SocialTraceEntry trade = thread("TRADE", null);
        trade.decideWhy = "no deal was planned: neither holds an ETC stack, potions or food worth 1k-300k to sell";
        assertEquals(trade.decideWhy, judge(trade).get(Stage.DECIDE).reason());
    }

    @Test
    void aRivalryEscalationIsDecidedThenCarriedOutOrDroppedWithItsReason() {
        SocialTraceEntry no = thread("RIVALRY", "Shroom93 has beef with LycanZelda");
        no.decideWhy = "no escalation: the roll said no (chance 0.12)";
        assertEquals(Status.NONE, judge(no).get(Stage.DECIDE).status());
        assertEquals(no.decideWhy, judge(no).get(Stage.DECIDE).reason());

        SocialTraceEntry e = thread("RIVALRY", "Shroom93 has beef with LycanZelda");
        e.decided = "escalate: Shroom93 goes after LycanZelda in 240 s";
        assertEquals(Status.OK, judge(e).get(Stage.DECIDE).status());
        e.events.add(new Event(T0 + 240_000, A, "escalate-drop", "no real player can see the rival's map"));
        Verdict dropped = judge(e).get(Stage.ACT);
        assertEquals(Status.FAIL, dropped.status());
        assertTrue(dropped.reason().endsWith(": no real player can see the rival's map"), dropped.reason());
        e.events.add(new Event(T0 + 300_000, A, "escalate", "came to smack-talk LycanZelda on 100000000: not u"));
        assertEquals(Status.OK, judge(e).get(Stage.ACT).status());
    }

    @Test
    void rememberNotesTheTalkedEpisode() {
        SocialTraceEntry e = goodLfg();
        Verdict silent = judge(e).get(Stage.REMEMBER);
        assertTrue(silent.data().containsKey("memory"), "no talked episode is said so");
        e.remembered.add("talked: Shroom93 + LycanZelda (no life memory)");
        Verdict v = judge(e).get(Stage.REMEMBER);
        assertEquals(Status.OK, v.status());
        assertTrue(v.reason().endsWith("; talked: Shroom93 + LycanZelda (no life memory)"), v.reason());
        assertEquals(List.of("talked: Shroom93 + LycanZelda (no life memory)"), v.data().get("written"));
    }

    @Test
    void aRetoldStoryIsCreditedToTheOpenGossipEntryOnly() {
        SocialTrace.resetForTest();
        SocialTrace.onForTest(true);
        try {
            SocialTraceEntry gossip = thread("GOSSIP", "Gm ksed Nayrb");
            gossip.spreadKey = "Gm ksed Nayrb";
            SocialTraceEntry rival = new SocialTraceEntry("t-r", "thread", "RIVALRY", T0, 1, 1, A, "Shroom93", C, "Cid",
                    List.of("x"));
            rival.spreadKey = "Gm ksed Nayrb";
            SocialTrace.add(gossip);
            SocialTrace.add(rival);
            SocialTrace.storyRetold(A, "Shroom93", "Cid", "Gm ksed Nayrb", "someone");
            assertTrue(gossip.retold.isEmpty(), "the original teller telling it again is no spread");
            SocialTrace.storyRetold(B, "LycanZelda", "Cid", "gm KSED nayrb", "Shroom93");
            assertEquals(List.of("LycanZelda told Cid (heard from Shroom93): gm KSED nayrb"), gossip.retold);
            assertTrue(rival.retold.isEmpty());
        } finally {
            SocialTrace.resetForTest();
        }
    }

    // ── the drama lines (formats from TEST logs, 2026-10-26) ──

    @Test
    void theDramaLinesAreRead() {
        var spat = SocialTraceLines.read("KS SPAT Iceninja -> HorntailRed (rivals): hey thats my mob");
        assertEquals(SocialTraceLines.Role.START, spat.role());
        assertEquals("KS_SPAT", spat.kind());
        assertEquals(List.of("Iceninja", "HorntailRed"), spat.names());
        assertEquals("a rival KSed it", spat.why());
        var back = SocialTraceLines.read("KS SPAT BACK HorntailRed -> Iceninja: not ur mob");
        assertEquals(SocialTraceLines.Role.ANSWER, back.role());
        assertEquals(List.of("Iceninja", "HorntailRed"), back.names());
        assertEquals("answer", back.why());
        assertEquals("CALLOUT", SocialTraceLines.read("CALLOUT Zombiechan on dkk3 (nemesis): megaphone sent").kind());
        assertEquals("GUILD_FEUD", SocialTraceLines.read("GUILD FEUD Legendz: a1 vs b2 (fight 2, nemesis)").kind());
        assertEquals("leave", SocialTraceLines.read("GUILD FEUD LEAVE a1 left Legendz").kind());
        var poach = SocialTraceLines.read("POACH FoxLover: MoonLiteX -> SlumLords (megaphone)");
        assertEquals(List.of("FoxLover", "[SlumLords]"), poach.names());
        var jab = SocialTraceLines.read("JAB IchigoPika [SlumLords] -> [MoonLiteX]: FoxLover ur guild is a joke lol");
        assertEquals("JAB", jab.kind());
        assertEquals("FoxLover ur guild is a joke lol", jab.text());
        var guildParty = SocialTraceLines.read("PARTY MoonLiteX: iinoobz + FoxLover + D3vilPanda");
        assertEquals(SocialTraceLines.Role.EVENT, guildParty.role());
        assertEquals(List.of("iinoobz", "FoxLover", "D3vilPanda"), guildParty.names());
        var coupleParty = SocialTraceLines.read("PARTY a1 + b2: true");
        assertEquals(List.of("a1", "b2"), coupleParty.names());
        assertNull(SocialTraceLines.read("PARTY a1 + b2: false"));
        assertEquals(List.of(1000000005, 1000000009),
                SocialTraceLines.read("FORMED couple #12: 1000000005 + 1000000009").ids());
        assertEquals("JEALOUS", SocialTraceLines.read("COUPLE JEALOUS a1 at c3 (likes b2): back off; FIGHT a1 -> b2: ugh").kind());
        assertEquals("BREAKUP", SocialTraceLines.read("COUPLE BREAKUP a1 + b2: were done (megaphone gated)").kind());
        assertEquals("REBOUND", SocialTraceLines.read("REBOUND a1 is dating b2 now").kind());
        assertEquals("CRUSH", SocialTraceLines.read("CRUSH START a1 -> b2").kind());
        assertEquals(SocialTraceLines.Role.ANSWER, SocialTraceLines.read("CRUSH TO COUPLE #3: a1 + b2").role());
        assertEquals(SocialTraceLines.Role.NEWS, SocialTraceLines.read("NEWS rage_quit Zombiechan (maple sword)").role());
        assertEquals("TEASE", SocialTraceLines.read("TEASE boom friend a1 -> b2: rip ur sword").kind());
        assertNull(SocialTraceLines.read("ROAST facts for AutoQaOne: level 30"));
    }

    // ── closing the window ──

    List<Map<String, Object>> written;

    @BeforeEach
    void quietTrace() {
        SocialTrace.resetForTest();
        written = new ArrayList<>();
        SocialTrace.sink = written::add;
        SocialTrace.endReader = e -> Map.of("sameParty", false);
        SocialTrace.rules = RULES;
    }

    @AfterEach
    void reset() {
        SocialTrace.resetForTest();
    }

    @Test
    void anEntryClosesOnlyAfterItsWindowAndIsWrittenJudged() {
        SocialTraceEntry lfg = goodLfg();
        SocialTraceEntry gossip = thread("GOSSIP", "Gm ksed Nayrb");
        gossip.spreadKey = "Gm ksed Nayrb";
        gossip.closesAt = SocialTraceJudge.closesAt(T0, true);
        SocialTrace.add(lfg);
        SocialTrace.add(gossip);
        assertEquals(0, SocialTrace.closeDue(T0 + SocialTraceJudge.ACT_WINDOW_MS - 1));
        assertEquals(1, SocialTrace.closeDue(T0 + SocialTraceJudge.ACT_WINDOW_MS));
        assertEquals(1, written.size());
        assertEquals(lfg.id, written.get(0).get("id"));
        Map<?, ?> stages = (Map<?, ?>) written.get(0).get("stages");
        assertEquals("none", ((Map<?, ?>) stages.get("DECIDE")).get("status"));
        assertTrue(SocialTrace.entriesOf(A).contains(gossip), "the gossip entry waits for its spread window");
        assertEquals(1, SocialTrace.closeDue(T0 + SocialTraceJudge.SPREAD_WINDOW_MS));
        assertTrue(SocialTrace.entriesOf(A).isEmpty(), "a closed entry leaves the index");
    }

    @Test
    void eventsReachOnlyOpenEntriesOfThatBot() {
        SocialTraceEntry e = goodLfg();
        SocialTrace.add(e);
        SocialTrace.onForTest(true);
        SocialTrace.life(A, "START activity=GRIND");
        SocialTrace.life(C, "START activity=GRIND");
        assertEquals(1, e.events.size());
        SocialTrace.closeDue(T0 + SocialTraceJudge.ACT_WINDOW_MS);
        SocialTrace.life(A, "START activity=RESTOCK");
        assertEquals(1, e.events.size());
    }

    @Test
    void theOpenEntriesAreBounded() {
        for (int i = 0; i < SocialTrace.MAX_OPEN + 5; i++) {
            SocialTraceEntry e = new SocialTraceEntry("x" + i, "drama", "TEASE", T0 + i, 1, 1, A + i, "a" + i, 0, "[g]",
                    List.of());
            e.planned = 0;
            e.closesAt = T0 + i + SocialTraceJudge.ACT_WINDOW_MS;
            SocialTrace.add(e);
        }
        assertEquals(SocialTrace.MAX_OPEN, SocialTrace.openForTest().size());
        assertEquals(5, written.size());
        assertEquals("cut short: too many open entries", written.get(0).get("cutShort"));
    }

    // ── the summary ──

    @Test
    void theSummaryCountsStagesAndRanksReasons() {
        SocialTrace.add(goodLfg());
        SocialTrace.add(new SocialTraceEntry("t9", "thread", "TRADE", T0, 1, 1, A, "a", B, "b", List.of("p")));
        SocialTrace.closeAll(T0 + 1, "test");
        Map<String, Object> s = SocialTraceSummary.build(written, Map.of(), T0);
        assertEquals(2, s.get("entries"));
        Map<?, ?> deliver = (Map<?, ?>) ((Map<?, ?>) s.get("stages")).get("DELIVER");
        assertEquals(1, deliver.get("ok"));
        assertEquals(1, deliver.get("fail"));
        assertEquals(0.5, deliver.get("okRate"));
        assertFalse(((List<?>) s.get("topNone")).isEmpty());
        assertTrue(((Map<?, ?>) s.get("examples")).containsKey("DELIVER fail"));
        assertEquals("said twice: \"…\"", SocialTraceSummary.reasonKey("said twice: \"lol ok\""));
    }

    // ── observers and control ──

    @Test
    void observersSpreadOverChannelsAndMapsWithTheirOwnSlots() {
        assertEquals(List.of(1, 2, 3, 1), List.of(SocialObservers.channelFor(0, 3), SocialObservers.channelFor(1, 3),
                SocialObservers.channelFor(2, 3), SocialObservers.channelFor(3, 3)));
        assertEquals(1, SocialObservers.channelFor(2, 2), "channel 3 down: channel 1");
        assertEquals(List.of(0, 2, 4, 6), List.of(SocialObservers.firstMapIndex(0), SocialObservers.firstMapIndex(1),
                SocialObservers.firstMapIndex(2), SocialObservers.firstMapIndex(3)));
        assertEquals("AutoQaThree", SyntheticPlayers.nameOf(3));
        assertEquals("AutoQaSix", SyntheticPlayers.nameOf(6));
        assertTrue(SyntheticPlayers.isObserverSlot(3) && !SyntheticPlayers.isObserverSlot(2));
        for (String n : SyntheticPlayers.OBSERVER_NAMES) {
            assertTrue(soloMapling.site.SiteFeed.isQaName(n) && n.length() <= 12, n);
        }
    }

    @Test
    void theBootPropertyIsHours() {
        assertEquals(0, SocialTrace.hoursProperty(null));
        assertEquals(9, SocialTrace.hoursProperty("9"));
        assertEquals(1.5, SocialTrace.hoursProperty("1.5"));
        assertEquals(SocialTrace.DEFAULT_HOURS, SocialTrace.hoursProperty("true"));
        assertEquals(0, SocialTrace.hoursProperty("-2"));
    }
}
