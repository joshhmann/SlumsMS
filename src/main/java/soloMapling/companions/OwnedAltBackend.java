package soloMapling.companions;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import net.server.Server;
import net.server.world.Party;
import server.Trade;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import tools.DatabaseConnection;

import java.sql.*;
import java.util.*;

/** Production adapter: real character rows and normal inventory persistence, never BotStore. */
final class OwnedAltBackend implements OwnedAltService.Backend {
    @Override public List<OwnedAltService.Candidate> list(Character owner) {
        List<OwnedAltService.Candidate> rows = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, accountid, world, name FROM characters WHERE accountid = ? AND world = ? ORDER BY name")) {
            ps.setInt(1, owner.getAccountID());
            ps.setInt(2, owner.getWorld());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new OwnedAltService.Candidate(rs.getInt("id"), rs.getInt("accountid"),
                        rs.getInt("world"), rs.getString("name")));
            }
            return rows;
        } catch (SQLException e) { throw new IllegalStateException("Unable to list your characters. Please try again later.", e); }
    }
    @Override public boolean online(int id) {
        return Server.getInstance().getWorlds().stream().anyMatch(w -> w.getPlayerStorage().getCharacterById(id) != null);
    }
    @Override public Character load(OwnedAltService.Candidate row, Character owner) {
        BotClient client = new BotClient(owner.getWorld(), owner.getClient().getChannel());
        client.setAccID(owner.getAccountID());
        client.setAccountName(owner.getClient().getAccountName());
        try {
            Character alt = Character.loadCharFromDB(row.id(), client, true);
            client.setPlayer(alt); // private headless client: inventory operations use the real character
            alt.markOwnedAlt(owner);
            alt.setParty(null);
            alt.setMPC(null);
            alt.resetPlayerRates();
            if (YamlConfig.config.server.USE_ADD_RATES_BY_LEVEL) alt.setPlayerRates();
            alt.setWorldRates();
            if (YamlConfig.config.server.USE_REBIRTH_SYSTEM) {
                int rebirths = alt.getReborns();
                if (rebirths == 1) alt.setPlayerExpRatesCerezeth(YamlConfig.config.server.REBIRTH_FIRST_RATE);
                else if (rebirths == 2) alt.setPlayerExpRatesCerezeth(YamlConfig.config.server.REBIRTH_SECOND_RATE);
                else if (rebirths == 3) alt.setPlayerExpRatesCerezeth(alt.getLevel() >= 200
                        ? YamlConfig.config.server.REBIRTH_FINAL_RATE : YamlConfig.config.server.REBIRTH_THIRD_RATE);
            }
            alt.updateCouponRates();
            return alt;
        } catch (SQLException e) { throw new IllegalStateException("Unable to load that character. Please try again later.", e); }
    }
    @Override public void attach(Character owner, Character alt) {
        var target = owner.getMap();
        if (target == null || target.getChannelServer() != alt.getClient().getChannelServer())
            throw new IllegalStateException("Your channel changed while loading. Please call your alt again.");
        if (owner.getEventInstance() != null || target.getEventInstance() != null)
            throw new IllegalStateException("Call your alt before entering an instance.");
        if (owner.getParty() == null && !Party.createParty(owner, true))
            throw new IllegalStateException("Please create a party first.");
        if (owner.getParty().getLeaderId() != owner.getId() || owner.getParty().getMembers().size() >= 6)
            throw new IllegalStateException("Lead a party with a free place before calling your alt.");
        // A saved party is not permission to join an unrelated group.
        alt.setParty(null);
        alt.setMPC(null);
        alt.setMap(target);
        alt.setPosition(OwnedAltService.groundedSpawn(target, owner.getPosition()));
        alt.setStance(0); // standing, never a saved jump/fly stance
        alt.getClient().getChannelServer().addPlayer(alt);
        alt.getWorldServer().addPlayer(alt);
        alt.setEnteredChannelWorld();
        target.addPlayer(alt);
        if (!Party.joinParty(alt, owner.getParty().getId(), true))
            throw new IllegalStateException("Your party has no free place.");
    }
    @Override public void save(Character alt) {
        // Return any real trade escrow before persisting. Never invoke BotTradeCommands/holdings.
        Trade.cancelTrade(alt, Trade.TradeResult.PARTNER_CANCEL);
        alt.saveOwnedAltToDB();
    }
    @Override public void detach(Character alt) {
        alt.setDisconnectedFromChannelWorld();
        if (alt.getEventInstance() != null) alt.getEventInstance().playerDisconnected(alt);
        if (alt.getParty() != null) alt.leaveParty();
        if (alt.getMap() != null) alt.getMap().removePlayer(alt);
        alt.getClient().getChannelServer().removePlayer(alt);
        alt.getWorldServer().removePlayer(alt);
        alt.logOff();
        alt.empty(true);
    }
    @Override public Set<Integer> companions(int ownerId) {
        Set<Integer> ids = new HashSet<>();
        CharacterStorage.getAllBots().forEach((id, bot) -> {
            if (bot instanceof FollowerBot follower && follower.leaderId() == ownerId) ids.add(id);
        });
        return ids;
    }
}
