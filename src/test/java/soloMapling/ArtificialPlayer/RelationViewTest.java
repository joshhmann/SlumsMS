package soloMapling.ArtificialPlayer;

import client.command.CommandsExecutor;
import client.command.commands.gm0.BondsCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotDramaSystem.Romance.Stage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RelationViewTest {
    static final Pattern FORBIDDEN = Pattern.compile("(?i)\\d|\\bbot\\b|\\bai\\b|score|hostile|nemesis|rival|enemy|hate");

    static class Fake implements RelationView.Source {
        List<String> buddies = List.of();
        Map<String, AffinityState> affinity = new LinkedHashMap<>();
        Map<String, Stage> romances = new LinkedHashMap<>();
        boolean romanceOn = true;
        String spouse, guild, senior;
        List<String> juniors = List.of(), alts = List.of();
        public List<String> buddyNames() { return buddies; }
        public Map<String, AffinityState> affinity() { return affinity; }
        public Map<String, Stage> romances() { return romances; }
        public boolean romanceOn() { return romanceOn; }
        public String spouseName() { return spouse; }
        public String guildName() { return guild; }
        public String seniorName() { return senior; }
        public List<String> juniorNames() { return juniors; }
        public List<String> altNames() { return alts; }
    }

    @BeforeEach void on() { System.setProperty("slums.bonds", "true"); }
    @AfterEach void off() { System.clearProperty("slums.bonds"); }

    @Test void hostileStatesAndNumbersNeverShow() {
        Fake f = new Fake();
        f.buddies = List.of("Mira", "Dax");
        f.affinity.put("Grim", AffinityState.NEMESIS);
        f.affinity.put("Vex", AffinityState.RIVAL);
        f.affinity.put("Mira", AffinityState.FRIEND);
        f.guild = "Slumlords";
        String all = String.join("\n", RelationView.build(f));
        assertFalse(all.contains("Grim") || all.contains("Vex"), all);
        assertTrue(all.contains("Good friends: Mira."), all);
        assertTrue(all.contains("Friends: Dax."), all);
        assertFalse(FORBIDDEN.matcher(all).find(), all);
    }

    @Test void exesAndRomanceOffPartnersAreHiddenButMarriageStays() {
        Fake f = new Fake();
        f.romances.put("Old", Stage.EX);
        f.romances.put("Flirt", Stage.DATING);
        f.romances.put("Wed", Stage.MARRIED);
        f.romanceOn = false;
        String all = String.join("\n", RelationView.build(f));
        assertFalse(all.contains("Old") || all.contains("Flirt"), all);
        assertTrue(all.contains("Married to Wed."), all);
        f.romanceOn = true;
        all = String.join("\n", RelationView.build(f));
        assertTrue(all.contains("Dating Flirt.") && !all.contains("Old"), all);
    }

    @Test void realPlayerShowsStockFactsOnly() {
        Fake f = new Fake();
        f.buddies = List.of("Pal");
        f.spouse = "Sam";
        f.romanceOn = false; // Sam's private status is never read or shown
        List<String> lines = RelationView.build(f);
        assertEquals(List.of("Married to Sam.", "Friends: Pal."), lines);
    }

    @Test void familyAndAltsAreListed() {
        Fake f = new Fake();
        f.senior = "Elder";
        f.juniors = List.of("Kid1", "Kid2");
        f.alts = List.of("Me2");
        List<String> lines = RelationView.build(f);
        assertEquals(List.of("Family senior: Elder.", "Family juniors: Kid1, Kid2.", "Your alt: Me2."), lines);
    }

    @Test void longListsAreCapped() {
        Fake f = new Fake();
        f.buddies = java.util.stream.IntStream.range(0, 60).mapToObj(i -> "Friendly" + (char) ('a' + i % 26) + (char) ('a' + i / 26)).toList();
        List<String> lines = RelationView.build(f);
        assertTrue(lines.size() <= 12);
        lines.forEach(l -> assertTrue(l.length() <= 110, l));
        assertTrue(lines.get(0).contains("more"), lines.get(0));
    }

    @Test void switchOffSaysNotAvailable() {
        System.clearProperty("slums.bonds");
        Fake f = new Fake();
        f.buddies = List.of("Mira");
        assertEquals(List.of(RelationView.OFF_TEXT), BondsCommand.reply(f));
        System.setProperty("slums.bonds", "true");
        assertEquals(List.of("Friends: Mira."), BondsCommand.reply(f));
    }

    @Test void bondsIsAPlayerCommand() {
        assertTrue(CommandsExecutor.getInstance().getGmCommands().get(0).getLeft().contains("bonds"));
    }
}
