package testutil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The leak this guards against: a test sets {@code some.property} and never puts it back, so the next test in the same
 * JVM reads a machine the build never configured. {@link PropsRestored} snapshots the property table around each test;
 * here the extension is driven directly so the check is a plain assertion rather than a bet on method order.
 */
class PropsRestoredTest {

    private static final String KEY = "testutil.propsRestored.probe";

    private final Map<Object, Object> store = new HashMap<>();

    @AfterEach
    void leaveTheMachineAsFound() {
        System.clearProperty(KEY);
    }

    private ExtensionContext context() {
        ExtensionContext ctx = mock(ExtensionContext.class);
        ExtensionContext.Store backing = mock(ExtensionContext.Store.class);
        when(ctx.getUniqueId()).thenReturn("props-restored-test");
        when(ctx.getStore(any())).thenReturn(backing);
        doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1))).when(backing).put(any(), any());
        when(backing.remove(any(), eq(Properties.class)))
                .thenAnswer(inv -> (Properties) store.remove(inv.getArgument(0)));
        return ctx;
    }

    @Test
    void aPropertyATestSetsIsGoneAfterwards() {
        PropsRestored ext = new PropsRestored();
        ExtensionContext ctx = context();
        System.clearProperty(KEY);
        ext.beforeEach(ctx);
        System.setProperty(KEY, "leaked");
        ext.afterEach(ctx);
        assertNull(System.getProperty(KEY), "the test's value must not outlive the test");
    }

    @Test
    void aPropertyATestOverwritesGetsItsOldValueBack() {
        PropsRestored ext = new PropsRestored();
        ExtensionContext ctx = context();
        System.setProperty(KEY, "the-server-said-so");
        ext.beforeEach(ctx);
        System.setProperty(KEY, "the-test-said-otherwise");
        ext.afterEach(ctx);
        assertEquals("the-server-said-so", System.getProperty(KEY), "the previous value, not null");
    }

    @Test
    void aPropertyTheJvmSetBeforeTheClassLoadedSurvives() {
        PropsRestored ext = new PropsRestored();
        System.setProperty(KEY, "from-startup");
        ExtensionContext ctx = context();
        // stands in for surefire's systemPropertyVariables and a static initialiser's WZ path: in the snapshot already
        ext.beforeEach(ctx);
        assertNull(System.getProperty("testutil.propsRestored.untouched"));
        System.setProperty("testutil.propsRestored.untouched", "set by this test");
        ext.afterEach(ctx);
        assertEquals("from-startup", System.getProperty(KEY));
        assertNull(System.getProperty("testutil.propsRestored.untouched"));
    }
}
