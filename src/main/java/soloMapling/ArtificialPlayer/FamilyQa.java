package soloMapling.ArtificialPlayer;

import client.Character;
import client.Family;
import client.FamilyBuffs;
import client.FamilyEntry;
import config.YamlConfig;
import net.server.coordinator.world.InviteCoordinator;
import net.server.world.World;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 1.6.1 ticket 53: staging for {@code !qa fy-1} and auto-QA t-fy-1. A player with no family invites an online
 * roster bot as their junior (the bot answers through {@link BotFamily}, staged to say yes), and the teardown
 * dissolves that family again (its rows, the players' familyid, the world's copy, any Family buff).
 */
public final class FamilyQa {

    /** What the staging made. */
    public record Staged(Character bot, int familyId) {
    }

    private FamilyQa() {
    }

    /** Forms a family with {@code player} as senior and an online bot as junior; throws with the reason if it cannot. */
    public static Staged stage(Character player) {
        if (!YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            throw new IllegalStateException("USE_FAMILY_SYSTEM is off on this server");
        }
        if (player.getFamily() != null) {
            throw new IllegalStateException(player.getName() + " is already in a family (leave it first)");
        }
        World world = player.getWorldServer();
        List<Character> bots = new ArrayList<>();
        // The active bots (a bot is never "logged in" the way a player is: TEST 2026-10-28 found 0 candidates)
        for (BotSM sm : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
            Character c = sm == null ? null : sm.getChr();
            if (c != null && c.isBot() && c.getMap() != null && c.getFamily() == null && c.getLevel() > 10
                    && c.getWorld() == player.getWorld()) {
                bots.add(c);
            }
        }
        bots.sort(Comparator.comparingInt((Character c) -> Math.abs(c.getLevel() - player.getLevel()) > 20 ? 1 : 0)
                .thenComparingInt(c -> c.getMapId() == player.getMapId() ? 0 : 1));
        BotFamily.certainFor(60_000);
        try {
            for (Character bot : bots.subList(0, Math.min(5, bots.size()))) {
                InviteCoordinator.createInvite(InviteCoordinator.InviteType.FAMILY, player, bot, bot.getId());
                BotFamily.Verdict v = BotFamily.decide(bot, player);
                if (v == BotFamily.Verdict.ACCEPT && bot.getFamily() != null && bot.getFamily() == player.getFamily()) {
                    return new Staged(bot, player.getFamily().getID());
                }
                InviteCoordinator.removeInvite(InviteCoordinator.InviteType.FAMILY, bot.getId());
            }
        } finally {
            BotFamily.certainFor(0);
        }
        throw new IllegalStateException("no online bot (level 11+, not in a family) took the junior invite ("
                + bots.size() + " candidates)");
    }

    /** Dissolves the staged family: rows, familyid, the world's copy and {@code player}'s Family buffs. */
    public static String teardown(Character player, Staged st) {
        FamilyBuffs.clear(player.getId());
        World world = player.getWorldServer();
        Family f = world.getFamily(st.familyId());
        List<Integer> ids = new ArrayList<>();
        if (f != null) {
            for (FamilyEntry e : f.getMembers()) {
                ids.add(e.getChrId());
                Character c = e.getChr();
                if (c != null) {
                    e.setCharacter(null);
                    c.setFamilyEntry(null);
                    c.setFamilyId(0);
                }
            }
            world.removeFamily(st.familyId());
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            for (int id : ids) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM family_entitlement WHERE charid = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM family_character WHERE familyid = ?")) {
                ps.setInt(1, st.familyId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET familyid = 0 WHERE familyid = ?")) {
                ps.setInt(1, st.familyId());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            return "family " + st.familyId() + " left in the database: " + e.getMessage();
        }
        return "family " + st.familyId() + " dissolved (" + ids.size() + " members)";
    }
}
