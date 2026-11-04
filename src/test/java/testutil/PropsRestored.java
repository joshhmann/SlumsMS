package testutil;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Properties;

/**
 * A test that changes a system property and does not put the old value back makes every later test in the same JVM
 * run against a different machine than the one the build started on. Surefire forks one JVM per test class
 * ({@code reuseForks=false}), so the damage stops at the class boundary - but inside a class it is enough to make a
 * test pass only because another test ran first, which is a lie the build then reports as green.
 * <p>
 * {@code @ExtendWith(PropsRestored.class)} snapshots the whole property table before each test method and restores it
 * after: a property the method set disappears, a property it overwrote gets its old value back, and a property the
 * JVM or the static initialiser set (surefire's {@code systemPropertyVariables}, a WZ path picked at class load) is in
 * the snapshot and therefore survives untouched. Tests that need a property set should set it in the test body (or in
 * {@code @BeforeEach}); nothing they leave behind escapes.
 */
public final class PropsRestored implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(PropsRestored.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        Properties snapshot = new Properties();
        snapshot.putAll(System.getProperties());
        context.getStore(NS).put(context.getUniqueId(), snapshot);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Properties snapshot = context.getStore(NS).remove(context.getUniqueId(), Properties.class);
        if (snapshot != null) {
            System.setProperties(snapshot);
        }
    }
}
