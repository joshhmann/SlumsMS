package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 player likeness: bot chat goes out the way a 2009 player typed - mostly lowercase, short, u/ur/ty/brb - with a
 * stable per-bot style, and never with a changed item name, number, price or player name.
 */
class MessyChatTest {

    private static final List<String> LINES = List.of(
            "Hey, are you going to Henesys right now?",
            "Thank you so much for the buff!",
            "I'm selling Brown Work Gloves for 50k, anyone?",
            "Okay, be right back. I need potions.",
            "What level are you? I'm 34.",
            "Does anyone want to party at Ant Tunnel?",
            "Sorry, I don't know where Kerning City is.",
            "No problem, have fun.",
            "I have a level 70 strider and he can solo ludi. Indeed.",
            "Selling 10% Helmet DEF scroll 1.2m pm me");

    @AfterEach
    void clear() {
        System.clearProperty(MessyChat.PROP);
    }

    private static int firstSlot(MessyChat.Style style) {
        for (int slot = 1; slot < 10_000; slot++) {
            if (MessyChat.styleOf(slot) == style) {
                return slot;
            }
        }
        throw new AssertionError("no slot with style " + style);
    }

    @Test
    void mostBotsTypeMessyAndSomeStayTidy() {
        int tidy = 0;
        int n = 5_000;
        for (int slot = 0; slot < n; slot++) {
            if (MessyChat.styleOf(slot) == MessyChat.Style.TIDY) {
                tidy++;
            }
            assertEquals(MessyChat.styleOf(slot), MessyChat.styleOf(slot), "a bot's style is stable");
        }
        double share = tidy / (double) n;
        assertTrue(share > 0.15 && share < 0.40, "tidy share " + share);
        for (int slot = 0; slot < 500; slot++) {
            if (ConvoPersona.of(slot).voice() == ConvoPersona.Voice.PROPER) {
                assertEquals(MessyChat.Style.TIDY, MessyChat.styleOf(slot), "a PROPER typer stays tidy");
            }
        }
    }

    @Test
    void aMessyBotLowercasesAndAbbreviates() {
        int slot = firstSlot(MessyChat.Style.MESSY);
        String out = MessyChat.apply("Hey, are you going to Henesys right now?", slot, List.of());
        assertTrue(out.startsWith("hey"), out);
        assertTrue(out.contains("Henesys"), "the map name is kept: " + out);
        assertTrue(out.contains(" u ") || out.contains(" you "), out);
        assertFalse(out.contains("gonna Henesys"), "going to a place is not 'gonna': " + out);

        int shorter = 0;
        int lowerStart = 0;
        for (int s = 0; s < 400; s++) {
            if (MessyChat.styleOf(s) == MessyChat.Style.TIDY) {
                continue;
            }
            for (String line : LINES) {
                String o = MessyChat.apply(line, s, List.of());
                if (o.length() < line.length()) {
                    shorter++;
                }
                if (java.lang.Character.isLowerCase(o.charAt(0))) {
                    lowerStart++;
                }
                assertTrue(o.length() <= line.length() + 2, line + " -> " + o);
            }
        }
        assertTrue(shorter > 0.8 * lowerStart, "most messy lines come out shorter: " + shorter + "/" + lowerStart);
        int ty = 0;
        int messy = 0;
        for (int s = 0; s < 400; s++) {
            if (MessyChat.styleOf(s) != MessyChat.Style.TIDY) {
                messy++;
                if (MessyChat.apply("Thank you so much for the buff!", s, List.of()).startsWith("ty ")) {
                    ty++;
                }
            }
        }
        assertTrue(ty > messy / 2, "thank you -> ty for most messy bots: " + ty + "/" + messy);
    }

