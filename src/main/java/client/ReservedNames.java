package client;

import java.util.Locale;
import java.util.Set;

/**
 * Names nobody may register because they read as staff (ticket 43). Two rules:
 *
 * <ul>
 *   <li>{@link #isReservedAccountName}: automatic registration refuses account names that are, or merely spell, a
 *       staff word - {@code admin}, {@code gm}, {@code administrator}, {@code owner}, {@code staff}, {@code mod},
 *       {@code moderator}, {@code sysop} (plus {@code gamemaster}, {@code root}) - in any case, with digit/letter
 *       swaps ({@code 4dm1n}, {@code m0d}, {@code 0wn3r}), lookalike letters ({@code admln}, {@code adrnin}),
 *       separators ({@code a.d-m_i n}) and trailing digits ({@code admin01}), that contain {@code admin},
 *       {@code moderator}, {@code sysop} or {@code gamemaster} anywhere, or that start with {@code gm} followed by a
 *       separator or digit ({@code gm_josh}, {@code gm2}).</li>
 *   <li>{@link #looksLikeStaffCharacterName}: character creation and renames refuse names that start with
 *       {@code GM} (in any spelling of it) or contain a staff word, so nobody can pose as a GM in chat.</li>
 * </ul>
 *
 * <p>Pure functions with no state, so the login and character threads can call them freely.
 */
public final class ReservedNames {

    /** Account names refused for automatic registration (compared in canonical form). */
    static final Set<String> RESERVED_ACCOUNT_WORDS = Set.of("admin", "gm", "administrator", "owner", "staff", "mod",
            "moderator", "sysop", "gamemaster", "root", "superuser", "support", "system");

    /** Words no account name may contain anywhere (canonical form), e.g. "xXadminXx", "the_m0derator". */
    static final Set<String> ACCOUNT_CONTAINS = Set.of("admin", "moderator", "sysop", "gamemaster");

    /** Words no character name may contain (compared in canonical form). */
    static final Set<String> STAFF_WORDS = Set.of("admin", "owner", "staff", "moderator", "sysop", "gamemaster");

    private ReservedNames() {
    }

    /** True when an account with this name must not be created by automatic registration. */
    public static boolean isReservedAccountName(String login) {
        if (login == null) {
            return false;
        }
        String lower = login.toLowerCase(Locale.ROOT);
        String compact = canonical(lower);
        if (RESERVED_ACCOUNT_WORDS.contains(compact)) {
            return true;
        }
        String withoutDigits = canonical(stripTrailingDigits(stripSeparators(lower)));
        if (RESERVED_ACCOUNT_WORDS.contains(withoutDigits)) {
            return true;
        }
        for (String word : ACCOUNT_CONTAINS) {
            if (compact.contains(word)) {
                return true;
            }
        }
        // "gm_josh", "gm-1", "[gm]x", "gm2": GM followed by a separator or a digit in the raw spelling.
        String raw = lower.replaceAll("^[^a-z0-9]+", "");
        return raw.matches("[g69]m([^a-z].*)?");
    }

    /** True when a character name reads as staff: starts with GM, or contains a staff word. */
    public static boolean looksLikeStaffCharacterName(String name) {
        if (name == null) {
            return false;
        }
        String compact = canonical(name.toLowerCase(Locale.ROOT));
        if (compact.startsWith("gm")) {
            return true;
        }
        String withoutDigits = canonical(stripTrailingDigits(stripSeparators(name.toLowerCase(Locale.ROOT))));
        if (withoutDigits.equals("mod") || withoutDigits.equals("gm")) {
            return true;
        }
        for (String word : STAFF_WORDS) {
            if (compact.contains(canonical(word))) {
                return true;
            }
        }
        return false;
    }

    /**
     * One spelling for every lookalike: lower case, separators and symbols dropped, digits read as the letters they
     * imitate, and letters that pass for one another folded together (l/i, rn/m, vv/w).
     */
    static String canonical(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            char mapped = switch (c) {
                case '0' -> 'o';
                case '1', '!', '|', 'l' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '6', '9' -> 'g';
                case '7', '+' -> 't';
                case '8' -> 'b';
                default -> c;
            };
            if (mapped >= 'a' && mapped <= 'z') {
                out.append(mapped);
            }
        }
        return out.toString().replace("rn", "m").replace("vv", "w");
    }

    private static String stripSeparators(String s) {
        return s.replaceAll("[^a-z0-9]", "");
    }

    private static String stripTrailingDigits(String s) {
        String stripped = s.replaceAll("[0-9]+$", "");
        return stripped.isEmpty() ? s : stripped;
    }
}
