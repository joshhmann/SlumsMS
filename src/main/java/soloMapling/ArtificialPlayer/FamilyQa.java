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
        Character bot = addBotJunior(player, java.util.Set.of());
        return new Staged(bot, player.getFamily().getID());
    }

    /**
     * Makes an online regular {@code senior}'s junior through its real answer ({@link BotFamily#decide}, staged to say
     * yes). Prefers a bot within 20 levels, on the senior's channel and map, and free to come when summoned. Throws
     * with the reason when none took it.
     */
    public static Character addBotJunior(Character senior, java.util.Set<Integer> exclude) {
        int ch = senior.getClient().getChannel();
        List<Character> bots = new ArrayList<>();
        // The active bots (a bot is never "logged in" the way a player is: TEST 2026-10-28 found 0 candidates)
        for (BotSM sm : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
            Character c = sm == null ? null : sm.getChr();
            if (c != null && c.isBot() && c.getMap() != null && c.getFamily() == null && c.getLevel() > 10
                    && c.getWorld() == senior.getWorld() && !exclude.contains(c.getId())) {
                bots.add(c);
            }
        }
        bots.sort(Comparator.comparingInt((Character c) -> Math.abs(c.getLevel() - senior.getLevel()) > 20 ? 1 : 0)
                .thenComparingInt(c -> c.getClient() != null && c.getClient().getChannel() == ch ? 0 : 1)
                .thenComparingInt(c -> c.getMapId() == senior.getMapId() ? 0 : 1));
        // among the first few, one free to come when summoned first (the check is not cheap: not for every bot)
        List<Character> head = new ArrayList<>(bots.subList(0, Math.min(40, bots.size())));
        head.sort(Comparator.comparingInt(c -> BotFamily.summonRefusal(c, senior) == null ? 0 : 1));
        for (int i = 0; i < head.size(); i++) {
            bots.set(i, head.get(i));
        }
        BotFamily.certainFor(60_000);
        try {
            for (Character bot : bots.subList(0, Math.min(5, bots.size()))) {
                InviteCoordinator.createInvite(InviteCoordinator.InviteType.FAMILY, senior, bot, bot.getId());
                BotFamily.Verdict v = BotFamily.decide(bot, senior);
                if (v == BotFamily.Verdict.ACCEPT && bot.getFamily() != null && bot.getFamily() == senior.getFamily()) {
                    return bot;
                }
                InviteCoordinator.removeInvite(InviteCoordinator.InviteType.FAMILY, bot.getId());
            }
        } finally {
            BotFamily.certainFor(0);
        }
        throw new IllegalStateException("no online bot (level 11+, not in a family) took the junior invite ("
                + bots.size() + " candidates)");
    }

    /** Makes player {@code junior} (no family) {@code senior}'s junior, as its Accept click does. */
    public static void addPlayerJunior(Character senior, Character junior) {
        if (junior.getFamily() != null) {
            throw new IllegalStateException(junior.getName() + " is already in a family");
        }
        InviteCoordinator.createInvite(InviteCoordinator.InviteType.FAMILY, senior, junior, junior.getId());
        net.server.channel.handlers.AcceptFamilyHandler.answer(junior, senior, true);
        if (junior.getFamily() == null || junior.getFamily() != senior.getFamily()) {
            throw new IllegalStateException(junior.getName() + " did not join " + senior.getName() + "'s family");
        }
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
                FamilyBuffs.clear(e.getChrId()); // 1.6.1: a second pretend player in the family had buffs too
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
