package net.server.guild;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * rc6 (owner QA 2026-10-23, t-gw-2 / t-gu-2): an offline guild member loaded from the database kept world 0, so on a
 * server whose world is 1 the new guild's master never got rank 1 (Guild.changeRank looked up world 0 and threw).
 */
class GuildCharacterWorldTest {

    @Test
    void anOfflineMemberKeepsItsWorld() {
        GuildCharacter offline = new GuildCharacter(null, 1, 30, "Admin", -1, 1, 0, 5, 11, false, 5);
        assertEquals(1, offline.getWorld(), "the world it was loaded with, online or not");
        assertEquals(5, offline.getGuildRank());
    }

    @Test
    void anOnlineMemberStillGetsItsChannel() {
        GuildCharacter online = new GuildCharacter(null, 2, 30, "Bob", 3, 1, 0, 2, 11, true, 5);
        assertEquals(1, online.getWorld());
        assertEquals(3, online.getChannel());
    }
}
