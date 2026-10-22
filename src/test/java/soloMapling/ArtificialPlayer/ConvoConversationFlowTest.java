package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
import soloMapling.ArtificialPlayer.BotConvo.ChatBank;
import soloMapling.ArtificialPlayer.BotConvo.ConvoActions;
import soloMapling.ArtificialPlayer.BotConvo.ConvoPersona;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 59 through {@link BotConversation}: the bank when the model is out, the voice, the contract's tag. */
class ConvoConversationFlowTest {

    private final List<List<BotBrain.Turn>> asked = new CopyOnWriteArrayList<>();
    private final List<String> said = new CopyOnWriteArrayList<>();
    private final List<String> done = new CopyOnWriteArrayList<>();
    private volatile Optional<String> brainReply = Optional.empty();
    private static int nextPlayer = 52_001;

    @BeforeEach
    void fakes() throws Exception {
        System.setProperty("solomapling.chat.typingScale", "0.01");
        System.setProperty("solomapling.chat.logReplies", "false");
        System.setProperty("solomapling.convo.quality", "false"); // no transcript files from tests
        ChatStats.resetForTest();
        ChatDecide.useForTest(null);
        BotBrains.useForTest(new BotBrain() {
            @Override
            public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
                asked.add(conversation);
                return CompletableFuture.completedFuture(brainReply);
            }

            @Override
            public CompletableFuture<Optional<String>> notes(List<Turn> conversation) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply>> reply(
                    soloMapling.ArtificialPlayer.BotBrainSystem.Priority priority, int playerId, List<Turn> conversation) {
                asked.add(conversation);
                return CompletableFuture.completedFuture(brainReply.map(
                        soloMapling.ArtificialPlayer.BotConvo.ConvoContract::parse));
            }

            @Override
            public boolean isLive() {
                return true;
            }

            @Override
            public String describe() {
                return "fake";
            }
        });
        BotStore.ConnectionSource fake = () -> {
            Connection con = mock(Connection.class);
            when(con.prepareStatement(anyString())).thenAnswer(c -> statement());
            when(con.prepareStatement(anyString(), anyInt())).thenAnswer(c -> statement());
            return con;
        };
        BotMemory.useConnectionSourceForTest(fake);
        BotRoster.useConnectionSourceForTest(fake);
        ChatBank.useForTest(ChatBank.read(new ByteArrayInputStream(String.join("\n",
                "1\tgreeting\tany\tany\tany\t-\tyo {player} whats good",
                "2\ttalk_q\tany\tany\tany\t-\thmm no idea tbh",
                "3\tcome_no\tany\tany\tany\t-\tnah im staying put").getBytes(StandardCharsets.UTF_8))));
        ConvoActions.useForTest(new ConvoActions.Effects() {
            public void grindAt(BotSM bot, int mapId) {
                done.add("grind " + mapId);
            }

            public void walkTo(Character bot, int mapId, Character player) {
                done.add("walk " + mapId);
            }

            public String buff(Character bot, Character player, String line) {
                return null;
            }

            public boolean buddy(int botCharId, int playerId) {
                return false;
            }

            public void affinity(Character bot, Character player, AffinityEvent event, int delta) {
                done.add("affinity " + event.key());
            }
        }, (bot, player, action, read, line) -> new soloMapling.ArtificialPlayer.BotConvo.ConvoGates.Facts(false, false,
                false, false, false, false, soloMapling.ArtificialPlayer.BotConvo.ConvoGates.BotKind.GRINDER, true,
                player.getMapId(), false, true, true, false, null, false, false, false, true));
    }

    private PreparedStatement statement() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet empty = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(empty);
        when(ps.getGeneratedKeys()).thenReturn(empty);
        return ps;
    }

    @AfterEach
    void reset() {
        System.clearProperty("solomapling.chat.typingScale");
        System.clearProperty("solomapling.chat.logReplies");
        System.clearProperty("solomapling.convo.quality");
        BotBrains.useForTest(null);
        ChatBank.useForTest(null);
        ConvoActions.useForTest(null, null);
        BotMemory.useConnectionSourceForTest(null);
        BotRoster.useConnectionSourceForTest(null);
        ChatStats.resetForTest();
    }

    private static Character character(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(35);
        return chr;
    }

    private void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 240 && !ok.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
    }

    private Character whisper(BotSM bot, int botSlot, String line) {
        Character chr = character(BotStore.BOT_ID_BASE + botSlot, "Walkr");
        when(bot.getChr()).thenReturn(chr);
        Character player = character(nextPlayer++, "Josh");
        assertTrue(BotConversation.say(bot, player, line, null, said::add));
        return player;
    }

    @Test
    void whenTheModelIsOutTheBankAnswersInTheBotsVoice() throws Exception {
        int slot = 61_001;
        ConvoPersona.forceVoice(slot, ConvoPersona.Voice.PROPER);
        try {
            whisper(mock(TownWandererBot.class), slot, "hey");
            waitFor(() -> !said.isEmpty());
            assertEquals(1, said.size(), said.toString());
            assertTrue(said.get(0).startsWith("Yo Josh"), "bank line, filled and styled: " + said);
        } finally {
            ConvoPersona.clearVoice(slot);
        }
    }

    @Test
    void theModelsTagRunsTheGatedActionAndTheWordsStay() throws Exception {
        brainReply = Optional.of("ya omw [do:come]");
        System.setProperty("solomapling.convo.persona", "false");
        try {
            whisper(mock(TrainingBot.class), 61_002, "these mobs hit so hard lol");
            waitFor(() -> !said.isEmpty());
            assertEquals(List.of("ya omw"), said, "the tag never reaches the player");
            assertTrue(done.stream().anyMatch(d -> d.startsWith("grind")), done.toString());
            String system = asked.get(0).get(0).content();
            assertTrue(system.contains("[do:come]") && system.contains("About Josh:"), "contract and card: " + system);
        } finally {
            System.clearProperty("solomapling.convo.persona");
        }
    }

    @Test
    void aRefusedRequestIsSaidAsARefusalEvenIfTheModelSaysYes() throws Exception {
        ConvoActions.useForTest(new ConvoActions.Effects() {
            public void grindAt(BotSM bot, int mapId) {
                done.add("grind");
            }

            public void walkTo(Character bot, int mapId, Character player) {
                done.add("walk");
            }

            public String buff(Character bot, Character player, String line) {
                return null;
            }

            public boolean buddy(int botCharId, int playerId) {
                return false;
            }

            public void affinity(Character bot, Character player, AffinityEvent event, int delta) {
            }
        }, (bot, player, action, read, line) -> new soloMapling.ArtificialPlayer.BotConvo.ConvoGates.Facts(false, false,
                true, false, false, false, soloMapling.ArtificialPlayer.BotConvo.ConvoGates.BotKind.GRINDER, true,
                105050000, false, false, true, false, null, false, false, false, true));
        brainReply = Optional.of("sure omw");
        System.setProperty("solomapling.convo.persona", "false");
        try {
            whisper(mock(TrainingBot.class), 61_003, "come to ant tunnel");
            waitFor(() -> !said.isEmpty());
            assertEquals(List.of("nah im staying put"), said, "the bank's refusal, not the model's yes");
            assertTrue(done.isEmpty(), "no walk for a rival");
        } finally {
            System.clearProperty("solomapling.convo.persona");
        }
    }
}
