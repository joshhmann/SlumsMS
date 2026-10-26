package client.command.commands.gm0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WebLoginCommandTest {
    @TempDir Path dir;
    AtomicLong now = new AtomicLong(1_793_001_642_500L);

    @BeforeEach
    void setUp() {
        WebLoginCommand.resetForTest();
        WebLoginCommand.clock = now::get;
    }

    @AfterEach
    void tearDown() {
        WebLoginCommand.clock = System::currentTimeMillis;
    }

    private String run(String d, int charId, int acct, String code) {
        return WebLoginCommand.handle(d, charId, "login" + acct, acct, "Momo", 0, code);
    }

    private long fileCount() throws Exception {
        Path a = dir.resolve("approvals");
        if (!Files.isDirectory(a)) return 0;
        try (var s = Files.list(a)) { return s.count(); }
    }

    @Test
    void validCodeWritesApprovalJson() throws Exception {
        assertEquals("Website sign-in sent. Go back to your browser.", run(dir.toString(), 5, 123, " k7q4zp "));
        Path f = dir.resolve("approvals/K7Q4ZP.json");
        JsonNode j = new ObjectMapper().readTree(Files.readAllBytes(f));
        assertEquals("K7Q4ZP", j.get("code").asText());
        assertEquals(123, j.get("account_id").asInt());
        assertEquals("login123", j.get("account").asText());
        assertEquals("Momo", j.get("character").asText());
        assertEquals(0, j.get("gm").asInt());
        assertEquals(1_793_001_642L, j.get("time").asLong());
        assertEquals(1, fileCount(), "no leftover .tmp");
        if (Files.getFileStore(f).supportsFileAttributeView("posix")) {
            assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        }
    }

    @Test
    void invalidCodesWriteNothing() throws Exception {
        for (String bad : new String[]{"", "ABC", "K7Q4ZPX", "K7Q4Z0", "K7Q4ZO", "K7Q4Z1", "K7Q4ZI", "K7Q-ZP", null}) {
            assertTrue(run(dir.toString(), 5, 1, bad).startsWith("That code doesn't look right"), String.valueOf(bad));
        }
        assertEquals(0, fileCount());
    }

    @Test
    void rateLimitFivePerMinutePerAccount() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertTrue(run(dir.toString(), 5, 9, "AAAAA" + "BCDEF".charAt(i)).startsWith("Website sign-in sent"));
        }
        assertTrue(run(dir.toString(), 5, 9, "AAAAAG").startsWith("Slow down"));
        assertFalse(Files.exists(dir.resolve("approvals/AAAAAG.json")));
        assertTrue(run(dir.toString(), 6, 10, "AAAAAG").startsWith("Website sign-in sent"), "other account unaffected");
        now.addAndGet(60_000);
        assertTrue(run(dir.toString(), 5, 9, "AAAAAH").startsWith("Website sign-in sent"));
    }

    @Test
    void botsRefused() throws Exception {
        assertEquals("Bots can't use website sign-in.", run(dir.toString(), soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE, 1, "K7Q4ZP"));
        assertEquals(0, fileCount());
    }

    @Test
    void offByDefault() {
        assertEquals("Website sign-in isn't available right now.", run(null, 5, 1, "K7Q4ZP"));
        assertEquals("Website sign-in isn't available right now.", run("", 5, 1, "K7Q4ZP"));
    }

    @Test
    void revokedAccountRefused() throws Exception {
        Files.writeString(dir.resolve("revoked.txt"), "7\n 42 \n");
        assertEquals("Website sign-in isn't available for this account.", run(dir.toString(), 5, 42, "K7Q4ZP"));
        assertEquals(0, fileCount());
        assertTrue(run(dir.toString(), 5, 43, "K7Q4ZP").startsWith("Website sign-in sent"));
    }

    @Test
    void diskFailureDoesNotThrow() throws Exception {
        Path blocker = dir.resolve("afile");
        Files.writeString(blocker, "x");
        assertEquals("Couldn't send the website sign-in, please try again.", run(blocker.toString(), 5, 1, "K7Q4ZP"));
    }
}
