package server;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import soloMapling.WzTestData;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 b-20 (B-20/B-22: "Crystal Ilbi won't recharge", widened by the owner to every rechargeable): every throwing star,
 * bullet and capsule that WZ gives a unitPrice must be (a) offered for recharge by every NPC shop
 * ({@link Shop#rechargeables}) and (b) priced for a refill ({@link ShopMath#rechargePrice}). Before: Crystal Ilbi 2070016
 * and Balanced Fury 2070018 have a unitPrice but no info/price, so the refill price was -1 and Shop.recharge returned
 * without a word; Bullet for Novice Pirates 2330006 was outside the shop list (BULLET_MAX 2330005).
 */
class ShopRechargeAllRechargeablesTest {

    record WzRechargeable(int id, int price, double unitPrice, int slotMax) {
    }

    @Test
    void theRefillPriceRule() {
        assertEquals(250 + 9999, ShopMath.rechargePrice(250, 1.0, 9999), "price + ceil(qty x unitPrice), as b289");
        assertEquals(9999, ShopMath.rechargePrice(-1, 1.0, 9999), "no WZ price: the unitPrice alone");
        assertEquals(2, ShopMath.rechargePrice(-1, 0.6, 3), "rounded up");
        assertEquals(-1, ShopMath.rechargePrice(-1, 0.0, 9999), "neither: nothing to charge");
    }

    @Test
    void everyWzRechargeableIsOfferedAndPriced() throws Exception {
        WzTestData.assumePresent("Item.wz");
        List<WzRechargeable> all = new ArrayList<>();
        for (String img : List.of("0207.img.xml", "0233.img.xml")) {
            all.addAll(read(WzTestData.dir().resolve("Item.wz").resolve("Consume").resolve(img)));
        }
        assertTrue(all.size() >= 25, "read " + all.size() + " rechargeables from WZ");
        Set<Integer> offered = Shop.rechargeables();
        List<String> bad = new ArrayList<>();
        for (WzRechargeable r : all) {
            if (r.unitPrice() <= 0) {
                continue;
            }
            if (!offered.contains(r.id())) {
                bad.add(r.id() + " not offered for recharge");
            }
            int refill = ShopMath.rechargePrice(r.price(), r.unitPrice(), r.slotMax());
            if (refill < 0) {
                bad.add(r.id() + " has no refill price (WZ price " + r.price() + ", unitPrice " + r.unitPrice() + ")");
            }
        }
        assertEquals(List.of(), bad);
        assertTrue(offered.contains(2070016), "Crystal Ilbi (B-20/B-22)");
    }

    private static List<WzRechargeable> read(Path file) throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        List<WzRechargeable> out = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element item)) {
                continue;
            }
            int price = -1, slotMax = 0;
            double unitPrice = 0;
            for (Node i = item.getFirstChild(); i != null; i = i.getNextSibling()) {
                if (i instanceof Element info && "info".equals(info.getAttribute("name"))) {
                    for (Node v = info.getFirstChild(); v != null; v = v.getNextSibling()) {
                        if (v instanceof Element e) {
                            switch (e.getAttribute("name")) {
                                case "price" -> price = Integer.parseInt(e.getAttribute("value"));
                                case "unitPrice" -> unitPrice = Double.parseDouble(e.getAttribute("value"));
                                case "slotMax" -> slotMax = Integer.parseInt(e.getAttribute("value"));
                                default -> {
                                }
                            }
                        }
                    }
                }
            }
            out.add(new WzRechargeable(Integer.parseInt(item.getAttribute("name")), price, unitPrice, slotMax));
        }
        return out;
    }
}
