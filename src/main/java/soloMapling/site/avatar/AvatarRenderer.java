package soloMapling.site.avatar;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a character standing (frame 0 of {@code stand1}, or {@code stand2} under a two-handed weapon, face
 * {@code default}) the way the client composes it, for the website's rankings: every part is a canvas with an origin
 * and named map points ({@code navel}, {@code neck}, {@code hand}, {@code brow}); the body is placed first and each
 * other part is moved so that one of its map points lands on the same point of a part already placed. Parts are drawn
 * back to front in {@code zmap.img}'s order. A part is left out when a layer it draws on ({@code smap.img}) is taken by
 * a higher kind of part: a cap's {@code vslot} "CpH1H5" takes the hair's {@code hairOverHead} layer (H1), so only the
 * hair under the cap shows. A missing file, frame or pixel format only drops that part.
 */
public final class AvatarRenderer {

    public static final int SIZE = 96;
    /** Where the feet stand in the picture (a few pixels of floor, as on the old official rankings). */
    public static final int FEET_Y = 90;

    /** Which kind of part: a higher kind hides a lower one's layers it takes (cap over hair over face over body). */
    public enum Kind {
        BODY, FACE, HAIR, EQUIP
    }

    /** One canvas to draw: its pixels (null in tests), origin, map points, layer and the vslot of the item it is from. */
    public record Part(String name, Kind kind, String z, int originX, int originY, Map<String, int[]> map,
                       BufferedImage image, int width, int height, String vslot) {
    }

    /** A part where it goes: the canvas's top-left corner, relative to the feet. */
    public record Placed(Part part, int x, int y) {
    }

    /** Where the client data comes from: an .img by its path under {@code Data/}, or null when it is not there. */
    public interface Source {
        ImgFile.Node img(String path);
    }

    private final Source source;
    private final Map<String, Integer> zIndex = new HashMap<>();
    private final Map<String, String> smap = new HashMap<>();

    public AvatarRenderer(Source source) {
        this.source = source;
        ImgFile.Node z = source.img("zmap.img");
        if (z == null) {
            z = source.img("Base/zmap.img");
        }
        if (z != null) {
            for (ImgFile.Node n : z.children) {
                zIndex.putIfAbsent(n.name, zIndex.size());
            }
        }
        ImgFile.Node s = source.img("smap.img");
        if (s == null) {
            s = source.img("Base/smap.img");
        }
        if (s != null) {
            for (ImgFile.Node n : s.children) {
                if (n.string() != null) {
                    smap.put(n.name, n.string());
                }
            }
        }
    }

    /** For tests: the layer order (front first) and the layer slots, as zmap.img and smap.img give them. */
    AvatarRenderer(List<String> zmap, Map<String, String> smapIn) {
        this.source = path -> null;
        for (String z : zmap) {
            zIndex.putIfAbsent(z, zIndex.size());
        }
        smap.putAll(smapIn);
    }

    // ── the picture ─────────────────────────────────────────────────────────────────────────────

    /** The 96x96 picture of a look, transparent around the character. */
    public BufferedImage render(AvatarLook look) {
        return frame(place(visible(parts(look))));
    }

    /** The parts a look is made of, in the order they are anchored (body first). */
    List<Part> parts(AvatarLook look) {
        String stance = "stand1";
        int weapon = look.weapon();
        if (weapon > 0) {
            ImgFile.Node w = img(equipPath(weapon));
            if (w != null && w.get("info/stand") != null && w.get("info/stand").intValue(1) == 2) {
                stance = "stand2";
            }
        }
        List<Part> out = new ArrayList<>();
        int skin = look.skin();
        ImgFile.Node body = img(String.format("Character/%08d.img", 2000 + skin));
        if (body == null && skin != 0) {
            skin = 0;
            body = img("Character/00002000.img");
        }
        addFrame(out, body, frameOf(body, stance), Kind.BODY, skin);
        ImgFile.Node head = img(String.format("Character/%08d.img", 12000 + skin));
        ImgFile.Node headFrame = frameOf(head, stance);
        addFrame(out, head, headFrame != null ? headFrame : head == null ? null : head.get("front"), Kind.BODY, skin);
        ImgFile.Node face = img(String.format("Character/Face/%08d.img", look.face()));
        addFrame(out, face, face == null ? null : face.get("default"), Kind.FACE, skin);
        ImgFile.Node hair = img(String.format("Character/Hair/%08d.img", look.hair()));
        ImgFile.Node hairFrame = frameOf(hair, stance);
        addFrame(out, hair, hairFrame != null ? hairFrame : hair == null ? null : hair.get("default"), Kind.HAIR, skin);
        for (AvatarLook.Shown s : look.shown()) {
            ImgFile.Node img = img(equipPath(s.itemId()));
            if (img == null) {
                continue;
            }
            ImgFile.Node root = img;
            if (s.underWeapon() > 0) { // a cash weapon keeps one drawing per weapon type
                root = img.get(String.valueOf(s.underWeapon() / 10000 % 100));
                if (root == null) {
                    continue;
                }
            }
            ImgFile.Node frame = s.itemId() / 10000 >= 101 && s.itemId() / 10000 <= 103
                    ? root.get("default") : frameOf(root, stance);
            if (frame == null) {
                frame = root.get("default");
            }
            addFrame(out, img, frame, Kind.EQUIP, skin);
        }
        return out;
    }

