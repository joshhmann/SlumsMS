package soloMapling.Economy;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.DataFiles;

import java.io.FileReader;
import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.5.0 rc3 economy lane: every number the bot shops, direct trades, price board, gifts and meso sinks use, read
 * from {@code economy.yaml} (the owner's data file, next to this class).
 * <p>
 * A key is its YAML path with dots ({@code sinks.shopTaxPercent}). Each read names its own safe default, so a
 * missing file or key changes nothing about how the server starts; a JVM property
 * {@code -Dsolomapling.economy.<key>=<value>} wins over the file (the server's config.yaml is strict, so the lane's
 * numbers live here, like the gachapon pools). {@code !economy reload} re-reads the file.
 * <p>
 * Map-valued keys ({@code sinks.shopPrices}) come back with {@link #map}.
 */
public final class EconomyConfig {

    private static final Logger log = LoggerFactory.getLogger(EconomyConfig.class);

    public static final String FILE = "src/main/java/soloMapling/Economy/economy.yaml";
    static final String PROPERTY_PREFIX = "solomapling.economy.";

    private static volatile Map<String, Object> values;

    private EconomyConfig() {
    }

    private static Map<String, Object> values() {
        Map<String, Object> v = values;
        if (v == null) {
            synchronized (EconomyConfig.class) {
                v = values;
                if (v == null) {
                    v = load();
                    values = v;
                }
            }
        }
        return v;
    }

    /** Re-reads the file; the number of keys read. */
    public static synchronized int reload() {
        values = load();
        return values.size();
    }

    private static Map<String, Object> load() {
        try (Reader in = new FileReader(DataFiles.file(FILE))) {
            Map<String, Object> flat = parse(in);
            log.info("[Economy] {} keys read from {}", flat.size(), FILE);
            return flat;
        } catch (Exception e) {
            log.warn("[Economy] could not read {}: every economy number uses its built-in default ({})", FILE,
                    e.toString());
            return Map.of();
        }
    }

    /** Reads a YAML document into dotted keys; a map under a key is kept whole too (for {@link #map}). */
    static Map<String, Object> parse(Reader in) throws Exception {
        Object root = new YamlReader(in).read();
        Map<String, Object> flat = new LinkedHashMap<>();
        if (root instanceof Map<?, ?> m) {
            flatten("", m, flat);
        }
        return Collections.unmodifiableMap(flat);
    }

    private static void flatten(String prefix, Map<?, ?> m, Map<String, Object> out) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = prefix + String.valueOf(e.getKey()).trim();
            out.put(key, e.getValue());
            if (e.getValue() instanceof Map<?, ?> child) {
                flatten(key + ".", child, out);
            }
        }
    }

    /** Test seam: these values instead of the file (null = read the file again on next use). */
    public static void useForTest(Map<String, Object> testValues) {
        values = testValues == null ? null : new LinkedHashMap<>(testValues);
    }

    // ---- typed reads ----

    static String raw(String key) {
        String p = System.getProperty(PROPERTY_PREFIX + key);
        if (p != null && !p.isBlank()) {
            return p.trim();
        }
        Object v = values().get(key);
        return v == null || v instanceof Map ? null : String.valueOf(v).trim();
    }

    public static long getLong(String key, long fallback) {
        String v = raw(key);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.replace("_", "").replace(",", ""));
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(v);
            } catch (NumberFormatException e2) {
                log.warn("[Economy] {} = '{}' is not a number; using {}", key, v, fallback);
                return fallback;
            }
        }
    }

    public static int getInt(String key, int fallback) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, getLong(key, fallback)));
    }

    public static double getDouble(String key, double fallback) {
        String v = raw(key);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.replace("_", ""));
        } catch (NumberFormatException e) {
            log.warn("[Economy] {} = '{}' is not a number; using {}", key, v, fallback);
            return fallback;
        }
    }

    public static boolean getBool(String key, boolean fallback) {
        String v = raw(key);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> fallback;
        };
    }

    /** An integer-keyed, integer-valued map ({@code itemId: price}); empty when missing. Not overridable by -D. */
    public static Map<Integer, Long> map(String key) {
        Object v = values().get(key);
        Map<Integer, Long> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                try {
                    out.put(Integer.parseInt(String.valueOf(e.getKey()).trim()),
                            Long.parseLong(String.valueOf(e.getValue()).trim().replace("_", "")));
                } catch (NumberFormatException bad) {
                    log.warn("[Economy] {}: skipped '{}: {}'", key, e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }
}
