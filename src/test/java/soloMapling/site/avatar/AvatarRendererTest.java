package soloMapling.site.avatar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/** The ranking pictures: layer order, anchoring, cash covers, hidden layers, the cache key, missing parts, the batch. */
class AvatarRendererTest {

    static final List<String> ZMAP = List.of("weapon", "hairOverHead", "cap", "hair", "face", "head", "arm", "body",
            "hairBelowBody");
    static final Map<String, String> SMAP = Map.of("hairOverHead", "H1", "hair", "H2", "cap", "CpHdH1H2", "face", "Fc",
            "head", "Hd", "body", "Bd", "arm", "Bd", "weapon", "Wp", "hairBelowBody", "Hb");

    static AvatarRenderer.Part part(String name, AvatarRenderer.Kind kind, int ox, int oy, Map<String, int[]> map,
                                    String vslot) {
        return new AvatarRenderer.Part(name, kind, name, ox, oy, map, null, 10, 10, vslot);
    }

    static Map<String, int[]> map(Object... kv) {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.put((String) kv[i], new int[]{(int) kv[i + 1], (int) kv[i + 2]});
        }
        return m;
    }

    // ── anchoring and layers ────────────────────────────────────────────────────────────────────

    @Test
    void partsAnchorOnTheSameMapPointOfAPartAlreadyPlaced() {
        AvatarRenderer r = new AvatarRenderer(ZMAP, SMAP);
        // Body: origin (19, 32) at the feet; neck 4 left of and 32 above the feet.
        AvatarRenderer.Part body = part("body", AvatarRenderer.Kind.BODY, 19, 32, map("neck", -4, -32, "navel", -6, -20), "Bd");
        // Head: neck 15 below its origin, brow 4 left of and 5 above it.
        AvatarRenderer.Part head = part("head", AvatarRenderer.Kind.BODY, 19, 17, map("neck", 0, 15, "brow", -4, -5), "Hd");
        // Face: anchored by the head's brow, which only exists once the head is placed (listed first on purpose).
        AvatarRenderer.Part face = part("face", AvatarRenderer.Kind.FACE, 13, 8, map("brow", -1, -12), "Fc");
        AvatarRenderer.Part lost = part("arm", AvatarRenderer.Kind.BODY, 0, 0, map("elbow", 1, 1), "");
        Map<String, AvatarRenderer.Placed> at = new HashMap<>();
        for (AvatarRenderer.Placed p : r.place(List.of(body, face, head, lost))) {
            at.put(p.part().name(), p);
        }
        assertEquals(-19, at.get("body").x());
        assertEquals(-32, at.get("body").y());
        // head origin = body neck (-4, -32) - head neck (0, 15) = (-4, -47); top-left = origin - (19, 17)
        assertEquals(-4 - 19, at.get("head").x());
        assertEquals(-47 - 17, at.get("head").y());
        // head brow = (-4, -47) + (-4, -5) = (-8, -52); face origin = brow - (-1, -12) = (-7, -40); minus (13, 8)
        assertEquals(-7 - 13, at.get("face").x());
        assertEquals(-40 - 8, at.get("face").y());
        assertFalse(at.containsKey("arm"), "a part whose map points meet nothing is left out");
    }

    @Test
    void partsAreDrawnBackToFrontInZmapOrder() {
        AvatarRenderer r = new AvatarRenderer(ZMAP, SMAP);
        List<AvatarRenderer.Part> parts = List.of(
                part("body", AvatarRenderer.Kind.BODY, 0, 0, map("navel", 0, 0), "Bd"),
                part("weapon", AvatarRenderer.Kind.EQUIP, 0, 0, map("navel", 0, 0), "Wp"),
                part("hairBelowBody", AvatarRenderer.Kind.HAIR, 0, 0, map("navel", 0, 0), "H2Hb"),
                part("face", AvatarRenderer.Kind.FACE, 0, 0, map("navel", 0, 0), "Fc"),
                part("arm", AvatarRenderer.Kind.BODY, 0, 0, map("navel", 0, 0), "Bd"));
        List<String> order = r.place(parts).stream().map(p -> p.part().name()).toList();
        assertEquals(List.of("hairBelowBody", "body", "arm", "face", "weapon"), order);
    }

    @Test
    void aCapHidesTheHairLayersItTakesButNotTheOthers() {
        AvatarRenderer r = new AvatarRenderer(ZMAP, SMAP);
        List<AvatarRenderer.Part> parts = List.of(
                part("head", AvatarRenderer.Kind.BODY, 0, 0, Map.of(), "Hd"),
                part("face", AvatarRenderer.Kind.FACE, 0, 0, Map.of(), "Fc"),
                part("hairOverHead", AvatarRenderer.Kind.HAIR, 0, 0, Map.of(), "H1H2Hb"),
                part("hair", AvatarRenderer.Kind.HAIR, 0, 0, Map.of(), "H1H2Hb"),
                part("cap", AvatarRenderer.Kind.EQUIP, 0, 0, Map.of(), "CpH1"),
                part("weapon", AvatarRenderer.Kind.EQUIP, 0, 0, Map.of(), "WpCp"));
        List<String> shown = r.visible(parts).stream().map(AvatarRenderer.Part::name).toList();
        assertEquals(List.of("head", "face", "hair", "cap", "weapon"), shown,
                "H1 is the cap's: hairOverHead goes; the cap's own layer lists Hd and H2 but they are lower kinds");

        List<AvatarRenderer.Part> helmet = List.of(
                part("face", AvatarRenderer.Kind.FACE, 0, 0, Map.of(), "Fc"),
                part("hair", AvatarRenderer.Kind.HAIR, 0, 0, Map.of(), "H1H2"),
                part("cap", AvatarRenderer.Kind.EQUIP, 0, 0, Map.of(), "CpH1H2Fc"));
        assertEquals(List.of("cap"), r.visible(helmet).stream().map(AvatarRenderer.Part::name).toList());
    }

    @Test
    void theFrameCentresTheBodyWithTheFeetNearTheBottom() {
        AvatarRenderer r = new AvatarRenderer(ZMAP, SMAP);
        BufferedImage red = solid(20, 30, 0xffff0000);
        BufferedImage blue = solid(40, 4, 0xff0000ff);
        AvatarRenderer.Part body = new AvatarRenderer.Part("body", AvatarRenderer.Kind.BODY, "body", 10, 30,
                map("hand", 8, -10), red, 20, 30, "Bd");
        // A long weapon sticking out to the left: it must not pull the body off centre.
        AvatarRenderer.Part weapon = new AvatarRenderer.Part("weapon", AvatarRenderer.Kind.EQUIP, "weapon", 60, 2,
                map("hand", 0, 0), blue, 40, 4, "Wp");
        BufferedImage img = r.frame(r.place(List.of(body, weapon)));
        assertEquals(AvatarRenderer.SIZE, img.getWidth());
        assertEquals(AvatarRenderer.SIZE, img.getHeight());
        assertEquals(0xffff0000, img.getRGB(38, AvatarRenderer.FEET_Y - 1), "body's left edge at 48 - 10");
        assertEquals(0xffff0000, img.getRGB(57, AvatarRenderer.FEET_Y - 30), "body's right edge, top row");
        assertEquals(0, img.getRGB(58, AvatarRenderer.FEET_Y - 1) >>> 24);
        assertEquals(0, img.getRGB(48, AvatarRenderer.FEET_Y) >>> 24, "nothing below the feet");
    }

    // ── looks ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void cashCoversReplaceTheItemTheyCover() {
        AvatarLook look = new AvatarLook(0, 30000, 20000, Map.of(
                -1, 1002140, -101, 1002186, // cap under a cash hat
                -5, 1040002,                // a plain top
                -11, 1402000, -111, 1702000, // a two-handed sword under a cash weapon
                -12, 1112000));             // a ring: not drawn
        assertEquals(List.of(new AvatarLook.Shown(1, 1002186, 0), new AvatarLook.Shown(5, 1040002, 0),
                new AvatarLook.Shown(11, 1702000, 1402000)), look.shown());
        assertEquals(1402000, look.weapon());

        AvatarLook cashOnly = new AvatarLook(0, 30000, 20000, Map.of(-111, 1702000));
        assertEquals(List.of(), cashOnly.shown(), "a cash weapon with no weapon under it is not drawn");
    }

    @Test
    void theCacheKeyFollowsWhatIsDrawnOnly() {
        Map<Integer, Integer> eq = new HashMap<>(Map.of(-1, 1002140, -5, 1040002, -11, 1302000));
        AvatarLook a = new AvatarLook(1, 30030, 20000, eq);
        assertEquals(a.cacheKey(), new AvatarLook(1, 30030, 20000, new LinkedHashMap<>(eq)).cacheKey());
        assertTrue(a.cacheKey().matches("[0-9a-f]{16}"));

        Map<Integer, Integer> ring = new HashMap<>(eq);
        ring.put(-12, 1112000);
        ring.put(-17, 1122000);
        assertEquals(a.cacheKey(), new AvatarLook(1, 30030, 20000, ring).cacheKey(), "rings and pendants are not drawn");

        Map<Integer, Integer> cash = new HashMap<>(eq);
        cash.put(-101, 1002186);
        assertNotEquals(a.cacheKey(), new AvatarLook(1, 30030, 20000, cash).cacheKey());
        assertNotEquals(a.cacheKey(), new AvatarLook(2, 30030, 20000, eq).cacheKey());
        assertNotEquals(a.cacheKey(), new AvatarLook(1, 30031, 20000, eq).cacheKey());
        assertNotEquals(a.cacheKey(), new AvatarLook(1, 30030, 20001, eq).cacheKey());
    }

    // ── from client files ───────────────────────────────────────────────────────────────────────

    /** A tiny client: body, head and hair (the hair's stand frame is a UOL to its default frame). */
    static Map<String, byte[]> client() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("zmap.img", img(w -> {
            w.count(ZMAP.size());
            for (String z : ZMAP) {
                w.nul(z);
            }
        }));
        files.put("smap.img", img(w -> {
            w.count(SMAP.size());
            SMAP.forEach(w::str);
        }));
        files.put("Character/00002000.img", img(w -> {
            w.count(2);
            w.prop("info", i -> {
                i.count(1);
                i.str("vslot", "Bd");
            });
            w.prop("stand1", s -> {
                s.count(1);
                s.prop("0", f -> {
                    f.count(1);
                    f.canvas("body", 6, 10, 0xff00ff00, 3, 10, "body", Map.of("neck", new int[]{0, -10}));
                });
            });
        }));
        files.put("Character/00012000.img", img(w -> {
            w.count(1);
            w.prop("stand1", s -> {
                s.count(1);
                s.prop("0", f -> {
                    f.count(1);
                    f.canvas("head", 8, 8, 0xffffcc99, 4, 4, "head", Map.of("neck", new int[]{0, 4},
                            "brow", new int[]{0, -2}));
                });
            });
        }));
        files.put("Character/Hair/00030000.img", img(w -> {
            w.count(3);
            w.prop("info", i -> {
                i.count(1);
                i.str("vslot", "H1H2");
            });
            w.prop("default", d -> {
                d.count(1);
                d.canvas("hair", 10, 3, 0xff663300, 5, 3, "hair", Map.of("brow", new int[]{0, 0}));
            });
            w.prop("stand1", s -> {
                s.count(1);
                s.prop("0", f -> {
                    f.count(1);
                    f.uol("hair", "../../default/hair");
                });
            });
        }));
        return files;
    }

    @Test
    void aClientImgParsesWithPixelsVectorsAndLinks() {
        ImgFile.Node hair = ImgFile.parse(client().get("Character/Hair/00030000.img"), "00030000.img");
        ImgFile.Node c = hair.get("stand1/0/hair");
        assertNotNull(c, "the UOL resolves to the default frame's canvas");
        assertEquals("canvas", c.kind);
        assertEquals(5, c.get("origin").x());
        assertEquals("hair", c.get("z").string());
        BufferedImage px = c.image();
        assertEquals(10, px.getWidth());
        assertEquals(0xff663300, px.getRGB(9, 2));
        assertEquals("H1H2", hair.get("info/vslot").string());
    }

    @Test
    void aWholeCharacterIsDrawnFromClientFilesAndMissingPartsAreSkipped() {
        Map<String, byte[]> files = client();
        AvatarRenderer r = new AvatarRenderer(path -> files.containsKey(path) ? ImgFile.parse(files.get(path), path) : null);
        // Face 20000, a cap and a cash weapon are not in this client: they are left out, the rest is drawn.
        AvatarLook look = new AvatarLook(0, 30000, 20000, Map.of(-1, 1002140, -11, 1302000, -111, 1702000));
        BufferedImage img = r.render(look);
        int feet = AvatarRenderer.FEET_Y;
        assertEquals(0xff00ff00, img.getRGB(48, feet - 1), "body");
        assertEquals(0xffffcc99, img.getRGB(48, feet - 10 - 4 - 1), "head above the neck");
        assertEquals(0xff663300, img.getRGB(48, feet - 10 - 4 - 2 - 1), "hair on the brow, over the head");

        // Nothing at all: a blank picture, no exception.
        BufferedImage blank = new AvatarRenderer(path -> null).render(look);
        assertEquals(0, blank.getRGB(48, 48) >>> 24);
        // A broken file counts as missing.
        files.put("Character/Hair/00030000.img", new byte[]{1, 2, 3});
        AvatarRenderer broken = new AvatarRenderer(path -> {
            try {
                return files.containsKey(path) ? ImgFile.parse(files.get(path), path) : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
        assertEquals(0xffffcc99, broken.render(look).getRGB(48, feet - 10 - 4 - 2 - 1), "no hair: the head shows");
    }

    // ── the batch ───────────────────────────────────────────────────────────────────────────────

    @Test
    void theBatchDrawsEachLookOnceAndWritesTheNameIndex(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("Data");
        for (Map.Entry<String, byte[]> e : client().entrySet()) {
            Files.createDirectories(data.resolve(e.getKey()).getParent());
            Files.write(data.resolve(e.getKey()), e.getValue());
        }
        Path boards = tmp.resolve("leaderboards.json");
        Files.writeString(boards, Json.write(Map.of(
                "level", List.of(Map.of("rank", 1, "name", "Alpha"), Map.of("rank", 2, "name", "Beta")),
                "levelByClass", Map.of("Warrior", List.of(Map.of("rank", 1, "name", "Alpha"))),
                "fame", List.of(Map.of("rank", 1, "name", "Gamma")),
                "guilds", List.of(Map.of("rank", 1, "name", "Guild", "leader", "Delta")),
                "bosses", Map.of("Zakum", List.of(Map.of("rank", 1, "name", "Beta"))))));
        assertEquals(List.of("Alpha", "Beta", "Gamma", "Delta"),
                List.copyOf(AvatarExport.names(Json.parse(Files.readString(boards)))));
        Path looks = tmp.resolve("looks.json");
        Files.writeString(looks, Json.write(List.of(
                Map.of("name", "Alpha", "skin", 0, "hair", 30000, "face", 20000, "equips", Map.of("-1", 1002140)),
                Map.of("name", "Beta", "skin", 0, "hair", 30000, "face", 20000, "equips", Map.of("-1", 1002140)),
                Map.of("name", "Gamma", "skin", 0, "hair", 30000, "face", 20000, "equips", Map.of()),
                Map.of("name", "Outsider", "skin", 0, "hair", 30000, "face", 20000, "equips", Map.of()))));
        Path out = tmp.resolve("site");
        AvatarExport.Options o = AvatarExport.Options.parse(new String[]{"--data=" + data, "--leaderboards=" + boards,
                "--out=" + out, "--looks=" + looks});
        Files.createDirectories(out.resolve("avatars"));
        Path stale = out.resolve("avatars/0123456789abcdef.png");
        Files.write(stale, new byte[]{1});
        Files.setLastModifiedTime(stale, FileTime.fromMillis(0));

        AvatarExport.Summary s = AvatarExport.run(o, new AvatarRenderer(new AvatarExport.DataDir(data)),
                AvatarExport.fileLooks(looks), 1_793_000_000_000L);
        assertEquals(4, s.names());
        assertEquals(2, s.looks(), "Alpha and Beta dress alike: one picture");
        assertEquals(2, s.drawn());
        assertEquals(1, s.pruned());
        assertFalse(Files.exists(stale));

        Map<?, ?> index = (Map<?, ?>) Json.parse(Files.readString(out.resolve("avatars/index.json")));
        assertEquals(1, ((Number) index.get("schema")).intValue());
        Map<?, ?> avatars = (Map<?, ?>) index.get("avatars");
        assertEquals(List.of("Alpha", "Beta", "Gamma"), avatars.keySet().stream().map(String::valueOf).sorted().toList(),
                "no look, no entry (Delta); never a name the boards do not list (Outsider)");
        assertEquals(avatars.get("Alpha"), avatars.get("Beta"));
        BufferedImage png = ImageIO.read(out.resolve("avatars").resolve((String) avatars.get("Gamma")).toFile());
        assertEquals(96, png.getWidth());
        assertFalse(Files.readString(out.resolve("avatars/index.json")).contains("30000"), "no look data in the index");

        AvatarExport.Summary again = AvatarExport.run(o, new AvatarRenderer(new AvatarExport.DataDir(data)),
                AvatarExport.fileLooks(looks), 1_793_000_900_000L);
        assertEquals(0, again.drawn());
        assertEquals(2, again.reused());
    }

    // ── a minimal .img writer (no key: the reader falls back to it after the GMS key) ───────────

    interface Body {
        void write(W w);
    }

    static final class W {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void b(int v) {
            out.write(v);
        }

        void i32(int v) {
            ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);
            out.writeBytes(bb.array());
        }

        void cint(int v) {
            if (v >= -127 && v <= 127) {
                b(v & 0xff);
            } else {
                b(0x80);
                i32(v);
            }
        }

        void string(String s) {
            byte[] raw = s.getBytes(StandardCharsets.ISO_8859_1);
            if (raw.length == 0) {
                b(0);
                return;
            }
            b((-raw.length) & 0xff);
            for (int i = 0; i < raw.length; i++) {
                b((raw[i] ^ (0xaa + i)) & 0xff);
            }
        }

        void name(String n) {
            b(0);
            string(n);
        }

        void count(int n) {
            cint(n);
        }

        void nul(String n) {
            name(n);
            b(0);
        }

        void str(String n, String v) {
            name(n);
            b(8);
            b(0);
            string(v);
        }

        void extended(String n, Body body) {
            name(n);
            b(9);
            W inner = new W();
            body.write(inner);
            byte[] bytes = inner.out.toByteArray();
            i32(bytes.length);
            out.writeBytes(bytes);
        }

        void prop(String n, Body children) {
            extended(n, w -> {
                w.b(0x73);
                w.string("Property");
                w.b(0);
                w.b(0);
                children.write(w);
            });
        }

        void uol(String n, String target) {
            extended(n, w -> {
                w.b(0x73);
                w.string("UOL");
                w.b(0);
                w.b(0);
                w.string(target);
            });
        }

        void vector(String n, int x, int y) {
            extended(n, w -> {
                w.b(0x73);
                w.string("Shape2D#Vector2D");
                w.cint(x);
                w.cint(y);
            });
        }

        void canvas(String n, int width, int height, int argb, int ox, int oy, String z, Map<String, int[]> map) {
            extended(n, w -> {
                w.b(0x73);
                w.string("Canvas");
                w.b(0);
                w.b(1);
                w.b(0);
                w.b(0);
                w.count(map.isEmpty() ? 2 : 3);
                w.vector("origin", ox, oy);
                w.str("z", z);
                if (!map.isEmpty()) {
                    w.prop("map", m -> {
                        m.count(map.size());
                        map.forEach((k, v) -> m.vector(k, v[0], v[1]));
                    });
                }
                w.cint(width);
                w.cint(height);
                w.cint(2);
                w.b(0);
                w.i32(0);
                byte[] px = new byte[width * height * 4];
                for (int i = 0; i < width * height; i++) {
                    px[4 * i] = (byte) argb;
                    px[4 * i + 1] = (byte) (argb >> 8);
                    px[4 * i + 2] = (byte) (argb >> 16);
                    px[4 * i + 3] = (byte) (argb >>> 24);
                }
                Deflater d = new Deflater();
                d.setInput(px);
                d.finish();
                byte[] buf = new byte[px.length + 64];
                int len = d.deflate(buf);
                d.end();
                w.i32(len + 1);
                w.b(0);
                w.out.write(buf, 0, len);
            });
        }
    }

    static byte[] img(Body root) {
        W w = new W();
        w.b(0x73);
        w.string("Property");
        w.b(0);
        w.b(0);
        root.write(w);
        return w.out.toByteArray();
    }

    static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }
}
