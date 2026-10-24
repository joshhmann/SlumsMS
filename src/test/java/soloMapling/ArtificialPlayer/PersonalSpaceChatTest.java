package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Owner report 2026-10-24, the other half: personal space kept shoving a chatting bot away from the player. While a
 * bot holds a chat, personal space may only clear a partner (step to 15-30 px) and must never push it away from the
 * player because a third character crowded in.
 */
class PersonalSpaceChatTest {

    private MapleMap map;
    private Character player;
    private Character stranger;

    @BeforeEach
    void fakes() {
        System.setProperty("solomapling.chat.typingScale", "0.01");
        System.setProperty("solomapling.chat.logReplies", "false");
        System.setProperty("solomapling.convo.quality", "false");
        System.setProperty("solomapling.convo.persona", "false");
        System.setProperty("solomapling.convo.bank", "false");
        ChatStats.resetForTest();
        ChatDecide.useForTest(null);
        BotBrains.useForTest(new BotBrain() {
            @Override
            public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
                return CompletableFuture.completedFuture(Optional.of("still here"));
            }

            @Override
            public CompletableFuture<Optional<String>> notes(List<Turn> conversation) {
                return CompletableFuture.completedFuture(Optional.empty());
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
        BotConversation.holdMsForTest(BotConversation.HOLD_MS);
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
        for (String key : List.of("solomapling.chat.typingScale", "solomapling.chat.logReplies",
                "solomapling.convo.quality", "solomapling.convo.persona", "solomapling.convo.bank")) {
            System.clearProperty(key);
        }
        BotBrains.useForTest(null);
        ChatDecide.useForTest(null);
        BotMemory.useConnectionSourceForTest(null);
        BotRoster.useConnectionSourceForTest(null);
        ChatStats.resetForTest();
        BotConversation.holdMsForTest(BotConversation.HOLD_MS);
    }

    private static Character character(int id, String name, Point at) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(35);
        chr.setPosition(at);
        return chr;
    }

    /** A town with the player, one stranger bot, and the bot under test; a flat floor at y = 274. */
    private Character town(int botId, Point botAt, Point playerAt, Point strangerAt) {
        Character bot = character(botId, "Chatty", botAt);
        player = character(botId + 1, "Josh", playerAt);
        player = spy(player);
        doReturn(mock(client.Client.class)).when(player).getClient(); // a real player, not a bot
        stranger = character(botId + 2, "Pushr", strangerAt);
        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        when(map.getAllPlayers()).thenReturn(List.of(player, stranger));
        when(map.getMapObjects()).thenReturn(List.of());
        bot.setMap(map);
        player.setMap(map);
        stranger.setMap(map);
        return bot;
    }

    private void chat(Character bot, String line) {
        BotSM sm = mock(TownWandererBot.class);
        when(sm.getChr()).thenReturn(bot);
        assertTrue(BotConversation.say(sm, player, line, null, s -> { }), "the player's line starts the chat");
        assertTrue(BotConversation.isHoldingChat(bot), "and holds the bot");
    }

    @Test
    void aStrangerNoLongerShovesAChattingBotAwayFromThePlayer() {
        Character bot = town(BotStore.BOT_ID_BASE + 60_001, new Point(0, 274), new Point(40, 274), new Point(12, 274));
        chat(bot, "hey, where are you going?");
        try (var movement = mockStatic(GCMovement.class); var commands = mockStatic(MovementCommands.class);
             var tuning = mockStatic(BotCombatTuning.class)) {
            movement.when(() -> GCMovement.isEnabled(bot)).thenReturn(false);
            movement.when(() -> GCMovement.sweepPose(bot)).thenReturn("GROUNDED");
            movement.when(() -> GCMovement.groundPointBelow(eq(map), anyInt(), anyInt()))
                    .thenAnswer(c -> new Point(c.getArgument(1), 274));
            commands.when(() -> MovementCommands.isBotMoving(bot)).thenReturn(false);
            tuning.when(BotCombatTuning::spread).thenReturn(true);
            assertFalse(PersonalSpace.keepNow(bot), "a talking bot keeps its place: no step away from the player");
            commands.verify(() -> MovementCommands.standUp(bot), never()); // it never even began to move
        }
    }

    @Test
    void aPartnerStillGetsTheFifteenToThirtyPixelRoom() {
        Character bot = town(BotStore.BOT_ID_BASE + 60_010, new Point(0, 274), new Point(6, 274), new Point(400, 274));
        chat(bot, "hi hi");
        try (var movement = mockStatic(GCMovement.class); var commands = mockStatic(MovementCommands.class);
             var tuning = mockStatic(BotCombatTuning.class)) {
            movement.when(() -> GCMovement.isEnabled(bot)).thenReturn(false);
            movement.when(() -> GCMovement.sweepPose(bot)).thenReturn("GROUNDED");
            movement.when(() -> GCMovement.groundPointBelow(eq(map), anyInt(), anyInt()))
                    .thenAnswer(c -> new Point(c.getArgument(1), 274));
            commands.when(() -> MovementCommands.isBotMoving(bot)).thenReturn(false);
            tuning.when(BotCombatTuning::spread).thenReturn(true);
            assertTrue(PersonalSpace.keepNow(bot), "the pair still steps to a polite 15-30 px");
        }
    }

    @Test
    void onceTheHoldLapsesAStrangerMayClearTheSpotAgain() throws Exception {
        Character bot = town(BotStore.BOT_ID_BASE + 60_020, new Point(0, 274), new Point(40, 274), new Point(12, 274));
        BotConversation.holdMsForTest(40); // a 40 ms hold, cleared: this test's chat arms it afresh
        chat(bot, "hey, where are you going?");
        for (int i = 0; i < 100 && BotConversation.isHoldingChat(bot); i++) {
            Thread.sleep(25); // the bot's own reply re-arms the hold once; then it lapses
        }
        assertFalse(BotConversation.isHoldingChat(bot), "the hold lapsed");
        try (var movement = mockStatic(GCMovement.class); var commands = mockStatic(MovementCommands.class);
             var tuning = mockStatic(BotCombatTuning.class)) {
            movement.when(() -> GCMovement.isEnabled(bot)).thenReturn(false);
            movement.when(() -> GCMovement.sweepPose(bot)).thenReturn("GROUNDED");
            movement.when(() -> GCMovement.groundPointBelow(eq(map), anyInt(), anyInt()))
                    .thenAnswer(c -> new Point(c.getArgument(1), 274));
            commands.when(() -> MovementCommands.isBotMoving(bot)).thenReturn(false);
            tuning.when(BotCombatTuning::spread).thenReturn(true);
            assertTrue(PersonalSpace.keepNow(bot), "with no chat left, personal space works as before");
        }
    }
}
