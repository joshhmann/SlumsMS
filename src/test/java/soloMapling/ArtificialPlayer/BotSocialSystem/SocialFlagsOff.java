package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Since 1.6.4 the daily-life brain, bot talk and crews are on unless a flag says {@code false}. A test written for
 * the world without them applies this extension ({@code @ExtendWith(SocialFlagsOff.class)}): the four flags are
 * {@code false} for each test and put back afterwards, whatever the test did to them. A test that wants one on sets
 * it to {@code true} itself, as before.
 */
public final class SocialFlagsOff implements BeforeEachCallback, AfterEachCallback {

    private static final String[] KEYS = {
            "slums.botlife.enabled", "slums.botlife.execute", "slums.bottalk", "slums.crews"};
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(SocialFlagsOff.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        String[] saved = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            saved[i] = System.getProperty(KEYS[i]);
            System.setProperty(KEYS[i], "false");
        }
        context.getStore(NS).put("saved", saved);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String[] saved = context.getStore(NS).remove("saved", String[].class);
        if (saved == null) {
            return;
        }
        for (int i = 0; i < KEYS.length; i++) {
            if (saved[i] == null) {
                System.clearProperty(KEYS[i]);
            } else {
                System.setProperty(KEYS[i], saved[i]);
            }
        }
    }
}
