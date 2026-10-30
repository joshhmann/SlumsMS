package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChatRoutingTest {

    private static ChatMessage line(int senderId, String text) {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(senderId);
        return new ChatMessage(player, text);
    }

    @Test
    void aYesOrTheBotsNameConfirmsAndAnythingElseDoesNot() {
        long now = 1_000;
        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertTrue(BotChatRouting.confirmation(line(7, "ya"), "Ressurectme", now + 2_000).isPresent());

        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertTrue(BotChatRouting.confirmation(line(7, "yes u!!"), "Ressurectme", now + 2_000).isPresent());

        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertTrue(BotChatRouting.confirmation(line(7, "ressurect"), "Ressurectme", now + 2_000).isPresent());

        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertFalse(BotChatRouting.confirmation(line(7, "nah not u"), "Ressurectme", now + 2_000).isPresent());
        assertEquals(null, BotChatRouting.pendingFor(7), "a question is answered once");

        BotChatRouting.askFirst(20_501, line(8, "hey resme"), now);
        assertFalse(BotChatRouting.confirmation(line(8, "ya"), "Ressurectme",
                now + BotChatRouting.CONFIRM_MS + 1).isPresent(), "too late");
    }

    @Test
    void aShortAcceptanceAnswersTheBotThatJustAskedAndChatterDoesNot() {
        BotChatRouting.LastSpeaker partyCall = new BotChatRouting.LastSpeaker(20_100, "xXSkyBladeXx",
                "anyone wanna party for ant tunnel", 0);
        for (String yes : List.of("ill come", "sure count me in", "inv me pls", "me!", "i'm in")) {
            assertEquals(Optional.of(20_100), BotChatRouting.replyToLastSpeaker(yes, partyCall), yes);
        }
        for (String no : List.of("brb mom says dinner", "lol", "selling 60% glove att 3m",
                "sure but first i need to go sell all my etc items in henesys")) {
            assertEquals(Optional.empty(), BotChatRouting.replyToLastSpeaker(no, partyCall), no);
        }
        BotChatRouting.LastSpeaker statement = new BotChatRouting.LastSpeaker(20_100, "xXSkyBladeXx", "nice weather", 0);
        assertEquals(Optional.empty(), BotChatRouting.replyToLastSpeaker("ok", statement), "nothing was asked");
        assertEquals(Optional.empty(), BotChatRouting.replyToLastSpeaker("ill come", null));
    }

    @Test
    void buffRequestsAreLeftToTheBuffHandlerAndTradeTalkFindsNobody() {
        assertTrue(soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler.isBuffRequest("hs pls"));
        List<BotAddressee.Candidate> nearby = List.of(new BotAddressee.Candidate(20_100, "xXSkyBladeXx", 310),
                new BotAddressee.Candidate(20_101, "Kimchiz", 231));
        assertEquals(Optional.empty(), BotChatRouting.decideByJob("selling claws, anyone?", nearby));
        assertEquals(Optional.empty(), BotChatRouting.decideByJob("nice", nearby), "not a request");
    }

    @Test
    void jobFamiliesSplitClericsFromOtherMagicians() {
        assertEquals(BotChatRouting.JobFamily.CLERIC, BotChatRouting.JobFamily.of(231));
        assertEquals(BotChatRouting.JobFamily.MAGICIAN, BotChatRouting.JobFamily.of(211));
        assertEquals(BotChatRouting.JobFamily.BOWMAN, BotChatRouting.JobFamily.of(310));
        assertEquals(BotChatRouting.JobFamily.THIEF, BotChatRouting.JobFamily.of(420));
        assertEquals(BotChatRouting.JobFamily.WARRIOR, BotChatRouting.JobFamily.of(110));
        assertEquals(BotChatRouting.JobFamily.PIRATE, BotChatRouting.JobFamily.of(520));
        assertEquals(null, BotChatRouting.JobFamily.of(0), "beginners are no kind");
    }

    /** 1.6.0 population: Cygnus Knights and Aran are 15% of new bots, so "which one of u is the thief" must find them. */
    @Test
    void cygnusKnightsAndAranAnswerJobRequests() {
        assertEquals(BotChatRouting.JobFamily.WARRIOR, BotChatRouting.JobFamily.of(1110), "Dawn Warrior");
        assertEquals(BotChatRouting.JobFamily.MAGICIAN, BotChatRouting.JobFamily.of(1210), "Blaze Wizard");
        assertEquals(BotChatRouting.JobFamily.BOWMAN, BotChatRouting.JobFamily.of(1310), "Wind Archer");
        assertEquals(BotChatRouting.JobFamily.THIEF, BotChatRouting.JobFamily.of(1410), "Night Walker");
        assertEquals(BotChatRouting.JobFamily.PIRATE, BotChatRouting.JobFamily.of(1510), "Thunder Breaker");
        assertEquals(BotChatRouting.JobFamily.WARRIOR, BotChatRouting.JobFamily.of(2110), "Aran");
        assertEquals(null, BotChatRouting.JobFamily.of(1000), "a Noblesse is still a beginner");
        assertEquals(null, BotChatRouting.JobFamily.of(2000), "a Legend is still a beginner");
    }

    @Test
    void theJobRequestIsCleanAndItsAnswerIsReadBack() {
        String json = Json.write(BotChatRouting.jobRequest("can i get a bless (pls) :)"));
        assertFalse(json.contains("(pls)") || json.contains(":)"), json);
        assertTrue(json.contains("\"cleric\""));
        assertEquals(BotChatRouting.JobFamily.CLERIC, BotChatRouting.parseJob(
                "{\"answers\":{\"need\":{\"choice\":\"cleric\",\"confidence\":0.6}}}").orElseThrow().family());
        assertEquals(Optional.empty(), BotChatRouting.parseJob(
                "{\"answers\":{\"need\":{\"choice\":\"any\",\"confidence\":0.9}}}"));
    }

    // 1.6.2 bot audit (B26): this second decide consumer asked for the model id "decide" whatever the config said, so
    // the trained endpoint answered 404 and the request silently went unanswered.
    @Test
    void theDecisionRequestAsksForTheConfiguredModel() {
        config.ServerConfig server = YamlConfig.config.server;
        String was = server.BOT_DECIDE_MODEL;
        try {
            server.BOT_DECIDE_MODEL = "jev-latest";
            Map<?, ?> body = (Map<?, ?>) Json.parse(Json.write(BotChatRouting.jobRequest("which one of u is the thief")));
            assertEquals("jev-latest", body.get("model"));

            server.BOT_DECIDE_MODEL = "  ";
            body = (Map<?, ?>) Json.parse(Json.write(BotChatRouting.jobRequest("which one of u is the thief")));
            assertEquals("decide", body.get("model"), "an unset model keeps the old name");
        } finally {
            server.BOT_DECIDE_MODEL = was;
        }
    }

    @Test
    void theDecisionCallSendsTheConfiguredModelAndPicksTheOneNamedKind() throws IOException {
        AtomicReference<String> sent = new AtomicReference<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/v1/systemone", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"answers\":{\"need\":{\"choice\":\"cleric\",\"confidence\":0.7}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        http.start();
        config.ServerConfig server = YamlConfig.config.server;
        String url = server.BOT_DECIDE_URL;
        String model = server.BOT_DECIDE_MODEL;
        try {
            server.BOT_DECIDE_URL = "http://127.0.0.1:" + http.getAddress().getPort() + "/";
            server.BOT_DECIDE_MODEL = "jev-latest";
            List<BotAddressee.Candidate> near = List.of(
                    new BotAddressee.Candidate(1_000_000_012, "Prayer", 230),
                    new BotAddressee.Candidate(1_000_000_013, "Stab", 100));
            Optional<BotChatRouting.Choice> pick = BotChatRouting.decideByJob("which one of u is the cleric", near);
            assertEquals(1_000_000_012, pick.orElseThrow().botId(), "the one cleric nearby");
            assertTrue(sent.get().contains("\"model\":\"jev-latest\""), sent.get());
        } finally {
            server.BOT_DECIDE_URL = url;
            server.BOT_DECIDE_MODEL = model;
            http.stop(0);
        }
    }
}
