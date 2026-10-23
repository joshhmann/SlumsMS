package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.BotClient;
import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6, owner QA 2026-10-23 (t-gu-5: "some are in a party already, but was able to get one"): a crew member gathering
 * at the boss entrance leaves a party of bots, so the player's party invite reaches it.
 */
class GuildBossRunCrewPartyTest {

    final List<String> left = new ArrayList<>();

    @AfterEach
    void tearDown() {
        GuildBossRuns.resetForTest();
        GuildBossRuns.usePartyLeaverForTest(null);
    }

    private Character bot(int n) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + n);
        c.setName("crew" + n);
        return c;
    }

    private Character player(int id) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName("player" + id);
        return c;
    }

    private void gathering(Character... crew) {
        List<Integer> ids = new ArrayList<>();
        for (Character c : crew) {
            ids.add(c.getId());
        }
        GuildBossRuns.setForTest(new GuildBossRuns.Run(1, "Legendz", GuildRules.boss("pap"), 1, ids, 60_000, 120_000,
                "test", System.currentTimeMillis()));
        GuildBossRuns.usePartyLeaverForTest(c -> {
            left.add(c.getName());
            c.setParty(null);
        });
    }

    private static Party partyOf(Character... members) {
        Party p = new Party(7, new PartyCharacter(members[0]));
        for (Character m : members) {
            p.addMember(new PartyCharacter(m));
            m.setParty(p);
        }
        return p;
    }

    @Test
    void aGatheringCrewMemberInABotPartyLeavesItForThePlayersInvite() {
        Character crew = bot(1);
        Character friend = bot(2);
        gathering(crew);
        partyOf(crew, friend);
        assertTrue(GuildBossRuns.freeForInvite(crew));
        assertEquals(List.of("crew1"), left);
        assertTrue(crew.getParty() == null, "free to take the invite");
    }

    @Test
    void aPartyWithARealPlayerIsKept() {
        Character crew = bot(1);
        gathering(crew);
        partyOf(crew, player(42));
        assertFalse(GuildBossRuns.freeForInvite(crew));
        assertTrue(left.isEmpty());
    }

    @Test
    void aBotOutsideTheRunIsLeftAlone() {
        Character crew = bot(1);
        Character other = bot(3);
        gathering(crew);
        partyOf(other, bot(4));
        assertFalse(GuildBossRuns.freeForInvite(other));
        assertTrue(left.isEmpty());
    }
}
