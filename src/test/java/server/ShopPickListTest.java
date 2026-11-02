package server;

import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.4: the GM shop editor's shop picker calls cm.getAllShopIds() and iterates the result with
 * {@code for (const kv of shops.entrySet())}, printing #{shopid}# - #p{npcid}# (scripts/npc/ShopEditor.js:93-103).
 * No scripting manager had that method and ShopFactory had no way to enumerate the shops, so the picker - the only
 * way to reach the editor's per-shop menus - threw before the list was drawn.
 *
 * <p>The DB half is a single query behind ShopFactory's test seam; these check the seam and that the scripting API
 * really exposes it, without a database.
 */
class ShopPickListTest {
    private static final Map<Integer, Integer> SHOPS = new LinkedHashMap<>();

    static {
        SHOPS.put(11000, 11000);
        SHOPS.put(1337, 11000);
        SHOPS.put(9999992, 9001002);
    }

    private static ShopFactory factoryWith(Shop ignored) {
        return new ShopFactory(new ShopFactory.Loader() {
            @Override
            public Shop load(int id, boolean isShopId) {
                return null;
            }

            public Map<Integer, Integer> allShopIds() {
                return SHOPS;
            }
        });
    }

    private static Object pickList(ShopFactory factory) throws Exception {
        Method m = ShopFactory.class.getMethod("getAllShopIds");
        return m.invoke(factory);
    }

    @Test
    void theEditorGetsEveryShopAsAShopIdToNpcIdPair() throws Exception {
        assertEquals(SHOPS, pickList(factoryWith(null)));
    }

    @Test
    void theScriptingManagerExposesThePicker() throws Exception {
        Method m = NPCConversationManager.class.getMethod("getAllShopIds");
        assertEquals(Map.class, m.getReturnType(), "the script uses shops.size() and entrySet()");
    }

    @Test
    void aLoaderWithoutTheQuerySeesNoShopsRatherThanCrashing() throws Exception {
        assertTrue(((Map<?, ?>) pickList(new ShopFactory((id, byId) -> null))).isEmpty());
    }
}
