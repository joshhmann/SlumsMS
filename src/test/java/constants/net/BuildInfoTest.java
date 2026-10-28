package constants.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BuildInfoTest {

    static Properties props(String text) {
        return BuildInfo.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shortCommitFromTheBuildProperties() {
        assertEquals("5e21fd275", BuildInfo.shortCommit(props("git.commit.id.abbrev=5e21fd275\n")));
        assertEquals("5e21fd275", BuildInfo.shortCommit(props("git.commit.id=5e21fd275b3c67cdd8afa423f11fc5780f64403f\n")));
        assertNull(BuildInfo.shortCommit(props("")), "no git at build time: no commit");
        assertNull(BuildInfo.shortCommit(props("git.commit.id.abbrev=${git.commit.id.abbrev}\n")), "an unfilled placeholder is no commit");
        assertNull(BuildInfo.shortCommit(BuildInfo.load(null)));
    }

    @Test
    void theBuiltCommitLooksLikeOneWhenPresent() {
        String c = BuildInfo.commit();
        assertTrue(c == null || c.matches("[0-9a-f]{7,40}"), String.valueOf(c));
    }
}
