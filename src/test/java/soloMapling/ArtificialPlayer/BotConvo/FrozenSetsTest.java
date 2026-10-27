package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The frozen evaluation sets never change: the 70 held-out real-player lines and, separately, the 96 hand-labelled
 * model-comparison cases. Their checksums live in tools/chatbank/HOLDOUT-SHA256SUMS and here.
 */
class FrozenSetsTest {

    static final Map<String, String> HOLDOUT_70 = Map.of(
            "src/test/resources/chat/convo-holdout.tsv", "6c5bff868b091d6fb5f15b3894e195813f9390e92fa9eb965f5578bcb4cf04a7",
            "src/test/resources/chat/holdout-lines.tsv", "f9cf0da77d1ce1f5adbf08ee5ae44c7d68bf0b78d863f251583b345445c6c149");
    static final Map<String, String> BENCH_96 = Map.of(
            "tools/convo/benchmark/eval/fresh-v3/cases.jsonl", "674ed53d563c788daf07929820e7242d327036ed1ca44f472aeb97cb03257f53");

    private static String sha256(String file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file))));
    }

    private static Map<String, String> sumsFile() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String row : Files.readAllLines(Path.of("tools/chatbank/HOLDOUT-SHA256SUMS"))) {
            if (!row.isBlank() && !row.startsWith("#")) {
                String[] f = row.split("\\s+", 2);
                out.put(f[1].trim(), f[0]);
            }
        }
        return out;
    }

    @Test
    void theHeldOutSeventyAreFrozen() throws Exception {
        Map<String, String> sums = sumsFile();
        for (Map.Entry<String, String> e : HOLDOUT_70.entrySet()) {
            String msg = "the held-out set is frozen (the 70 real-player test lines, never trained on): " + e.getKey()
                    + " changed; do not edit it, add new lines to a new set instead";
            assertEquals(e.getValue(), sha256(e.getKey()), msg);
            assertEquals(e.getValue(), sums.get(e.getKey()), msg + " (tools/chatbank/HOLDOUT-SHA256SUMS)");
        }
    }

    @Test
    void theBenchmarkNinetySixAreFrozen() throws Exception {
        Map<String, String> sums = sumsFile();
        for (Map.Entry<String, String> e : BENCH_96.entrySet()) {
            String msg = "the benchmark set is frozen (the 96 hand-labelled model-comparison cases, never trained on, "
                    + "separate from the 70 held-out lines): " + e.getKey() + " changed; make a new set instead";
            assertEquals(e.getValue(), sha256(e.getKey()), msg);
            assertEquals(e.getValue(), sums.get(e.getKey()), msg + " (tools/chatbank/HOLDOUT-SHA256SUMS)");
        }
    }

    @Test
    void theGeneratorGuardsExactlyTheseFiles() {
        assertEquals(List.copyOf(HOLDOUT_70.keySet()).stream().sorted().toList(),
                ChatPairGen.FROZEN.get("holdout-70").stream().sorted().toList());
        assertEquals(List.copyOf(BENCH_96.keySet()), ChatPairGen.FROZEN.get("bench-96"));
    }
}
