package soloMapling.menus;

import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds map objects drawn in front of an NPC's sprite: objects on a layer above the NPC's foothold layer whose first-frame
 * canvas box overlaps the sprite box (half-width {@code hw} either side of x, 45 px up from the ground). Same-layer
 * objects draw before the NPC, so they do not count. Boxes are canvas rectangles, so this errs on the cautious side.
 */
final class FrontObjects {

    private FrontObjects() {
    }

    static List<String> covering(Path wz, int map, int x, int y, int fh, int hw) throws Exception {
        Element root = parse(wz.resolve("Map.wz/Map/Map" + map / 100000000 + "/" + map + ".img.xml"));
        int fhLayer = footholdLayer(child(root, "foothold"), fh);
        List<String> out = new ArrayList<>();
        for (int layer = fhLayer + 1; layer <= 7; layer++) {
            Element l = child(root, String.valueOf(layer));
            Element objs = l == null ? null : child(l, "obj");
            if (objs == null) {
                continue;
            }
            for (Element o : kids(objs)) {
                Map<String, String> v = props(o);
                int[] c = canvas(wz, v.get("oS"), v.get("l0"), v.get("l1"), v.get("l2"));   // w, h, ox, oy
                if (c == null) {
                    continue;
                }
                boolean flip = "1".equals(v.get("f"));
                int x0 = Integer.parseInt(v.get("x")) - (flip ? c[0] - c[2] : c[2]);
                int y0 = Integer.parseInt(v.get("y")) - c[3];
                if (x0 + c[0] > x - hw && x0 < x + hw && y0 + c[1] > y - 45 && y0 < y) {
                    out.add("layer " + layer + " " + v.get("oS") + "/" + v.get("l0") + "/" + v.get("l1") + "/" + v.get("l2"));
                }
            }
        }
        return out;
    }

    /**
     * Reachability heuristic: the foothold is part of a connected chain (prev/next links) at least {@code minWidth} px
     * wide, and a portal or spawn point lies within 150 px above or below the spot. Returns the problem, or null.
     */
    static String unreachable(Path wz, int map, int y, int fh, int minWidth) throws Exception {
        Element root = parse(wz.resolve("Map.wz/Map/Map" + map / 100000000 + "/" + map + ".img.xml"));
        Map<Integer, Map<String, String>> all = new HashMap<>();
        for (Element layer : kids(child(root, "foothold"))) {
            for (Element group : kids(layer)) {
                for (Element f : kids(group)) {
                    all.put(Integer.parseInt(f.getAttribute("name")), props(f));
                }
            }
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.Deque<Integer> todo = new java.util.ArrayDeque<>(List.of(fh));
        seen.add(fh);
        int width = 0;
        while (!todo.isEmpty()) {
            Map<String, String> f = all.get(todo.pop());
            width += Math.abs(Integer.parseInt(f.get("x2")) - Integer.parseInt(f.get("x1")));
            for (String link : new String[]{"prev", "next"}) {
                int n = Integer.parseInt(f.getOrDefault(link, "0"));
                if (n != 0 && all.containsKey(n) && seen.add(n)) {
                    todo.push(n);
                }
            }
        }
        if (width < minWidth) {
            return "foothold chain only " + width + " px wide";
        }
        for (Element p : kids(child(root, "portal"))) {
            if (Math.abs(Integer.parseInt(props(p).get("y")) - y) <= 150) {
                return null;
            }
        }
        return "no portal or spawn within 150 px vertically";
    }

    private static final Map<String, Element> CACHE = new HashMap<>();

    private static int[] canvas(Path wz, String oS, String l0, String l1, String l2) throws Exception {
        Element e = CACHE.get(oS);
        if (e == null) {
            Path p = wz.resolve("Map.wz/Obj/" + oS + ".img.xml");
            if (!Files.exists(p)) {
                return null;
            }
            e = parse(p);
            CACHE.put(oS, e);
        }
        for (String n : new String[]{l0, l1, l2, "0"}) {
            e = e == null ? null : child(e, n);
        }
        if (e == null || !"canvas".equals(e.getTagName())) {
            return null;
        }
        Element origin = child(e, "origin");
        return new int[]{Integer.parseInt(e.getAttribute("width")), Integer.parseInt(e.getAttribute("height")),
                Integer.parseInt(origin.getAttribute("x")), Integer.parseInt(origin.getAttribute("y"))};
    }

    private static int footholdLayer(Element footholds, int id) {
        for (Element layer : kids(footholds)) {
            for (Element group : kids(layer)) {
                for (Element f : kids(group)) {
                    if (String.valueOf(id).equals(f.getAttribute("name"))) {
                        return Integer.parseInt(layer.getAttribute("name"));
                    }
                }
            }
        }
        throw new IllegalArgumentException("no foothold " + id);
    }

    private static Element parse(Path p) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(p.toFile()).getDocumentElement();
    }

    private static Element child(Element e, String name) {
        for (Element c : kids(e)) {
            if (name.equals(c.getAttribute("name"))) {
                return c;
            }
        }
        return null;
    }

    private static List<Element> kids(Element e) {
        List<Element> out = new ArrayList<>();
        for (var n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                out.add(c);
            }
        }
        return out;
    }

    private static Map<String, String> props(Element e) {
        Map<String, String> m = new HashMap<>();
        for (Element v : kids(e)) {
            m.put(v.getAttribute("name"), v.getAttribute("value"));
        }
        return m;
    }
}
