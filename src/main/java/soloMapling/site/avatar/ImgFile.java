package soloMapling.site.avatar;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads one v83 client {@code .img} file (the client's {@code Data/} folder layout: {@code Data/Character/Cap/01002140.img})
 * into a {@link Node} tree, pixels included. The server's WZ XML dump has every canvas's size, origin and map vectors
 * but no pixels, so the avatar pictures read the client's files (the same base layer the launcher ships and the site's
 * icon tool reads). Only what character art uses: properties, ints, strings, vectors, canvases and UOLs; sounds and
 * shapes are skipped. A malformed file throws {@link IllegalArgumentException}; callers treat that as a missing part.
 * Format reference: the client repo's tools/wz/native_wz.py (MapleLib-derived).
 */
public final class ImgFile {

    private static final byte[] AES_KEY = hex("130000000800000006000000b40000001b0000000f0000003300000052000000");
    private static final byte[] GMS_IV = hex("4d23c72b");
    private static final byte[] MSEA_IV = hex("b97d63e9");
    private static volatile byte[] gmsStream = new byte[0];
    private static volatile byte[] mseaStream = new byte[0];

    /** A property, canvas, vector, UOL or value. {@code parent} is null for the file's root. */
    public static final class Node {
        public final String name;
        public final String kind; // property, canvas, vector, uol, int, string, float, null
        public final Node parent;
        public final List<Node> children = new ArrayList<>();
        Object value;
        int width;
        int height;
        int format;
        byte[] zlib;

        Node(String name, String kind, Node parent) {
            this.name = name;
            this.kind = kind;
            this.parent = parent;
        }

        public Node child(String n) {
            for (Node c : children) {
                if (c.name.equals(n)) {
                    return c;
                }
            }
            return null;
        }

        /** The node at a slash path below this one, following UOLs on the way; null when any step is missing. */
        public Node get(String path) {
            Node n = this;
            for (String part : path.split("/")) {
                if (n == null) {
                    return null;
                }
                n = part.equals("..") ? n.parent : part.isEmpty() || part.equals(".") ? n : n.child(part);
                n = resolve(n);
            }
            return n;
        }

        /** Follows UOL links (relative to the UOL's parent), at most 8 deep; null for a broken link. */
        public static Node resolve(Node n) {
            for (int i = 0; n != null && "uol".equals(n.kind); i++) {
                if (i == 8 || n.parent == null) {
                    return null;
                }
                n = n.parent.get((String) n.value);
            }
            return n;
        }

        public int x() {
            return value instanceof int[] v ? v[0] : 0;
        }

        public int y() {
            return value instanceof int[] v ? v[1] : 0;
        }

        public String string() {
            return value instanceof String s ? s : value == null ? null : String.valueOf(value);
        }

