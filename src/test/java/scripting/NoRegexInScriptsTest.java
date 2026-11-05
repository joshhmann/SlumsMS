package scripting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No server script may use a JS regex. The fat jar keeps only one {@code META-INF/services/...TruffleLanguage$Provider}
 * (JavaScript's), so the regex language is never registered in the shipped server and any regex literal makes the
 * whole script "not coded". {@link AllScriptsLoadTest} can't see this: the test classpath has the separate jars, where
 * both providers are registered. 1.6.4: High Priest John and three other wedding NPCs (ticket 188 F3) went dark.
 */
class NoRegexInScriptsTest {

    // A '/' that opens a regex: after an operator, opener or `return`, not a comment, closed on the same line.
    private static final Pattern LITERAL =
            Pattern.compile("(?:^|[=(,:\\[!&|?{};]|\\breturn)\\s*/(?![/*])(?:\\\\.|[^/\\\\\\n])+/[gimsuy]*");
    private static final Pattern CONSTRUCTOR = Pattern.compile("\\bRegExp\\s*\\(");

    static List<String> regexUses(String source) {
        List<String> found = new ArrayList<>();
        String[] lines = stripComments(source).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripStrings(lines[i]);
            Matcher m = LITERAL.matcher(line);
            if (m.find() || CONSTRUCTOR.matcher(line).find()) {
                found.add((i + 1) + ": " + lines[i].trim());
            }
        }
        return found;
    }

    /** Blanks block and line comments, keeping line breaks so line numbers stay true. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        char quote = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    out.append(s.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                i++;
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                out.append(c);
                i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (int j = i; j < end; j++) {
                    out.append(s.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Empties string contents on one line so "a/b/c" in text never looks like a regex. */
    static String stripStrings(String line) {
        StringBuilder out = new StringBuilder(line.length());
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                    out.append(c);
                }
            } else {
                if (c == '"' || c == '\'' || c == '`') {
                    quote = c;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    @Test
    void noScriptUsesARegex() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("scripts"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".js") && !p.toString().endsWith(".old.js")).sorted().toList()) {
                for (String use : regexUses(Files.readString(f))) {
                    found.add(f.toString().replace('\\', '/') + ":" + use);
                }
            }
        }
        assertTrue(found.isEmpty(), "regex in scripts (the shipped server can't run them; use indexOf/substring): " + found);
    }

    @Test
    void theCheckSeesLiteralsAndConstructorsButNotDivisionCommentsOrText() {
        assertEquals(1, regexUses("var w = String(x).replace(/^[^-]*- /, \"\");").size());
        assertEquals(1, regexUses("var re = /[ ,&\\/]+/g;").size());
        assertEquals(1, regexUses("var r = new RegExp(\"a+\");").size());
        assertEquals(0, regexUses("var half = total / 2 / count;").size());
        assertEquals(0, regexUses("// see a/b/c\nvar x = 1;").size());
        assertEquals(0, regexUses("/* (a) /b/ */ var y = 2;").size());
        assertEquals(0, regexUses("cm.sendOk(\"Go to (Henesys /Ellinia/ route)\");").size());
    }
}