    private ImgFile.Node img(String path) {
        return path == null ? null : source.img(path);
    }

    /** {@code <stance>/0}, else {@code stand1/0}. */
    private static ImgFile.Node frameOf(ImgFile.Node img, String stance) {
        if (img == null) {
            return null;
        }
        ImgFile.Node f = img.get(stance + "/0");
        return f != null ? f : img.get("stand1/0");
    }

    private static void addFrame(List<Part> out, ImgFile.Node img, ImgFile.Node frame, Kind kind, int skin) {
        if (img == null || frame == null) {
            return;
        }
        ImgFile.Node vs = img.get("info/vslot");
        String vslot = vs == null || vs.string() == null ? "" : vs.string();
        for (ImgFile.Node c : frame.children) {
            ImgFile.Node n = ImgFile.Node.resolve(c);
            if (n != null && "property".equals(n.kind)) { // per-skin canvases (hairShade/0, /1, ...)
                ImgFile.Node bySkin = n.get(String.valueOf(skin));
                n = bySkin != null ? bySkin : n.get("0");
            }
            if (n == null || !"canvas".equals(n.kind)) {
                continue;
            }
            BufferedImage image = n.image();
            if (image == null) {
                continue;
            }
            ImgFile.Node origin = n.child("origin");
            Map<String, int[]> map = new LinkedHashMap<>();
            ImgFile.Node m = n.child("map");
            if (m != null) {
                for (ImgFile.Node v : m.children) {
                    if ("vector".equals(v.kind)) {
                        map.put(v.name, new int[]{v.x(), v.y()});
                    }
                }
            }
            ImgFile.Node z = n.child("z");
            String layer = z == null || z.string() == null ? c.name : z.string();
            out.add(new Part(c.name, kind, layer, origin == null ? 0 : origin.x(), origin == null ? 0 : origin.y(), map,
                    image, n.width(), n.height(), vslot));
        }
    }

    // ── which parts show ────────────────────────────────────────────────────────────────────────

    /**
     * Drops each part whose layer slots ({@code smap}) include a slot taken ({@code vslot}) by a part of a higher kind:
     * a cap taking H1 hides the hair's {@code hairOverHead}; a helmet taking Fc hides the face. Parts of one kind never
     * hide each other (a face accessory does not hide the cap).
     */
    List<Part> visible(List<Part> parts) {
        Map<String, Kind> owner = new HashMap<>();
        for (Part p : parts) {
            for (int i = 0; i + 1 < p.vslot().length(); i += 2) {
                String code = p.vslot().substring(i, i + 2);
                Kind k = owner.get(code);
                if (k == null || p.kind().ordinal() > k.ordinal()) {
                    owner.put(code, p.kind());
                }
            }
        }
        List<Part> out = new ArrayList<>();
        for (Part p : parts) {
            String slots = smap.getOrDefault(p.z(), "");
            boolean hidden = false;
            for (int i = 0; i + 1 < slots.length() && !hidden; i += 2) {
                Kind k = owner.get(slots.substring(i, i + 2));
                hidden = k != null && k.ordinal() > p.kind().ordinal();
            }
            if (!hidden) {
                out.add(p);
            }
        }
        return out;
    }

