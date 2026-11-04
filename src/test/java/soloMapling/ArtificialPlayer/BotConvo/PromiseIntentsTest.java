package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotChatActions;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSocialSystem.IntentBook;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActKind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntent;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Presence;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Result;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.SocialGate;
import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 stage 5: dialogue to intention. A bot's yes that the server already acts on (a bot-talk plan's yes, a
 * player's request the gates agreed to) is also a persisted intention with the right kind and due time; the model's
 * words alone are not. Broken, kept, survives a save, at most three, opt-outs and the flag.
 */
@org.junit.jupiter.api.extension.ExtendWith(soloMapling.ArtificialPlayer.BotSocialSystem.SocialFlagsOff.class)
class PromiseIntentsTest {

    static final long MIN = TimeUnit.MINUTES.toMillis(1);
    static final int HENESYS = 100000000, ELLINIA = 101000000;
    static final AtomicInteger SLOT = new AtomicInteger(500);

    final AtomicLong clock = new AtomicLong(7_000_000_000_000L);
    final List<String> done = new CopyOnWriteArrayList<>();
    final List<String> kept = new ArrayList<>(), broken = new ArrayList<>(), performed = new ArrayList<>();
    final Map<Integer, Presence> online = new HashMap<>();
    final Map<Integer, TalkPromises.Where> where = new HashMap<>();
    boolean aim = true, performs = false;
    ConvoGates.Facts facts = ConvoGatesTest.base();

