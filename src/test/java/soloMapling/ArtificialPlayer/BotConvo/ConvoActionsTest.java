package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotChatActions;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 59, owner decision 1: the bot's behaviour follows the words, and only through the gates. */
class ConvoActionsTest {

    private final List<String> done = new CopyOnWriteArrayList<>();
    private volatile ConvoGates.Facts facts = ConvoGatesTest.base();

    @BeforeEach
    void fakes() {
        ConvoActions.useForTest(new ConvoActions.Effects() {
            public void grindAt(BotSM bot, int mapId) {
                done.add("grind " + mapId);
            }

            public void walkTo(Character bot, int mapId, Character player) {
                done.add("walk " + mapId);
            }

            public String buff(Character bot, Character player, String line) {
                done.add("buff");
                return "Haste";
            }

            public boolean buddy(int botCharId, int playerId) {
                done.add("buddy");
                return true;
            }

            public void affinity(Character bot, Character player, AffinityEvent event, int delta) {
                done.add("affinity " + event.key());
            }
        }, (bot, player, action, read, line) -> facts);
    }

    @AfterEach
    void reset() {
        ConvoActions.useForTest(null, null);
        System.clearProperty("solomapling.convo.contract");
    }

    private static Character chr(int id, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(name);
        c.setLevel(40);
        return c;
    }

    private static BotSM bot() {
        BotSM bot = mock(TrainingBot.class);
        Character c = chr(BotStore.BOT_ID_BASE + 77_001, "Grindz");
        when(bot.getChr()).thenReturn(c);
        return bot;
    }

    @Test
    void aYesToComeStartsTheWalkBeforeTheWords() {
        BotChatActions.Result r = ConvoActions.onIntent(bot(), chr(3001, "Josh"), ConvoSense.rules("come to ant tunnel"),
                "come to ant tunnel");
        assertEquals(BotChatActions.Outcome.COME_YES, r.outcome());
        assertEquals(List.of("grind 105050000"), done);
        assertTrue(r.fact().contains("on your way"), r.fact());
        assertTrue(r.fits("ok omw"));
        assertFalse(r.fits("nah cant sry"));
    }

    @Test
    void aNoDoesNothing() {
        facts = ConvoGatesTest.with(f -> { f[2] = true; return f; });
        BotChatActions.Result r = ConvoActions.onIntent(bot(), chr(3002, "Josh"), ConvoSense.rules("come to ant tunnel"),
                "come to ant tunnel");
        assertEquals(BotChatActions.Outcome.COME_NO, r.outcome());
        assertTrue(done.isEmpty());
        assertFalse(r.fits("ya omw"), "a yes in words would not match the refusal");
    }

    @Test
    void aSecondWalkInTenMinutesIsRefusedByTheRealCooldown() {
        BotSM bot = bot();
        Character josh = chr(3003, "Josh");
        ConvoActions.onIntent(bot, josh, ConvoSense.rules("come to ant tunnel"), "come to ant tunnel");
        assertTrue(ConvoActions.recentlyWalked(bot.getChr().getId(), josh.getId(), System.currentTimeMillis()));
    }

    @Test
    void anApologyToARivalEasesTheGrudge() {
        facts = ConvoGatesTest.with(f -> { f[2] = true; f[15] = true; return f; });
        BotChatActions.Result r = ConvoActions.onIntent(bot(), chr(3004, "Josh"), ConvoSense.rules("sorry for ksing u"),
                "sorry for ksing u");
        assertEquals(BotChatActions.Outcome.MAKEUP_YES, r.outcome());
        assertEquals(List.of("affinity apology"), done);
    }

    @Test
    void aTagIsOnlyHonouredWhenThePlayerAskedForNothing() {
        ConvoContract.Reply tagged = ConvoContract.parse("omw [do:come]");
        ConvoActions.Proposal asked = ConvoActions.onProposal(bot(), chr(3005, "Josh"), tagged,
                ConvoSense.rules("can u buff me pls"), "can u buff me pls");
        assertFalse(asked.done(), "the player asked for a buff: the tag cannot swap it for a walk");
        assertTrue(done.isEmpty());
        ConvoActions.Proposal talk = ConvoActions.onProposal(bot(), chr(3006, "Josh"), tagged,
                ConvoSense.rules("these mobs hit so hard lol"), "these mobs hit so hard lol");
        assertTrue(talk.done());
        assertEquals(List.of("grind 105050000"), done);
    }

    @Test
    void economyTagsAreNeverHonoured() {
        for (String tag : new String[]{"gift", "trade", "haggle", "date", "guide"}) {
            ConvoActions.Proposal p = ConvoActions.onProposal(bot(), chr(3007, "Josh"),
                    ConvoContract.parse("sure [do:" + tag + "]"), ConvoSense.rules("lol nice"), "lol nice");
            assertFalse(p.done(), tag);
        }
        assertTrue(done.isEmpty(), done.toString());
    }

    @Test
    void aTaggedBuffGoesThroughTheBuffGate() {
        facts = ConvoGatesTest.with(f -> { f[13] = "no such buff"; return f; });
        ConvoActions.Proposal p = ConvoActions.onProposal(bot(), chr(3008, "Josh"), ConvoContract.parse("here [do:buff]"),
                ConvoSense.rules("ugh so slow"), "ugh so slow");
        assertFalse(p.done());
        assertEquals(BotChatActions.Outcome.BUFF_NO, p.result().outcome());
        assertTrue(done.isEmpty());
    }

    @Test
    void theSwitchTurnsItOff() {
        System.setProperty("solomapling.convo.contract", "false");
        assertEquals(BotChatActions.Outcome.NONE, ConvoActions.onIntent(bot(), chr(3009, "Josh"),
                ConvoSense.rules("come to ant tunnel"), "come to ant tunnel").outcome());
        assertTrue(done.isEmpty());
    }
}