    // ── where they go ───────────────────────────────────────────────────────────────────────────

    /**
     * Anchors every part: the first part (the body) has its origin at the feet (0, 0) and records its map points;
     * each other part goes where one of its map points meets the same point already known, and adds its own points.
     * A part with no map points stands on the feet; one whose points never meet anything is left out. Back to front.
     */
    List<Placed> place(List<Part> parts) {
        Map<String, int[]> anchors = new HashMap<>();
        List<Placed> placed = new ArrayList<>();
        List<Part> pending = new ArrayList<>(parts);
        boolean first = true;
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (int i = 0; i < pending.size(); i++) {
                Part p = pending.get(i);
                int[] at = null;
                if (first || p.map().isEmpty()) {
                    at = new int[]{0, 0};
                } else {
                    for (Map.Entry<String, int[]> e : p.map().entrySet()) {
                        int[] a = anchors.get(e.getKey());
                        if (a != null) {
                            at = new int[]{a[0] - e.getValue()[0], a[1] - e.getValue()[1]};
                            break;
                        }
                    }
                }
                if (at == null) {
                    continue;
                }
                first = false;
                for (Map.Entry<String, int[]> e : p.map().entrySet()) {
                    anchors.putIfAbsent(e.getKey(), new int[]{at[0] + e.getValue()[0], at[1] + e.getValue()[1]});
                }
                placed.add(new Placed(p, at[0] - p.originX(), at[1] - p.originY()));
                pending.remove(i--);
                progress = true;
            }
        }
        List<Placed> ordered = new ArrayList<>(placed);
        ordered.sort((a, b) -> Integer.compare(z(b.part()), z(a.part()))); // stable: equal layers keep their order
        return ordered;
    }

    /** The part's layer depth: 0 is the front. An unknown layer goes just in front of the body. */
    int z(Part p) {
        Integer z = zIndex.get(p.z());
        if (z == null) {
            z = zIndex.get(p.name());
        }
        if (z == null) {
            z = zIndex.getOrDefault("body", 0) - 1;
        }
        return z;
    }

    // ── the 96x96 frame ─────────────────────────────────────────────────────────────────────────

    /**
     * The placed parts on a transparent 96x96 canvas: feet at {@link #FEET_Y}, the body (body and head) centred across,
     * so a long weapon or a weapon's glow is cut at the edge rather than pushing the character aside. A tall hat moves
     * the feet down to fit.
     */
    BufferedImage frame(List<Placed> placed) {
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        if (placed.isEmpty()) {
            return out;
        }
        Rectangle all = null;
        Rectangle body = null;
        for (Placed p : placed) {
            Rectangle r = new Rectangle(p.x(), p.y(), p.part().width(), p.part().height());
            all = all == null ? r : all.union(r);
            if (p.part().kind() == Kind.BODY) {
                body = body == null ? r : body.union(r);
            }
        }
        Rectangle centre = body == null ? all : body;
        int dx = SIZE / 2 - (centre.x + centre.width / 2);
        int dy = FEET_Y;
        if (all.y + dy < 0) {
            dy = Math.min(SIZE - 1, dy - (all.y + dy));
        }
        Graphics2D g = out.createGraphics();
        try {
            for (Placed p : placed) {
                if (p.part().image() != null) {
                    g.drawImage(p.part().image(), p.x() + dx, p.y() + dy, null);
                }
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    // ── paths ───────────────────────────────────────────────────────────────────────────────────

    /** An equip's .img under {@code Data/}: {@code Character/Cap/01002140.img}; null for an id with no avatar art. */
    public static String equipPath(int itemId) {
        String dir = equipDir(itemId);
        return dir == null ? null : String.format("Character/%s/%08d.img", dir, itemId);
    }

    static String equipDir(int itemId) {
        int p = itemId / 10000;
        if (p == 100) {
            return "Cap";
        }
        if (p >= 101 && p <= 103) {
            return "Accessory";
        }
        return switch (p) {
            case 104 -> "Coat";
            case 105 -> "Longcoat";
            case 106 -> "Pants";
            case 107 -> "Shoes";
            case 108 -> "Glove";
            case 109 -> "Shield";
            case 110 -> "Cape";
            default -> p >= 130 && p <= 170 ? "Weapon" : null;
        };
    }
}
