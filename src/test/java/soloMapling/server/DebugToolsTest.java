package soloMapling.server;

import org.junit.jupiter.api.Test;
import soloMapling.qa.QaRegistryTestAccess;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157: the debug tools are off on live and on on the test server, unless forced. */
class DebugToolsTest {

    @Test
    void offOnLiveOnOnTheTestServer() {
        assertFalse(DebugTools.enabledFor(QaRegistryTestAccess.live()::get));
        assertTrue(DebugTools.enabledFor(QaRegistryTestAccess.test()::get));
        assertTrue(DebugTools.enabledFor(QaRegistryTestAccess.props("java -Dslums.autoqa=true -jar x.jar")::get));
        assertTrue(DebugTools.enabledFor(QaRegistryTestAccess.props("java -Dslums.debugtools=true -jar x.jar")::get));
        assertFalse(DebugTools.enabledFor(QaRegistryTestAccess.props(
                "java -Dslums.qa=true -Dslums.debugtools=false -jar x.jar")::get), "forced off beats QA");
    }
}
