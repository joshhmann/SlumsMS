package soloMapling.menus;

import client.Character;
import soloMapling.ArtificialPlayer.BotChatPrompt;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.companions.CompanionLootMode;
import soloMapling.companions.CompanionSettings;
import soloMapling.companions.CompanionSettingsStore;
import soloMapling.menus.MenuWindow.Option;
import soloMapling.menus.MenuWindow.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * 1.6.7 alt-settings: one companion's card in the NPC window - level, job, hp/mp, pot counts, and the switches the
 * owner may flip (party heals, party buffs, buff pots) as clickable lines that save on the click. Reached from
 * {@code @alts card <name>} and from the companion's own "card" order. Same window as {@link SettingsMenu}, so there
 * is no packet code of its own: a click saves and the page is drawn again from the store, which is where the value
 * came from.
 *
 * <p>The potion lines are shown as text: they are set in chat ("pots at 60", "mp pots at 40"), and the store clamps
 * them. Looting is one clickable line (group D): it names the mode in force and a click flips to the next one and
 * saves it, so the four settings lines of the card keep their meaning and nothing else moves.
 */
public final class CompanionCardMenu {

    /** Slums-chan frames the window, the same frame {@code @settings} uses (an older client shows no head). */
    public static final int NPC = SlumsChanMenu.NPC;

    private CompanionCardMenu() {
    }

    /** Opens one companion's card for {@code owner}; false when another NPC window is open. */
    public static boolean open(Character owner, Character alt) {
        if (owner == null || alt == null) {
            return false;
        }
        return MenuWindow.open(owner, NPC, menu(owner, alt));
    }

    /** The menu for one companion: every page is redrawn from the store, so a click shows its new state. */
    public static MenuWindow.Menu menu(Character owner, Character alt) {
        return player -> page(player, alt);
    }

    /** The card page for {@code alt}, saved under {@code owner}'s id. */
    static Page page(Character owner, Character alt) {
        CompanionSettings now = CompanionSettingsStore.get(owner.getId(), alt.getId());
        int hpPots = BotVitals.potionCount(alt, true);
        int mpPots = BotVitals.potionCount(alt, false);
        String text = "#e" + alt.getName() + "#n  lv" + alt.getLevel() + " "
                + BotChatPrompt.jobName(alt.getJob().getId()) + "\r\n"
                + "hp " + alt.getHp() + "/" + alt.getMaxHp() + "  mp " + alt.getMp() + "/" + alt.getMaxMp() + "\r\n"
                + "pots: " + hpPots + " hp, " + mpPots + " mp"
                + " (pots at " + line(now.hpPercent()) + ", mp at " + line(now.mpPercent()) + ")\r\n"
                + "\r\n#eClick a switch to flip it.#n\r\n";
        List<Option> lines = new ArrayList<>();
        lines.add(toggle("Party heals: ", now.heals(), alt, s -> s.withHeals(!s.heals())));
        lines.add(toggle("Party buffs: ", now.support(), alt, s -> s.withSupport(!s.support())));
        lines.add(toggle("Buff pots: ", now.buffPots(), alt, s -> s.withBuffPots(!s.buffPots())));
        lines.add(lootLine(alt, CompanionLootMode.of(alt))); // SlumsMS: 1.6.7 group D, one line, flips through
        return new Page(text, lines);
    }

    /** A switch: its state as the label, and a click saves the opposite and redraws. */
    private static Option toggle(String what, boolean on, Character alt, UnaryOperator<CompanionSettings> flip) {
        return new Option(what + (on ? "#bON#k" : "#rOFF#k"), p -> {
            CompanionSettings current = CompanionSettingsStore.get(p.getId(), alt.getId());
            CompanionSettingsStore.save(p, alt, flip.apply(current));
            return menu(p, alt);
        });
    }

    /** 1.6.7 group D: the loot mode in force, and a click saves the next one and redraws. */
    private static Option lootLine(Character alt, CompanionLootMode mode) {
        return new Option("Looting: #b" + mode.stored() + "#k", p -> {
            CompanionSettings current = CompanionSettingsStore.get(p.getId(), alt.getId());
            CompanionSettingsStore.save(p, alt, current.withLootMode(mode.next().stored()));
            return menu(p, alt);
        });
    }

    /** "60%" for a set line, "the bot's own" for 0 (unset, today's behaviour). */
    static String line(int percent) {
        return percent > 0 ? percent + "%" : "the bot's own";
    }
}
