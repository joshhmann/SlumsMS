package client.command;

import client.command.commands.gm0.ReadPointsCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 1.6.1: @points shows stamps instead of vote points; reward points are a staff-only view. */
class ReadPointsCommandTest {

    @Test
    void playersSeeStampsOnly() {
        String line = ReadPointsCommand.line(false, 12, 99);
        assertEquals("Slums Stamps: 12", line);
        assertFalse(line.contains("RewardPoints"));
    }

    @Test
    void staffCanAskForRewardPoints() {
        assertEquals("RewardPoints: 99", ReadPointsCommand.line(true, 12, 99));
    }
}