    @Test
    void numbersPricesItemNamesAndPlayerNamesSurvive() {
        Pattern numbers = Pattern.compile("[0-9][0-9.,%km]*");
        for (int s = 0; s < 600; s++) {
            for (String line : LINES) {
                String out = MessyChat.apply(line, s, Set.of("bob"));
                assertEquals(tokens(numbers, line), tokens(numbers, out), "numbers: " + line + " -> " + out);
                for (String name : List.of("Henesys", "Brown Work Gloves", "Ant Tunnel", "Kerning City", "DEF", "Helmet")) {
                    if (line.contains(name)) {
                        assertTrue(out.contains(name), name + " in " + out);
                    }
                }
            }
            String named = MessyChat.apply("are you there bob? you said 3m for it", s, Set.of("bob"));
            assertTrue(named.contains("bob") && named.contains("3m"), named);
            String capName = MessyChat.apply("Hey Leetstriker, are you coming?", s, Set.of());
            assertTrue(capName.contains("Leetstriker"), capName);
            String quoted = MessyChat.apply("He said \"Are You There\" lol", s, Set.of());
            assertTrue(quoted.contains("\"Are You There\""), quoted);
            String sale = MessyChat.apply("S> Work Gloves 4 att 2.5m", s, Set.of());
            assertTrue(sale.contains("S>") && sale.contains("2.5m") && sale.contains("Work Gloves"), sale);
        }
    }

    @Test
    void itNeverAddsWordsSoItCannotChangeWhatTheBotSays() {
        for (int s = 0; s < 300; s++) {
            for (String line : LINES) {
                String out = MessyChat.apply(line, s, List.of()).toLowerCase(Locale.ROOT);
                for (String bad : List.of(" ai", "bot", "language model", "fuck", "shit")) {
                    assertEquals(line.toLowerCase(Locale.ROOT).contains(bad), out.contains(bad), line + " -> " + out);
                }
            }
        }
    }

    @Test
    void theSameBotAndLineAlwaysComeOutTheSame() {
        for (String line : LINES) {
            assertEquals(MessyChat.apply(line, 42, List.of()), MessyChat.apply(line, 42, List.of()));
        }
    }

    @Test
    void someTyposAndRareCapsButNotOnEveryLine() {
        int typos = 0;
        int caps = 0;
        int total = 0;
        for (int s = 0; s < 2_000; s++) {
            if (MessyChat.styleOf(s) == MessyChat.Style.TIDY) {
                continue;
            }
            String out = MessyChat.apply("lol that was nice, thanks for the help with the quest", s, List.of());
            total++;
            if (out.matches(".*\\b(LOL|NICE|HELP)\\b.*")) {
                caps++;
            }
            String plain = out.toLowerCase(Locale.ROOT);
            for (String w : List.of("that", "nice", "help", "with", "quest", "thanks", "thx")) {
                plain = plain.replace(w, "");
            }
            if (plain.matches(".*[a-z]{4,}.*")) {
                typos++;
            }
        }
        assertTrue(caps > 0 && caps < total * 0.1, "caps " + caps + "/" + total);
        assertTrue(typos > 0 && typos < total * 0.25, "typos " + typos + "/" + total);
    }

    @Test
    void tidyBotsAndTheSwitchLeaveLinesAlone() {
        int tidy = firstSlot(MessyChat.Style.TIDY);
        for (String line : LINES) {
            assertEquals(line, MessyChat.apply(line, tidy, List.of()));
        }
        int messy = firstSlot(MessyChat.Style.MESSY);
        assertFalse(MessyChat.apply(LINES.get(0), messy, List.of()).equals(LINES.get(0)));
        System.setProperty(MessyChat.PROP, "false");
        for (String line : LINES) {
            assertEquals(line, MessyChat.apply(line, messy, List.of()));
        }
    }

    @Test
    void theQaSampleSlotsIncludeAMessyBot() {
        boolean any = false;
        for (int slot : new int[]{11, 42, 77, 305}) {
            any |= MessyChat.styleOf(slot) != MessyChat.Style.TIDY;
        }
        assertTrue(any, "lk-1's sample step must show at least one messy bot");
    }

    private static List<String> tokens(Pattern p, String s) {
        Matcher m = p.matcher(s);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
