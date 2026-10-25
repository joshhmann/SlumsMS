package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.wz.WZFiles;

import java.util.List;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.1 TEST log: "!maxskill" with no job threw ArrayIndexOutOfBoundsException (Index 0 out of bounds for length 0)
 * because it read params[0] before checking there was one, so the no-argument form (max every skill) never ran.
 */
class MaxSkillCommandTest {

    private static Character playerOf(Client c) {
        Character player = mock(Character.class);
        when(c.getPlayer()).thenReturn(player);
        when(player.getJob()).thenReturn(Job.RANGER);
        return player;
    }

    @Test
    void withoutAJobItMaxesEverySkill() {
        Client c = mock(Client.class);
        Character player = playerOf(c);
        Data skillNames = mock(Data.class);
        when(skillNames.getChildren()).thenReturn(List.of());
        DataProvider strings = mock(DataProvider.class);
        when(strings.getData("Skill.img")).thenReturn(skillNames);

        try (MockedStatic<DataProviderFactory> factory = mockStatic(DataProviderFactory.class)) {
            factory.when(() -> DataProviderFactory.getDataProvider(WZFiles.STRING)).thenReturn(strings);
            new MaxSkillCommand().execute(c, new String[0]);
        }

        verify(player).yellowMessage("Skills maxed out.");
    }

    @Test
    void anUnknownJobIsReportedInsteadOfThrowing() {
        Client c = mock(Client.class);
        Character player = playerOf(c);
        DataProvider skills = mock(DataProvider.class); // getData("999.img") -> null, like a missing WZ image

        try (MockedStatic<DataProviderFactory> factory = mockStatic(DataProviderFactory.class)) {
            factory.when(() -> DataProviderFactory.getDataProvider(WZFiles.SKILL)).thenReturn(skills);
            new MaxSkillCommand().execute(c, new String[]{"999"});
        }

        verify(player).yellowMessage(contains("No skills found for job 999"));
    }
}
