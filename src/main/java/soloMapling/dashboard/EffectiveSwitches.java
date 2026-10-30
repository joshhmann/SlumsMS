package soloMapling.dashboard;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Ticket 157 (idea from nutnnut/Cosmic's {@code /admin} settings view, read-only here): the game's effective on/off
 * settings for {@code live.json}'s {@code switches}, so "which switches are on TEST" is answered by the dashboard
 * instead of by hand. Every {@code -D} system property under {@link #PREFIXES}, name to its text, plus every on/off
 * field of the server config as {@code config.<FIELD>}. Left out: any name containing secret, key, pass or token,
 * and any value that looks like a file path or URL (it has a slash or a backslash, or starts with a drive letter).
 * Built once per export (once a minute), from the exporter's own thread.
 */
final class EffectiveSwitches {

    static final List<String> PREFIXES = List.of("slums.", "solomapling.", "cashshop.", "weather.", "mapleroot.");
    private static final List<String> HIDDEN_NAMES = List.of("secret", "key", "pass", "token");
    private static final List<Field> CONFIG_TOGGLES = configToggles();

    private EffectiveSwitches() {
    }

    /** This server's switches now. */
    static Map<String, String> snapshot() {
        Map<String, Boolean> toggles = new TreeMap<>();
        try {
            Object server = config.YamlConfig.config == null ? null : config.YamlConfig.config.server;
            if (server != null) {
                for (Field f : CONFIG_TOGGLES) {
                    toggles.put(f.getName(), f.getBoolean(server));
                }
            }
        } catch (IllegalAccessException | RuntimeException | LinkageError e) {
            // no config (tools, tests): the properties alone
        }
        return of(System.getProperties(), toggles);
    }

    /** The switches from a set of properties and the config's on/off fields (tests hand both in). */
    static Map<String, String> of(Properties props, Map<String, Boolean> configToggles) {
        Map<String, String> out = new TreeMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!switchName(name)) {
                continue;
            }
            String value = props.getProperty(name);
            if (value != null && !looksLikePath(value)) {
                out.put(name, value);
            }
        }
        configToggles.forEach((field, on) -> {
            if (!hidden(field)) {
                out.put("config." + field, String.valueOf(on));
            }
        });
        return out;
    }

    static boolean switchName(String name) {
        if (hidden(name)) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean hidden(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : HIDDEN_NAMES) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static boolean looksLikePath(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.startsWith("~")
                || value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    /** The config fields read as toggles (tests). */
    static List<String> configToggleNames() {
        return CONFIG_TOGGLES.stream().map(Field::getName).toList();
    }

    /** The public boolean fields of ServerConfig (its on/off settings). */
    private static List<Field> configToggles() {
        List<Field> out = new ArrayList<>();
        try {
            for (Field f : config.ServerConfig.class.getFields()) {
                if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers()) && !hidden(f.getName())) {
                    out.add(f);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // no config class: properties only
        }
        return out;
    }
}