        public int intValue(int fallback) {
            if (value instanceof Number n) {
                return n.intValue();
            }
            if (value instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
            return fallback;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** A canvas's pixels; null for an unsupported pixel format or broken data. */
        public BufferedImage image() {
            if (!"canvas".equals(kind) || zlib == null) {
                return null;
            }
            try {
                return decode(zlib, width, height, format);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private ImgFile() {
    }

    /** Parses a whole .img file; tries the GMS key, then no key, then the MSEA key. */
    public static Node parse(byte[] data, String name) {
        IllegalArgumentException first = null;
        for (int key = 0; key < 3; key++) {
            try {
                Reader r = new Reader(data, key);
                if (!"Property".equals(r.stringBlock()) || r.u8() != 0 || r.u8() != 0) {
                    throw new IllegalArgumentException("not a Property img");
                }
                Node root = new Node(name, "property", null);
                r.properties(root);
                return root;
            } catch (IllegalArgumentException | IndexOutOfBoundsException | java.nio.BufferUnderflowException e) {
                if (first == null) {
                    first = e instanceof IllegalArgumentException iae ? iae : new IllegalArgumentException(e.toString(), e);
                }
            }
        }
        throw first;
    }

    private static final class Reader {
        final ByteBuffer b;
        final int key;

        Reader(byte[] data, int key) {
            this.b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.key = key;
        }

        int u8() {
            return b.get() & 0xff;
        }

        int cint() {
            byte v = b.get();
            return v == -128 ? b.getInt() : v;
        }

        String string() {
            byte size = b.get();
            if (size == 0) {
                return "";
            }
            boolean unicode = size > 0;
            int n = size == 127 || size == -128 ? b.getInt() : Math.abs(size);
            if (n < 0 || n > 1_000_000) {
                throw new IllegalArgumentException("bad string length " + n);
            }
            byte[] raw = new byte[unicode ? n * 2 : n];
            b.get(raw);
            byte[] stream = keyStream(key, raw.length);
            for (int i = 0; i < raw.length; i++) {
                raw[i] ^= stream[i];
            }
            if (unicode) {
                char[] out = new char[n];
                for (int i = 0; i < n; i++) {
                    int mask = (0xaaaa + i) & 0xffff;
                    out[i] = (char) (((raw[2 * i] & 0xff) | (raw[2 * i + 1] & 0xff) << 8) ^ mask);
                }
                return new String(out);
            }
            for (int i = 0; i < n; i++) {
                raw[i] ^= (byte) (0xaa + i);
            }
            return new String(raw, StandardCharsets.ISO_8859_1);
        }

        String stringBlock() {
            int tag = u8();
            if (tag == 0 || tag == 0x73) {
                return string();
            }
            if (tag == 1 || tag == 0x1b) {
                int offset = b.getInt();
                int saved = b.position();
                b.position(offset);
                String s = string();
                b.position(saved);
                return s;
            }
            throw new IllegalArgumentException("bad string block " + tag);
        }

        void properties(Node parent) {
            int count = cint();
            if (count < 0 || count > 1_000_000) {
                throw new IllegalArgumentException("bad property count " + count);
            }
            for (int i = 0; i < count; i++) {
                String name = stringBlock();
                int tag = u8();
                Node n;
                switch (tag) {
                    case 0 -> n = new Node(name, "null", parent);
                    case 2, 11 -> {
                        n = new Node(name, "int", parent);
                        n.value = (int) b.getShort();
                    }
                    case 3, 19 -> {
                        n = new Node(name, "int", parent);
                        n.value = cint();
                    }
                    case 20 -> {
                        n = new Node(name, "int", parent);
                        byte v = b.get();
                        n.value = v == -128 ? b.getLong() : (long) v;
                    }
                    case 4 -> {
                        n = new Node(name, "float", parent);
                        n.value = u8() == 0x80 ? b.getFloat() : 0f;
                    }
                    case 5 -> {
                        n = new Node(name, "float", parent);
                        n.value = b.getDouble();
                    }
                    case 8 -> {
                        n = new Node(name, "string", parent);
                        n.value = stringBlock();
                    }
                    case 9 -> {
                        int length = b.getInt();
                        int end = b.position() + length;
                        n = extended(name, parent, end);
                        b.position(end); // skips what is not read (sounds), and keeps a mis-read local
                    }
                    default -> throw new IllegalArgumentException("bad property tag " + tag);
                }
                if (n != null) {
                    parent.children.add(n);
                }
            }
        }

        Node extended(String name, Node parent, int end) {
            String kind = stringBlock();
            switch (kind) {
                case "Property" -> {
                    b.position(b.position() + 2);
                    Node n = new Node(name, "property", parent);
                    properties(n);
                    return n;
                }
                case "Canvas" -> {
                    Node n = new Node(name, "canvas", parent);
                    u8();
                    if (u8() == 1) {
                        b.position(b.position() + 2);
                        properties(n);
                    }
                    n.width = cint();
                    n.height = cint();
                    n.format = cint() + u8();
                    b.position(b.position() + 4);
                    int size = b.getInt();
                    u8();
                    byte[] data = new byte[size - 1];
                    b.get(data);
                    n.zlib = zlibBytes(data, key);
                    return n;
                }
                case "Shape2D#Vector2D" -> {
                    Node n = new Node(name, "vector", parent);
                    n.value = new int[]{cint(), cint()};
                    return n;
                }
                case "UOL" -> {
                    u8();
                    Node n = new Node(name, "uol", parent);
                    n.value = stringBlock();
                    return n;
                }
                default -> {
                    return null; // Convex2D, Sound_DX8: no avatar art in them
                }
            }
        }
    }

    // ── canvases ────────────────────────────────────────────────────────────────────────────────

    private static boolean zlibHeader(byte[] d, int at) {
        return d.length > at + 1 && (d[at] & 0xff) == 0x78
                && ((d[at + 1] & 0xff) == 0x9c || (d[at + 1] & 0xff) == 0xda || (d[at + 1] & 0xff) == 0x01
                || (d[at + 1] & 0xff) == 0x5e);
    }

    /** The canvas's zlib stream: as stored, or un-XORed from the key stream in length-prefixed blocks. */
    static byte[] zlibBytes(byte[] data, int key) {
        if (zlibHeader(data, 0)) {
            return data;
        }
        ByteBuffer r = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        while (r.remaining() >= 4) {
            int size = r.getInt();
            if (size < 0 || size > r.remaining()) {
                return data; // not block-encrypted after all; decoding fails later and the part is skipped
            }
            byte[] stream = keyStream(key, size);
            for (int i = 0; i < size; i++) {
                out.write(r.get() ^ stream[i]);
            }
        }
        return out.toByteArray();
    }

    static BufferedImage decode(byte[] zlib, int w, int h, int format) {
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
            return null;
        }
        int expected = switch (format) {
            case 1, 513 -> w * h * 2;
            case 2 -> w * h * 4;
            case 517 -> w * h / 128;
            default -> -1; // DXT and others: v83 character art does not use them
        };
        if (expected <= 0) {
            return null;
        }
        byte[] raw = inflate(zlib, expected);
        if (raw == null) {
            return null;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[w * h];
        switch (format) {
            case 1 -> {
                for (int i = 0; i < w * h; i++) {
                    int lo = raw[2 * i] & 0xff;
                    int hi = raw[2 * i + 1] & 0xff;
                    int bl = lo & 0x0f;
                    int g = lo >> 4;
                    int r = hi & 0x0f;
                    int a = hi >> 4;
                    px[i] = (a * 17) << 24 | (r * 17) << 16 | (g * 17) << 8 | bl * 17;
                }
            }
            case 2 -> {
                for (int i = 0; i < w * h; i++) {
                    px[i] = (raw[4 * i + 3] & 0xff) << 24 | (raw[4 * i + 2] & 0xff) << 16 | (raw[4 * i + 1] & 0xff) << 8
                            | raw[4 * i] & 0xff;
                }
            }
            case 513 -> {
                for (int i = 0; i < w * h; i++) {
                    px[i] = rgb565((raw[2 * i] & 0xff) | (raw[2 * i + 1] & 0xff) << 8);
                }
            }
            default -> { // 517: one RGB565 colour per 16x16 block
                int bw = w / 16;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int bi = (y / 16) * bw + x / 16;
                        px[y * w + x] = 2 * bi + 1 < raw.length
                                ? rgb565((raw[2 * bi] & 0xff) | (raw[2 * bi + 1] & 0xff) << 8) : 0;
                    }
                }
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }

    private static int rgb565(int v) {
        int r = (v >> 11) & 0x1f;
        int g = (v >> 5) & 0x3f;
        int b = v & 0x1f;
        return 0xff000000 | (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
    }

    /** Inflates the declared pixel extent; some exporters leave the stream unterminated, so a short tail is padded. */
    private static byte[] inflate(byte[] zlib, int expected) {
        if (zlib.length < 2) {
            return null;
        }
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(zlib, 2, zlib.length - 2);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inf.finished()) {
                int got = inf.inflate(out, n, expected - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += got;
            }
            return n == expected ? out : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    // ── keys ────────────────────────────────────────────────────────────────────────────────────

    /** The WZ key stream for key 0 (GMS), 1 (none) or 2 (MSEA), at least {@code size} bytes long. */
    static byte[] keyStream(int key, int size) {
        if (key == 1) {
            return new byte[size];
        }
        byte[] s = key == 0 ? gmsStream : mseaStream;
        if (s.length >= size) {
            return s;
        }
        synchronized (ImgFile.class) {
            s = key == 0 ? gmsStream : mseaStream;
            if (s.length >= size) {
                return s;
            }
            byte[] grown = grow(key == 0 ? GMS_IV : MSEA_IV, Math.max(size, Math.max(4096, s.length * 2)));
            if (key == 0) {
                gmsStream = grown;
            } else {
                mseaStream = grown;
            }
            return grown;
        }
    }

    private static byte[] grow(byte[] iv, int size) {
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
            int blocks = (size + 15) / 16;
            byte[] out = new byte[blocks * 16];
            byte[] block = new byte[16];
            for (int i = 0; i < 16; i++) {
                block[i] = iv[i % 4];
            }
            for (int i = 0; i < blocks; i++) {
                block = aes.doFinal(block);
                System.arraycopy(block, 0, out, i * 16, 16);
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