    @BeforeEach
    void setUp() {
        System.clearProperty(SocialIntents.SWITCH);
        SocialSim.useForTest(clock::get, 9);
        SocialIntents.resetForTest();
        SocialIntents.useForTest(new SocialIntents.World() {
            public Presence presence(int id) {
                return online.get(id);
            }

            public boolean reachable(int actorId, int mapId) {
                return true;
            }

            public boolean free(int actorId, long now) {
                return true;
            }

            public boolean mayAim(int actorId, int targetId, SocialActKind kind) {
                return aim;
            }

            public String name(int id) {
                return "n" + id;
            }
        }, new SocialIntents.Consequences() {
            public void kept(SocialIntent in, long now) {
                kept.add(in.kind().name());
            }

            public void broken(SocialIntent in, long now) {
                broken.add(in.kind().name());
            }
        }, (a, t, kind, now) -> {
            performed.add(kind.name());
            return new Result(performs, performs ? "done" : "not close");
        });
        TalkPromises.useForTest(where::get);
        ConvoActions.useForTest(new ConvoActions.Effects() {
            public void grindAt(BotSM bot, int mapId) {
                done.add("grind " + mapId);
            }

            public void walkTo(Character bot, int mapId, Character player) {
                done.add("walk " + mapId);
            }

            public String buff(Character bot, Character player, String line) {
                return "Haste";
            }

            public boolean buddy(int botCharId, int playerId) {
                return true;
            }

            public void affinity(Character bot, Character player, AffinityEvent event, int delta) {
            }
        }, (bot, player, action, read, line) -> facts);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SocialIntents.SWITCH);
        ConvoActions.useForTest(null, null);
        TalkPromises.useForTest(null);
        SocialIntents.resetForTest();
        SocialSim.reset();
    }

    /** A bot Character whose memory is loaded, as a roster bot's is. */
    private Character bot(String name) {
        Character c = TalkPlannerTest.chr(SLOT.incrementAndGet(), name, 40);
        LifeMemoryStore.of(c.getId());
        online.put(c.getId(), new Presence(HENESYS, 1));
        return c;
    }

    private static Character player(int id, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(name);
        c.setLevel(40);
        return c;
    }

    /** A real player as the gate sees one: a character whose client is not a bot's. */
    private static Character human(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getClient()).thenReturn(mock(client.Client.class));
        return c;
    }

    private static BotSM botSm(Character chr) {
        BotSM sm = mock(TrainingBot.class);
        when(sm.getChr()).thenReturn(chr);
        return sm;
    }

    // ── bot to bot ──

    @Test
    void aYesToPartyHelpOrComeAlongIsAnIntentForTheOneWhoSaidYesAndATradeOrAStoryIsNot() {
        Character asker = bot("Ann"), yes = bot("Bob");
        long t0 = clock.get();
        TalkPromises.promised(1, Ask.PARTY, yes, asker, -1, t0);
        TalkPromises.promised(2, Ask.HELP, yes, asker, -1, t0);
        TalkPromises.promised(3, Ask.COME_ALONG, yes, asker, HENESYS, t0);
        TalkPromises.promised(4, Ask.TRADE, yes, asker, -1, t0);
        TalkPromises.promised(5, Ask.GOSSIP, yes, asker, -1, t0);

        List<SocialIntent> live = SocialIntents.live(yes.getId());
        assertEquals(3, live.size(), live.toString());
        for (SocialIntent in : live) {
            assertEquals(yes.getId(), in.actor(), "the one who said yes promised");
            assertEquals(asker.getId(), in.target());
            assertEquals(t0, in.createdAt());
        }
        SocialIntent party = byKind(live, SocialActKind.INVITE), help = byKind(live, SocialActKind.OFFER_HELP),
                come = byKind(live, SocialActKind.SPEND_TIME);
        assertEquals(t0 + TalkPromises.windowMs(Ask.PARTY) + PromiseIntents.GRACE_MS, party.dueAt());
        assertEquals(t0 + TalkPromises.windowMs(Ask.HELP) + PromiseIntents.GRACE_MS, help.dueAt());
        assertEquals(t0 + TalkPromises.windowMs(Ask.COME_ALONG) + PromiseIntents.GRACE_MS, come.dueAt());
        assertEquals(party.dueAt() + SocialIntents.WINDOW_MS, party.expiresAt());
        assertEquals("talk:PARTY", party.origin());
        assertEquals("talk:COME_ALONG", come.origin());
        assertTrue(SocialIntents.live(asker.getId()).isEmpty(), "the asker promised nothing");
        assertEquals(3L, SocialIntents.counters().get("created"));
    }

    private static SocialIntent byKind(List<SocialIntent> live, SocialActKind kind) {
        return live.stream().filter(i -> i.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    void theTalksOwnBackedVerdictKeepsTheIntentAtOnce() {
        Character asker = bot("Ann"), yes = bot("Bob");
        long t0 = clock.get();
        TalkPromises.promised(1, Ask.PARTY, yes, asker, -1, t0);
        where.put(asker.getId(), new TalkPromises.Where(HENESYS, 55, -1));
        where.put(yes.getId(), new TalkPromises.Where(HENESYS, 55, -1)); // in one party
        assertEquals(1, TalkPromises.resolveDue(t0 + 10_000).size());
        assertTrue(SocialIntents.live(yes.getId()).isEmpty(), "carried out by the act that was already there: kept");
        assertEquals(List.of("INVITE"), kept);
        assertEquals(1L, SocialIntents.counters().get("kept INVITE"));
        assertTrue(performed.isEmpty(), "nothing was carried out twice");
    }

    @Test
    void aYesThatWasNotKeptGetsASecondTryAtTheDueTimeAndIsBrokenWhenThatFailsToo() {
        Character asker = bot("Ann"), yes = bot("Cat");
        long t0 = clock.get();
        TalkPromises.promised(1, Ask.COME_ALONG, yes, asker, HENESYS, t0);
        where.put(yes.getId(), new TalkPromises.Where(ELLINIA, 0, -1)); // never went
        where.put(asker.getId(), new TalkPromises.Where(HENESYS, 0, -1));
        long window = TalkPromises.windowMs(Ask.COME_ALONG);
        assertEquals(1, TalkPromises.resolveDue(t0 + window).size(), "the talk's ledger calls it unbacked");
        SocialIntent in = SocialIntents.live(yes.getId()).get(0);
        assertTrue(SocialIntents.tick(yes.getId(), t0 + window).isEmpty(), "the grace: nothing is due yet");
        assertEquals("deferred", SocialIntents.tick(yes.getId(), in.dueAt()).get(0).what());
        assertEquals(List.of("SPEND_TIME"), performed, "its second try");
        assertEquals("broken", SocialIntents.tick(yes.getId(), in.expiresAt()).get(0).what());
        assertEquals(List.of("SPEND_TIME"), broken);
        assertTrue(kept.isEmpty());
        assertTrue(SocialIntents.live(yes.getId()).isEmpty());
    }

    @Test
    void theSecondTryCanKeepIt() {
        Character asker = bot("Ann"), yes = bot("Dee");
        long t0 = clock.get();
        TalkPromises.promised(1, Ask.HELP, yes, asker, -1, t0);
        SocialIntent in = SocialIntents.live(yes.getId()).get(0);
        performs = true;
        assertEquals("kept", SocialIntents.tick(yes.getId(), in.dueAt()).get(0).what());
        assertEquals(List.of("OFFER_HELP"), kept);
    }

    @Test
    void aDialogueIntentSurvivesALifeMemorySaveAndRestore() {
        Character asker = bot("Ann"), yes = bot("Eve");
        TalkPromises.promised(1, Ask.PARTY, yes, asker, -1, clock.get());
        SocialIntent in = SocialIntents.live(yes.getId()).get(0);
        LifeMemory memory = LifeMemoryStore.of(yes.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) Json.parse(Json.write(memory.save(clock.get())));
        LifeMemory reborn = new LifeMemory(yes.getId(), saved);
        assertEquals(List.of(in), reborn.intents(IntentBook::live));
    }

    @Test
    void atMostThreeAreLiveSoAFourthPromiseIsRefusedAndTheTalkIsUnchanged() {
        Character yes = bot("Fay");
        Character a = bot("A1"), b = bot("A2"), c = bot("A3"), d = bot("A4");
        long t0 = clock.get();
        TalkPromises.promised(1, Ask.PARTY, yes, a, -1, t0);
        TalkPromises.promised(2, Ask.HELP, yes, b, -1, t0);
        TalkPromises.promised(3, Ask.COME_ALONG, yes, c, HENESYS, t0);
        TalkPromises.promised(4, Ask.PARTY, yes, d, -1, t0);
        assertEquals(3, SocialIntents.live(yes.getId()).size());
        assertEquals(1L, SocialIntents.counters().get("refused"));
        assertEquals(4, ((Number) TalkPromises.stats().get("open")).intValue(),
                "the say-do ledger still holds all four yeses: nothing was taken away");
    }

    // ── words alone create nothing ──

    @Test
    void aModelsWordsAndTagsThatNeverPassedTheGatesCreateNothing() {
        Character yes = bot("Gus");
        BotSM sm = botSm(yes);
        Character josh = player(3_101, "Josh");
        // a promise in plain words: no tag, no action
        ConvoContract.Reply words = ConvoContract.parse("sure ill meet u at henesys in 5 min, ill help u kill stuff too");
        assertEquals(ConvoAction.NONE, words.action(), "no tag in the words");
        ConvoActions.Proposal p = ConvoActions.onProposal(sm, josh, words, ConvoSense.rules("what u doing"), "what u doing");
        assertFalse(p.done());
        // a tag the gates refuse (the bot is busy)
        facts = ConvoGatesTest.with(f -> {
            f[0] = true;
            return f;
        });
        ConvoActions.Proposal tagged = ConvoActions.onProposal(sm, josh, ConvoContract.parse("omw [do:come]"),
                ConvoSense.rules("these mobs hit so hard lol"), "these mobs hit so hard lol");
        assertFalse(tagged.done());
        // a claim the say-do judge reads
        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(yes, "ok omw to henesys");
        assertTrue(SocialIntents.live(yes.getId()).isEmpty(), "words never create an intent");
        assertTrue(done.isEmpty());
        assertEquals(0L, SocialIntents.counters().get("created"));
        assertEquals(0L, SocialIntents.counters().get("refused"), "nothing even asked for one");
    }

    // ── bot to player ──

    @Test
    void aPlayersRequestThatPassedTheGatesIsAnIntentAfterTheImmediateActAndHelpToo() {
        Character yes = bot("Hal");
        Character josh = player(3_102, "Josh");
        BotChatActions.Result r = ConvoActions.onIntent(botSm(yes), josh, ConvoSense.rules("come to ant tunnel"),
                "come to ant tunnel");
        assertEquals(BotChatActions.Outcome.COME_YES, r.outcome());
        assertEquals(List.of("grind 105050000"), done, "the act that ran before still runs");
        SocialIntent in = SocialIntents.live(yes.getId()).get(0);
        assertEquals(SocialActKind.SPEND_TIME, in.kind());
        assertEquals(josh.getId(), in.target());
        assertEquals(yes.getId(), in.actor());
        assertEquals(clock.get(), in.createdAt());
        assertEquals(clock.get() + PromiseIntents.COME_DELAY_MS, in.dueAt());
        assertEquals("chat:COME", in.origin());

        Character helper = bot("Ivy");
        facts = ConvoGatesTest.with(f -> {
            f[8] = 105050000;
            return f;
        });
        BotChatActions.Result h = ConvoActions.onIntent(botSm(helper), josh, ConvoSense.rules("can u help me kill at ant tunnel"),
                "can u help me kill at ant tunnel");
        assertEquals(BotChatActions.Outcome.HELP_YES, h.outcome());
        SocialIntent help = SocialIntents.live(helper.getId()).get(0);
        assertEquals(SocialActKind.OFFER_HELP, help.kind());
        assertEquals(clock.get() + PromiseIntents.HELP_DELAY_MS, help.dueAt());
        assertEquals("chat:HELP", help.origin());
    }

    @Test
    void aNoATooFarAndAlreadyHereAreNotPromises() {
        Character yes = bot("Jay");
        Character josh = player(3_103, "Josh");
        facts = ConvoGatesTest.with(f -> {
            f[2] = true; // hostile: the gates say no
            return f;
        });
        assertEquals(BotChatActions.Outcome.COME_NO, ConvoActions.onIntent(botSm(yes), josh,
                ConvoSense.rules("come to ant tunnel"), "come to ant tunnel").outcome());
        facts = ConvoGatesTest.with(f -> {
            f[17] = false; // too far to walk
            return f;
        });
        ConvoActions.onIntent(botSm(yes), josh, ConvoSense.rules("come to ant tunnel"), "come to ant tunnel");
        facts = ConvoGatesTest.with(f -> {
            f[9] = true; // already on the player's map
            return f;
        });
        assertEquals(BotChatActions.Outcome.COME_YES, ConvoActions.onIntent(botSm(yes), josh,
                ConvoSense.rules("come to ant tunnel"), "come to ant tunnel").outcome());
        assertTrue(SocialIntents.live(yes.getId()).isEmpty());
        assertEquals(0L, SocialIntents.counters().get("created"));
    }

    @Test
    void anOptedOutPlayerGetsTheImmediateActButNoIntent() {
        Character yes = bot("Kit");
        Character josh = player(3_104, "Josh");
        aim = false; // the fake gate says no: the immediate act still happened (their own request, already gated)
        BotChatActions.Result r = ConvoActions.onIntent(botSm(yes), josh, ConvoSense.rules("come to ant tunnel"),
                "come to ant tunnel");
        assertEquals(BotChatActions.Outcome.COME_YES, r.outcome());
        assertEquals(List.of("grind 105050000"), done);
        assertTrue(SocialIntents.live(yes.getId()).isEmpty());
        assertEquals(1L, SocialIntents.counters().get("refused"));
    }

    @Test
    void theRealGateTurnsAwayQuietBotsAndBotDramaOffAndLetsAnOrdinaryPlayerThrough() {
        SocialIntents.resetForTest();
        SocialIntents.useForTest(null, new SocialIntents.Consequences() {
            public void kept(SocialIntent in, long now) {
            }

            public void broken(SocialIntent in, long now) {
            }
        }, (a, t, k, n) -> new Result(true, "done"));
        Character yes = bot("Lou");
        Character ordinary = human(3_201, "Ordi"), quiet = human(3_202, "Quiet"), calm = human(3_203, "Calm");
        Map<Integer, Character> world = new HashMap<>();
        for (Character c : List.of(yes, ordinary, quiet, calm)) {
            world.put(c.getId(), c);
        }
        SocialIntents.useCharactersForTest(world::get);
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotNoiseFilter.set(quiet.getId(), new BotNoiseFilter.Setting(false, true)); // @quiet bots
        BotAffinity.setOptedOut(calm, true);                                         // @botdrama off
        try {
            assertTrue(SocialIntents.promise(yes.getId(), ordinary.getId(), SocialActKind.OFFER_HELP, MIN, 0, "chat:HELP",
                    clock.get()).ok(), "an ordinary player is gated by nothing");
            assertFalse(SocialIntents.promise(yes.getId(), quiet.getId(), SocialActKind.OFFER_HELP, MIN, 0, "chat:HELP",
                    clock.get()).ok(), "@quiet bots: nothing unasked, and a later check-in is unasked");
            assertFalse(SocialIntents.promise(yes.getId(), calm.getId(), SocialActKind.SPEND_TIME, MIN, 0, "chat:COME",
                    clock.get()).ok(), "@botdrama off");
            assertEquals(1, SocialIntents.live(yes.getId()).size());
            assertEquals(2L, SocialIntents.counters().get("refused"));
        } finally {
            BotNoiseFilter.set(quiet.getId(), BotNoiseFilter.Setting.NONE);
            BotAffinity.useForTest(null, null, null, null);
            SocialGate.resetForTest();
        }
    }

    // ── the flag ──

    @Test
    void withTheFlagOffNoIntentIsCreatedByAnyPathAndTheActsAreUnchanged() {
        System.setProperty(SocialIntents.SWITCH, "false");
        Character asker = bot("Ann"), yes = bot("Max");
        Character josh = player(3_105, "Josh");
        TalkPromises.promised(1, Ask.PARTY, yes, asker, -1, clock.get());
        BotChatActions.Result r = ConvoActions.onIntent(botSm(yes), josh, ConvoSense.rules("come to ant tunnel"),
                "come to ant tunnel");
        assertEquals(BotChatActions.Outcome.COME_YES, r.outcome());
        assertEquals(List.of("grind 105050000"), done);
        assertEquals(1, ((Number) TalkPromises.stats().get("open")).intValue(), "the say-do ledger still got its yes");
        assertTrue(SocialIntents.live(yes.getId()).isEmpty());
        assertEquals(0L, SocialIntents.counters().get("created"));
        assertNotNull(LifeMemoryStore.peek(yes.getId()));
    }
}
