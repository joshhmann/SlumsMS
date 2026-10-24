package client.command;

import client.command.commands.gm0.BotDramaCommand;
import client.command.commands.gm0.QuietCommand;
import client.command.commands.gm0.RomanceCommand;
import client.command.commands.gm0.SettingsCommand;
import client.command.commands.gm3.QaCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.qa.QaSuite;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.5.0 menus: @settings is a player command, and each line it runs is the player command it names. */
class MenuCommandsRegisteredTest {

    @AfterEach
    void clear() {
        System.clearProperty(QaSuite.PROPERTY);
    }

    @Test
    void settingsAndTheCommandsItRunsArePlayerCommands() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(SettingsCommand.class, ex.classForTest("settings"));
        assertEquals(BotDramaCommand.class, ex.classForTest("botdrama"));
        assertEquals(RomanceCommand.class, ex.classForTest("romance"));
        assertEquals(QuietCommand.class, ex.classForTest("quiet"));
        for (String name : new String[]{"settings", "botdrama", "romance", "quiet", "price", "guildwar"}) {
            assertEquals(0, ex.rankForTest(name), name);
        }
    }

    @Test
    void guideAndTutorialOpenTheGuideAndHelpStaysTheCommandList() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(client.command.commands.gm0.GuideCommand.class, ex.classForTest("guide"));
        assertEquals(client.command.commands.gm0.GuideCommand.class, ex.classForTest("tutorial"));
        assertEquals(client.command.commands.gm0.HelpCommand.class, ex.classForTest("help"));
        assertEquals(client.command.commands.gm0.HelpCommand.class, ex.classForTest("commands"));
        for (String name : new String[]{"guide", "tutorial", "help", "commands"}) {
            assertEquals(0, ex.rankForTest(name), name);
        }
    }

    @Test
    void playersCanGotoTowns() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(client.command.commands.gm2.GotoCommand.class, ex.classForTest("goto"));
        assertEquals(0, ex.rankForTest("goto"), "friends asked for town hopping (1.5.0)");
    }

    @Test
    void theQaMenuIsTheQaCommand() {
        System.setProperty(QaSuite.PROPERTY, "true");
        assertEquals(QaCommand.class, CommandsExecutor.freshForTest().classForTest("qa"));
    }
}
