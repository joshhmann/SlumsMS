package soloMapling.menus;

import client.Character;
import client.command.CommandsExecutor;
import client.command.commands.gm0.RomanceCommand;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.menus.MenuWindow.Option;
import soloMapling.menus.MenuWindow.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 1.5.0 {@code @settings}: the player's bot settings in the NPC window, each with its state; a click switches it and
 * the window redraws with the new state. Every switch is the player's own command ({@code @botdrama},
 * {@code @romance}, {@code @quiet}) run through {@link CommandsExecutor}, so the rules, rate limits, messages and logs
 * are the command's.
 */
public final class SettingsMenu {

    /**
     * Slums-chan frames the window (1.6.1; Cody did before). The 1.6.1 client has her head and she stands in Base of
     * Roots and Henesys (migration 042); an older client shows no head, which the window survives.
     */
    public static final int NPC = SlumsChanMenu.NPC;

    /** What the window shows. */
    public record State(boolean dramaOn, boolean romanceOn, boolean hideMegaphones, boolean hideBots) {
    }

    static final MenuWindow.Commands TYPED = (player, line) -> {
        CommandsExecutor.getInstance().handle(player.getClient(), line);
        return line.startsWith("@romance") ? RomanceCommand.pending(player.getId()) : null;
    };

    static final Function<Character, State> LIVE = p -> {
        BotNoiseFilter.Setting quiet = BotNoiseFilter.get(p.getId());
        return new State(!BotAffinity.optedOut(p.getId()), BotRomance.romanceOn(p.getId()), quiet.megaphones(),
                quiet.bots());
    };

    private static volatile MenuWindow.Commands commands = TYPED;
    private static volatile Function<Character, State> states = LIVE;

    private SettingsMenu() {
    }

    /** {@code @settings}: opens the window; false when it could not (another NPC window is open). */
    public static boolean open(Character player) {
        return MenuWindow.open(player, NPC, SettingsMenu::page);
    }

    static Page page(Character player) {
        return page(states.apply(player));
    }

    /** The settings page for a state. */
    static Page page(State s) {
        List<Option> lines = new ArrayList<>();
        lines.add(toggle("Bot drama: rivals, trash talk and KS wars. ", s.dramaOn(),
                "@botdrama " + (s.dramaOn() ? "off" : "on")));
        lines.add(toggle("Romance: bots may flirt, date, propose. ", s.romanceOn(),
                "@romance " + (s.romanceOn() ? "off" : "on")));
        lines.add(shown("Bot megaphones: ", !s.hideMegaphones(),
                "@quiet megaphones" + (s.hideMegaphones() ? " off" : "")));
        lines.add(shown("Bot chatter around you: ", !s.hideBots(),
                "@quiet bots" + (s.hideBots() ? " off" : "")));
        lines.add(new Option("#bMore commands (help)#k", p -> SettingsMenu::help));
        return new Page("#e@settings#n: click a line to switch it.\r\n"
                + "Real players and GM notices always show.\r\n", lines);
    }

    private static Option toggle(String what, boolean on, String command) {
        return new Option(what + (on ? "#bON#k" : "#rOFF#k"), p -> run(p, command));
    }

    private static Option shown(String what, boolean shown, String command) {
        return new Option(what + (shown ? "#bSHOWN#k" : "#rHIDDEN#k"), p -> run(p, command));
    }

    /** A click: the command, then this page again (after an async command finishes, in a fresh window). */
    static MenuWindow.Menu run(Character player, String command) {
        CompletableFuture<?> pending = commands.run(player, command);
        if (pending != null && !pending.isDone()) {
            MenuWindow.reopenLater(player, NPC, SettingsMenu::page, pending);
            return null;
        }
        return SettingsMenu::page;
    }

    /** Read-only help: the other player commands worth knowing. */
    static Page help(Character player) {
        String text = "#eUseful commands#n (type them in chat):\r\n"
                + "#b@price <item>#k what it sold for this week\r\n"
                + "#b@guildwar#k your guild's war score\r\n"
                + "#b@guildwar peace#k end a bot guild's war\r\n"
                + "#b@romance#k who you're with; wedding,\r\n   breakup <name>, divorce\r\n"
                + "#b@gacha <town>#k a machine's prizes\r\n"
                + "#b@quiet all#k hide all bot noise at once\r\n"
                + "#b@dispose#k unstick a frozen NPC or action\r\n"
                + "#b@commands#k every command\r\n";
        return new Page(text, List.of(new Option("#b< Back to settings#k", p -> SettingsMenu::page)));
    }

    /** Test seam (null restores the real ones). */
    static void useForTest(MenuWindow.Commands testCommands, Function<Character, State> testStates) {
        commands = testCommands == null ? TYPED : testCommands;
        states = testStates == null ? LIVE : testStates;
    }
}
