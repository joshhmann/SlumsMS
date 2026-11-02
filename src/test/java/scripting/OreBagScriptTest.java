package scripting;

import client.Client;
import client.Character;
import org.junit.jupiter.api.Test;
import scripting.item.ItemScriptMethods;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: scripts/item/orebag.js asked for im.sendOreStorage() - the ore-bag storage window, declared in
 * scripts/lib.d.ts:4794 - and then im.dispose(). Neither exists in this server: the only Storage is the bank
 * Storage Keeper opens (server/Storage), and ItemScriptMethods inherits nothing that opens a window or disposes a
 * dialog. The script therefore died on its first action. No UI can be invented here, so it has to say so.
 *
 * <p>The item script is also unreachable today (ItemScriptManager only runs scripts for 243xxxx items, and the ore
 * bag is a 4xxxxxx bag), which is why this is a fail-safe and a note rather than a feature.
 */
class OreBagScriptTest {
    @Test
    void theOreBagTellsThePlayerItIsClosedInsteadOfThrowing() throws Exception {
        Client client = mock(Client.class);
        Character player = mock(Character.class);
        when(client.getPlayer()).thenReturn(player);

        ScriptEngine engine = new Loader().load("item/orebag.js");
        assertNotNull(engine, "scripts/item/orebag.js did not evaluate");
        engine.put("im", new ItemScriptMethods(client));
        ((Invocable) engine).invokeFunction("start");

        verify(player).dropMessage(eq(5), contains("ore bag"));
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
