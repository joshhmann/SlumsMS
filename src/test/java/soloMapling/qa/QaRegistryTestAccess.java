package soloMapling.qa;

import java.util.Map;

/** The live and test server command lines QaRegistryTest keeps, for switch tests in other packages. */
public final class QaRegistryTestAccess {
    private QaRegistryTestAccess() {
    }

    public static Map<String, String> live() {
        return QaRegistryTest.props(QaRegistryTest.LIVE);
    }

    public static Map<String, String> test() {
        return QaRegistryTest.props(QaRegistryTest.TEST);
    }

    public static Map<String, String> props(String commandLine) {
        return QaRegistryTest.props(commandLine);
    }
}
