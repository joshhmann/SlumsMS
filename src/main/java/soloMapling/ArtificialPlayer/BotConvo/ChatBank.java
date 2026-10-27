package soloMapling.ArtificialPlayer.BotConvo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Ticket 59 (owner requirement 2026-10-22): the pre-generated line bank. Hundreds of thousands of short lines, used
 * whenever the LLM is busy, slow, over budget or down, so bots never go silent or repeat the same few lines.
 *
 * <p><b>File:</b> gzip TSV, one line per row: {@code id intent tone affinity voice tags text} ({@code tags}
 * comma-separated, {@code -} for none; {@code any} for an open tone, affinity or voice). Shipped as
 * {@code soloMapling/ArtificialPlayer/BotConvo/chatbank.bin} in the jar; {@code -Dsolomapling.dataDir} overrides it
 * with a bigger generated bank.
 *
 * <p><b>Memory:</b> loaded lazily on first use into one UTF-8 byte array with offsets, one byte per index field, a
 * tag bitmask per line and an int[] per (intent, affinity) cell. About 6 MB per 100k lines (ChatBankTest measures it).
 *
 * <p><b>Selection:</b> {@link #pick}: weighted random in the cell, exact voice and affinity first, then relaxing;
 * lines whose slots cannot be filled are skipped; the recently-used memory and the {@code !rate} weights apply.
 */
public final class ChatBank {

    private static final Logger log = LoggerFactory.getLogger(ChatBank.class);

    public static final String RESOURCE = "src/main/java/soloMapling/ArtificialPlayer/BotConvo/chatbank.bin";

    /** Situation tags (owner list), one bit each. */
    public enum Tag {
        TOWN, FM, GRIND, BOSS, PQ, PARTY, GUILD, TRADE, HAGGLE, DATE, BREAKUP, WAR, EVENT, NIGHT, WHISPER;

        final long bit = 1L << ordinal();

        public static Tag parse(String raw) {
            for (Tag t : values()) {
                if (t.name().equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
            return null;
        }

        public static long mask(Iterable<Tag> tags) {
            long m = 0;
            for (Tag t : tags) {
                m |= t.bit;
            }
            return m;
        }
    }

    /** Affinity keys (index field). */
    public static final List<String> AFFINITIES = List.of("any", "neutral", "friend", "rival", "nemesis", "partner", "ex");
    public static final List<String> TONES = List.of("any", "neutral", "kind", "rude", "flirty");
    /** Slots a line may carry; filled at runtime. */
    public static final List<String> SLOTS = List.of("player", "map", "mob", "item", "level", "job", "guild", "price",
            "partner");
    static final Pattern SLOT = Pattern.compile("\\{([a-z]+)}");

    // ── the loaded bank ──

    private final byte[] text;
    private final int[] offsets;
    private final int[] ids;
    private final byte[] intent;
    private final byte[] tone;
    private final byte[] affinity;
    private final byte[] voice;
    private final long[] tags;
    /** rc6 say-do: the action claims each line carries ({@link ClaimRules.Kind} bits, strong claims only). */
    private final long[] claims;
    private final List<String> intents;
    private final Map<Integer, int[]> cells;
    /** Rows sorted by id ({@code idOrder[k]} is the row with the k-th smallest id), for {@link #textOf}. */
    private final int[] idOrder;
    private final int[] sortedIds;

    private ChatBank(byte[] text, int[] offsets, int[] ids, byte[] intent, byte[] tone, byte[] affinity, byte[] voice,
                     long[] tags, long[] claims, List<String> intents, Map<Integer, int[]> cells) {
        this.claims = claims;
        this.text = text;
        this.offsets = offsets;
        this.ids = ids;
        this.intent = intent;
        this.tone = tone;
        this.affinity = affinity;
        this.voice = voice;
        this.tags = tags;
        this.intents = intents;
        this.cells = cells;
        Integer[] order = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> ids[i]));
        this.idOrder = new int[ids.length];
        this.sortedIds = new int[ids.length];
        for (int k = 0; k < ids.length; k++) {
            idOrder[k] = order[k];
            sortedIds[k] = ids[order[k]];
        }
    }

    public int size() {
        return ids.length;
    }

    public List<String> intents() {
        return intents;
    }

    /** The raw text of a bank line by its id, or null. */
    public String textOf(int id) {
        int k = java.util.Arrays.binarySearch(sortedIds, id);
        return k < 0 ? null : row(idOrder[k]);
    }

    String row(int i) {
        return new String(text, offsets[i], offsets[i + 1] - offsets[i], StandardCharsets.UTF_8);
    }

    /** Approximate heap held by the bank's arrays and index, in bytes. */
    public long heapBytes() {
        long b = text.length + 4L * offsets.length + 4L * ids.length + 4L * ids.length + 8L * tags.length
                + 8L * claims.length;
        for (int[] cell : cells.values()) {
            b += 16 + 4L * cell.length;
        }
        return b + 8L * sortedIds.length; // the id lookup
    }

    // ── loading ──

    private static volatile ChatBank shared;
    private static volatile boolean tried;

    /** The server's bank, loaded on first use; null when there is none (the scripted lines answer). */
    public static ChatBank get() {
        if (!tried) {
            synchronized (ChatBank.class) {
                if (!tried) {
                    shared = loadDefault();
                    tried = true;
                }
            }
        }
        return shared;
    }

    public static void useForTest(ChatBank bank) {
        shared = bank;
        tried = true;
    }

    private static ChatBank loadDefault() {
        try {
            Path file = soloMapling.DataFiles.path(RESOURCE);
            if (!Files.isRegularFile(file)) {
                log.info("No chat line bank at {}: scripted lines only", file);
                return null;
            }
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                ChatBank bank = read(in);
                log.info("Chat line bank: {} lines, {} intents, ~{} KB", bank.size(), bank.intents.size(),
                        bank.heapBytes() / 1024);
                return bank;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Chat line bank unreadable: scripted lines only", e);
            return null;
        }
    }

    /** The pack header of a bank whose tags field carries the claim tags ({@code claim:resupply}). */
    public static final String HEADER_V2 = "#chatbank v2 claims";

    /** Reads an uncompressed TSV stream. Bad rows are skipped. */
    public static ChatBank read(InputStream in) throws IOException {
        List<String[]> rows = new ArrayList<>();
        boolean tagged = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(HEADER_V2)) {
                    tagged = true;
                }
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", 7);
                if (f.length == 7 && !f[6].isBlank()) {
                    rows.add(f);
                }
            }
        }
        return build(rows, tagged);
    }

    /** Builds a bank from parsed rows (id, intent, tone, affinity, voice, tags, text); the claims from the text. */
    public static ChatBank build(List<String[]> rows) {
        return build(rows, false);
    }

    /**
     * The claim tags of a line, as the pack writes them into its tags field: {@code claim:<kind>} for each strong
     * claim ({@link ClaimRules}).
     */
    public static List<String> claimTags(String text) {
        List<String> out = new ArrayList<>();
        for (ClaimRules.Claim c : ClaimRules.classify(text)) {
            if (!c.weak() && !out.contains("claim:" + c.kind().tag())) {
                out.add("claim:" + c.kind().tag());
            }
        }
        return out;
    }

    /** The claim bits of a set of kinds. */
    public static long claimMask(java.util.Collection<ClaimRules.Kind> kinds) {
        long m = 0;
        for (ClaimRules.Kind k : kinds) {
            m |= 1L << k.ordinal();
        }
        return m;
    }

    /**
     * Builds a bank. {@code claimsTagged}: the rows' tags carry the pack-time claim tags (a v2 pack); otherwise each
     * line's claims are worked out from its text here (an older pack).
     */
    public static ChatBank build(List<String[]> rows, boolean claimsTagged) {
        int n = rows.size();
        List<String> intents = new ArrayList<>();
        Map<String, Integer> intentIndex = new HashMap<>();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(n * 40);
        int[] offsets = new int[n + 1];
        int[] ids = new int[n];
        byte[] in = new byte[n];
        byte[] to = new byte[n];
        byte[] af = new byte[n];
        byte[] vo = new byte[n];
        long[] tg = new long[n];
        long[] cl = new long[n];
        Map<Integer, List<Integer>> cellLists = new HashMap<>();
        int kept = 0;
        for (String[] f : rows) {
            int id;
            try {
                id = Integer.parseInt(f[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String intentKey = f[1].trim().toLowerCase(Locale.ROOT);
            Integer ii = intentIndex.get(intentKey);
            if (ii == null) {
                if (intents.size() >= 250) {
                    continue;
                }
                ii = intents.size();
                intents.add(intentKey);
                intentIndex.put(intentKey, ii);
            }
            int ti = Math.max(0, TONES.indexOf(f[2].trim().toLowerCase(Locale.ROOT)));
            int ai = Math.max(0, AFFINITIES.indexOf(f[3].trim().toLowerCase(Locale.ROOT)));
            ConvoPersona.Voice v = ConvoPersona.Voice.parse(f[4]);
            long mask = 0;
            long claimBits = 0;
            if (!f[5].isBlank() && !"-".equals(f[5].trim())) {
                for (String t : f[5].split(",")) {
                    if (t.trim().startsWith("claim:")) {
                        ClaimRules.Kind k = ClaimRules.Kind.parse(t.trim().substring(6));
                        if (k != null) {
                            claimBits |= 1L << k.ordinal();
                        }
                        continue;
                    }
                    Tag tag = Tag.parse(t);
                    if (tag != null) {
                        mask |= tag.bit;
                    }
                }
            }
            if (!claimsTagged) {
                for (String t : claimTags(f[6])) {
                    claimBits |= 1L << ClaimRules.Kind.parse(t.substring(6)).ordinal();
                }
            }
            byte[] utf = f[6].trim().getBytes(StandardCharsets.UTF_8);
            offsets[kept] = bytes.size();
            bytes.write(utf, 0, utf.length);
            ids[kept] = id;
            in[kept] = (byte) (int) ii;
            to[kept] = (byte) ti;
            af[kept] = (byte) ai;
            vo[kept] = (byte) (v == null ? -1 : v.ordinal());
            tg[kept] = mask;
            cl[kept] = claimBits;
            cellLists.computeIfAbsent(cell(ii, ai), k -> new ArrayList<>()).add(kept);
            kept++;
        }
        offsets[kept] = bytes.size();
        Map<Integer, int[]> cells = new HashMap<>();
        cellLists.forEach((k, v) -> cells.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        return new ChatBank(bytes.toByteArray(), java.util.Arrays.copyOf(offsets, kept + 1), java.util.Arrays.copyOf(ids, kept),
                java.util.Arrays.copyOf(in, kept), java.util.Arrays.copyOf(to, kept), java.util.Arrays.copyOf(af, kept),
                java.util.Arrays.copyOf(vo, kept), java.util.Arrays.copyOf(tg, kept), java.util.Arrays.copyOf(cl, kept),
                List.copyOf(intents), cells);
    }

    private static int cell(int intentIndex, int affinityIndex) {
        return intentIndex * 16 + affinityIndex;
    }

    // ── picking ──

    /**
     * What a caller wants.
     *
     * @param intent    the bank intent key ("talk", "come_yes", "thread_lfg"...)
     * @param tone      "kind", "rude", "flirty", "neutral" or null for any
     * @param affinity  "friend", "neutral", "rival", "nemesis", "partner", "ex"
     * @param voice     the bot's voice (null for any)
     * @param situation the situation's tags
     * @param slots     slot values ("player" -> "Pinky"); a line needing a missing slot is skipped
     */
    public record Query(String intent, String tone, String affinity, ConvoPersona.Voice voice, long situation,
                        Map<String, String> slots, long claimsOk) {
        /** Any claim allowed (tests, tools). */
        public Query(String intent, String tone, String affinity, ConvoPersona.Voice voice, long situation,
                     Map<String, String> slots) {
            this(intent, tone, affinity, voice, situation, slots, -1L);
        }
    }

    /** The claim bits of the line with this id (0 = none, or no such line). */
    public long claimsOf(int id) {
        int k = java.util.Arrays.binarySearch(sortedIds, id);
        return k < 0 ? 0 : claims[idOrder[k]];
    }

    /** A picked line: its id and the text with the slots filled. */
    public record Pick(int id, String text) {
    }

    /** Weights from {@code !rate} (1 = normal, 0 = blacklisted). */
    public interface Weights {
        double of(int id);

        Weights NONE = id -> 1.0;
    }

    /** Picks a line, or null when the bank has nothing fitting. */
    public Pick pick(Query q, RecentLines.Window recent, Weights weights, Random rng) {
        return pick(q, recent, weights, rng, null);
    }

    /**
     * The same, keeping only lines {@code textOk} accepts (e.g. a neutral acknowledgement for a generic reply), so the
     * caller never has to take a line back. It applies inside each cell, so a stricter first pass may still find one.
     */
    public Pick pick(Query q, RecentLines.Window recent, Weights weights, Random rng,
                     java.util.function.Predicate<String> textOk) {
        int ii = intents.indexOf(q.intent());
        if (ii < 0) {
            return null;
        }
        int ai = Math.max(1, AFFINITIES.indexOf(q.affinity() == null ? "neutral" : q.affinity()));
        // the bot's own affinity, then neutral, then "any"; in each, its own voice and situation first, then any
        for (int a : ai == 1 ? new int[]{1, 0} : new int[]{ai, 1, 0}) {
            for (boolean strict : new boolean[]{true, false}) {
                Pick p = pickIn(ii, a, strict, q, recent, weights, rng, textOk);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    private static final int SPANISH = ConvoPersona.Voice.SPANISH.ordinal();

    private Pick pickIn(int ii, int ai, boolean strict, Query q, RecentLines.Window recent, Weights weights, Random rng,
                        java.util.function.Predicate<String> textOk) {
        int[] cell = cells.get(cell(ii, ai));
        if (cell == null) {
            return null;
        }
        int toneIndex = q.tone() == null ? 0 : Math.max(0, TONES.indexOf(q.tone()));
        double total = 0;
        int start = cell.length <= 4000 ? 0 : rng.nextInt(cell.length);
        int limit = Math.min(cell.length, 4000);
        double[] w = new double[limit];
        int[] rows = new int[limit];
        int count = 0;
        for (int k = 0; k < limit; k++) {
            int row = cell[(start + k) % cell.length];
            if (strict && q.voice() != null && voice[row] >= 0 && voice[row] != q.voice().ordinal()) {
                continue;
            }
            if (!strict && q.voice() != null && voice[row] >= 0 && voice[row] != q.voice().ordinal()
                    && rng.nextDouble() < 0.5) {
                continue; // other voices' lines are fine (the styler re-voices them), just less often
            }
            if (voice[row] == SPANISH && (q.voice() != ConvoPersona.Voice.SPANISH
                    || VoiceStyler.spanishSentence(row(row)))) {
                continue; // 1.6.0 tune: the accent is the Spanish voice's own, and never a whole Spanish line
            }
            if (toneIndex > 0 && tone[row] > 0 && tone[row] != toneIndex) {
                continue;
            }
            if (strict && (tags[row] & ~q.situation()) != 0) {
                continue; // a line about a boss or a PQ only where there is one
            }
            if (recent != null && recent.contains(ids[row])) {
                continue;
            }
            if ((claims[row] & ~q.claimsOk()) != 0) {
                continue; // rc6 say-do: it promises something this reply's action does not do
            }
            if (textOk != null && !textOk.test(row(row))) {
                continue;
            }
            double weight = weights == null ? 1 : weights.of(ids[row]);
            if (weight <= 0) {
                continue;
            }
            if ((tags[row] & q.situation()) != 0) {
                weight *= 2; // a line about where the bot is reads specific
            }
            w[count] = weight;
            rows[count] = row;
            total += weight;
            count++;
        }
        while (count > 0 && total > 0) {
            double roll = rng.nextDouble() * total;
            int chosen = count - 1;
            for (int k = 0; k < count; k++) {
                roll -= w[k];
                if (roll <= 0) {
                    chosen = k;
                    break;
                }
            }
            String filled = fill(row(rows[chosen]), q.slots());
            if (filled != null && filled.length() <= VoiceStyler.MAX) {
                return new Pick(ids[rows[chosen]], filled);
            }
            total -= w[chosen]; // a slot it needs is missing: try another
            w[chosen] = 0;
        }
        return null;
    }

    /** Fills {slot}s; null when one is missing or unknown. */
    public static String fill(String raw, Map<String, String> slots) {
        Matcher m = SLOT.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = slots == null ? null : slots.get(m.group(1));
            if (v == null || v.isBlank()) {
                return null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
