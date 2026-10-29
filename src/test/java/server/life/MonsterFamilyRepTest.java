package server.life;

import client.BotClient;
import client.Character;
import client.Client;
import client.Family;
import client.FamilyEntry;
import config.YamlConfig;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Auto-QA t-fy-2 on TEST (2026-10-29): a bot junior's REAL kill gave its senior +0 rep. The bot was in a party, and the
 * party EXP list ({@code Character.getPartyMembersOnSameMap}) keeps only members logged in to the world, which a bot
 * never is: no EXP, so no rep. A solo bot was fine. Also: stock gave a party member the kill's rep twice. Drives the
 * real kill path ({@code Monster.killBy} -> {@code distributeExperience}).
 */
class MonsterFamilyRepTest {

    int killWas;

    @BeforeEach
    void tuning() {
        killWas = YamlConfig.config.server.FAMILY_REP_PER_KILL;
        YamlConfig.config.server.FAMILY_REP_PER_KILL = 4; // live's tuning (the test config has none)
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.FAMILY_REP_PER_KILL = killWas;
    }

    @SuppressWarnings("unchecked")
    static void putOnMap(MapleMap map, Character chr) throws Exception {
        var f = MapleMap.class.getDeclaredField("characters");
        f.setAccessible(true);
        ((Collection<Character>) f.get(map)).add(chr);
    }

    static Monster mushroom(MapleMap map) {
        MonsterStats stats = new MonsterStats();
        stats.setHp(80L);
        stats.setExp(15);
        Monster mob = new Monster(1210102, stats);
        mob.setMap(map);
        return mob;
    }

    static Character member(int id, boolean bot, int level, FamilyEntry entry, MapleMap map) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getLevel()).thenReturn(level);
        when(c.getMap()).thenReturn(map);
        when(c.getFamilyEntry()).thenReturn(entry);
        when(c.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        when(c.isAlive()).thenReturn(true);
        when(c.getName()).thenReturn("c" + id);
        return c;
    }

    @Test
    void aPartiedBotJuniorsRealKillGivesItsSeniorRep() throws Exception {
        MapleMap map = new MapleMap(910000997, 0, 0, 910000997, 1.0f);
        Family fam = mock(Family.class);
        FamilyEntry senior = new FamilyEntry(fam, 101, "Senior", 90, null);
        FamilyEntry junior = new FamilyEntry(fam, 1_000_000_009, "BotJr", 85, null);
        junior.setSenior(senior, false);
        Character bot = member(1_000_000_009, true, 85, junior, map);
        Party party = mock(Party.class);
        when(bot.getParty()).thenReturn(party);
        when(bot.getPartyMembersOnSameMap()).thenReturn(List.of()); // what the real one returns for a bot
        putOnMap(map, bot);

        Monster mob = mushroom(map);
        mob.damage(bot, 80, false);
        mob.killBy(bot);

        assertEquals(4, senior.getReputation(), "the bot junior's real kill gives its senior the kill's rep");
    }

    @Test
    void aPartyMembersKillGivesTheRepOnceNotTwice() throws Exception {
        MapleMap map = new MapleMap(910000996, 0, 0, 910000996, 1.0f);
        Family fam = mock(Family.class);
        FamilyEntry senior = new FamilyEntry(fam, 201, "Senior", 90, null);
        FamilyEntry junior = new FamilyEntry(fam, 202, "Junior", 50, null);
        junior.setSenior(senior, false);
        Character player = member(202, false, 50, junior, map);
        Party party = mock(Party.class);
        when(player.getParty()).thenReturn(party);
        when(player.getPartyMembersOnSameMap()).thenReturn(List.of(player));
        when(player.getExpRate()).thenReturn(1);
        putOnMap(map, player);

        Monster mob = mushroom(map);
        mob.damage(player, 80, false);
        mob.killBy(player);

        assertEquals(4, senior.getReputation(), "stock gave a party member's senior 8 (the rep twice)");
    }
}
