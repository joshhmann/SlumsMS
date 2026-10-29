/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package client;

import net.packet.Packet;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Jay Estrella - Mr.Trash :3
 * @author Ubaware
 */
public class Family {
    private static final Logger log = LoggerFactory.getLogger(Family.class);

    /**
     * 1.6.1 (TEST 2026-10-29: boot stopped on families 4 and 8 with no leader row): joining a family makes the junior
     * visible in memory before its row is written, and the auto-QA teardown deleted the family's rows in that window,
     * so the junior's row landed after the delete, pointing at a senior row that was gone. A join's in-memory and
     * database writes and a dissolve now run one at a time under this lock.
     */
    private static final java.util.concurrent.locks.ReentrantLock MEMBERSHIP_LOCK = new java.util.concurrent.locks.ReentrantLock();

    /** Runs {@code work} holding the family membership lock (a join, or a dissolve). */
    public static <T> T underMembershipLock(java.util.function.Supplier<T> work) {
        MEMBERSHIP_LOCK.lock();
        try {
            return work.get();
        } finally {
            MEMBERSHIP_LOCK.unlock();
        }
    }

    /** Tests: whether a join or a dissolve holds the lock now. */
    static boolean membershipLocked() {
        return MEMBERSHIP_LOCK.isLocked();
    }

    /** A family_character row: the character, its family and its senior (0 = none). */
    public record Row(int cid, int familyId, int seniorId) {
    }

    /**
     * The rows of family {@code familyId} that may be deleted, juniors before their seniors, never a row another row
     * still names as its senior unless that junior is deleted first. {@code rows} are the family's rows plus any row
     * naming one of them as senior (another family's junior keeps its senior's row, and so its whole chain up).
     */
    public static List<Integer> safeDeletionOrder(int familyId, List<Row> rows) {
        java.util.Set<Integer> mine = new java.util.HashSet<>();
        for (Row r : rows) {
            if (r.familyId() == familyId) {
                mine.add(r.cid());
            }
        }
        List<Integer> order = new ArrayList<>();
        java.util.Set<Integer> deleted = new java.util.HashSet<>();
        java.util.Set<Integer> left = new java.util.HashSet<>(mine);
        boolean progress = true;
        while (progress && !left.isEmpty()) {
            progress = false;
            for (Integer cid : new ArrayList<>(left)) {
                boolean namedBySomeoneStaying = false;
                for (Row r : rows) {
                    if (r.seniorId() == cid && r.cid() != cid && !deleted.contains(r.cid())) {
                        namedBySomeoneStaying = true;
                        break;
                    }
                }
                if (!namedBySomeoneStaying) {
                    order.add(cid);
                    deleted.add(cid);
                    left.remove(cid);
                    progress = true;
                }
            }
        }
        return order; // anything still in "left" is in a cycle: never deleted
    }
    private static final AtomicInteger familyIDCounter = new AtomicInteger();

    private final int id, world;
    private final Map<Integer, FamilyEntry> members = new ConcurrentHashMap<>();
    private FamilyEntry leader;
    private String name;
    private String preceptsMessage = "";
    private int totalGenerations;

    public Family(int id, int world) {
        int newId = id;
        if (id == -1) {
            // get next available family id
            while (idInUse(newId = familyIDCounter.incrementAndGet())) {
            }
        }
        this.id = newId;
        this.world = world;
    }

    private static boolean idInUse(int id) {
        for (World world : Server.getInstance().getWorlds()) {
            if (world.getFamily(id) != null) {
                return true;
            }
        }
        return false;
    }

    public int getID() {
        return id;
    }

    public int getWorld() {
        return world;
    }

    public void setLeader(FamilyEntry leader) {
        this.leader = leader;
        setName(leader.getName());
    }

    public FamilyEntry getLeader() {
        return leader;
    }

    private void setName(String name) {
        this.name = name;
    }

    public int getTotalMembers() {
        return members.size();
    }

    public int getTotalGenerations() {
        return totalGenerations;
    }

    public void setTotalGenerations(int generations) {
        this.totalGenerations = generations;
    }

    public String getName() {
        return this.name;
    }

    public void setMessage(String message, boolean save) {
        this.preceptsMessage = message;
        if (save) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE family_character SET precepts = ? WHERE cid = ?")) {
                ps.setString(1, message);
                ps.setInt(2, getLeader().getChrId());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Could not save new precepts for family {}", getID(), e);
            }
        }
    }

    public String getMessage() {
        return preceptsMessage;
    }

    public void addEntry(FamilyEntry entry) {
        members.put(entry.getChrId(), entry);
    }

    public void removeEntryBranch(FamilyEntry root) {
        members.remove(root.getChrId());
        for (FamilyEntry junior : root.getJuniors()) {
            if (junior != null) {
                removeEntryBranch(junior);
            }
        }
    }

    public void addEntryTree(FamilyEntry root) {
        members.put(root.getChrId(), root);
        for (FamilyEntry junior : root.getJuniors()) {
            if (junior != null) {
                addEntryTree(junior);
            }
        }
    }

    /** Every member (a copy), for QA teardown (1.6.1). */
    public java.util.List<FamilyEntry> getMembers() {
        return new java.util.ArrayList<>(members.values());
    }

    public FamilyEntry getEntryByID(int cid) {
        return members.get(cid);
    }

    public void broadcast(Packet packet) {
        broadcast(packet, -1);
    }

    public void broadcast(Packet packet, int ignoreID) {
        for (FamilyEntry entry : members.values()) {
            Character chr = entry.getChr();
            if (chr != null) {
                if (chr.getId() == ignoreID) {
                    continue;
                }
                chr.sendPacket(packet);
            }
        }
    }

    public void broadcastFamilyInfoUpdate() {
        for (FamilyEntry entry : members.values()) {
            Character chr = entry.getChr();
            if (chr != null) {
                chr.sendPacket(PacketCreator.getFamilyInfo(entry));
            }
        }
    }

    public void resetDailyReps() {
        for (FamilyEntry entry : members.values()) {
            entry.setTodaysRep(0);
            entry.setRepsToSenior(0);
            entry.resetEntitlementUsages();
        }
    }

    /** Character ids from here up are bots (BotStore.BOT_ID_BASE). */
    static final int BOT_ID_BASE = 1_000_000_000;

    public static void loadAllFamilies(Connection con) {
        List<Pair<Pair<Integer, Integer>, FamilyEntry>> unmatchedJuniors = new ArrayList<>(200); // <<world, seniorid> familyEntry>
        try (PreparedStatement psEntries = con.prepareStatement("SELECT * FROM family_character")) {
            ResultSet rsEntries = psEntries.executeQuery();
            while (rsEntries.next()) { // can be optimized
                int cid = rsEntries.getInt("cid");
                String name = null;
                int level = -1;
                int jobID = -1;
                int world = -1;
                boolean bot = cid >= BOT_ID_BASE; // 1.6.1 ticket 53: a bot junior has a botstore row, no characters row
                try (PreparedStatement ps = con.prepareStatement(bot
                        ? "SELECT ? AS world, Name AS name, Level AS level, Job AS job FROM botstore WHERE BotId = ?"
                        : "SELECT world, name, level, job FROM characters WHERE id = ?")) {
                    if (bot) {
                        ps.setInt(1, soloMapling.server.SoloMaplingConstants.GameConstants.homeWorld());
                        ps.setInt(2, cid - BOT_ID_BASE);
                    } else {
                        ps.setInt(1, cid);
                    }
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        world = rs.getInt("world");
                        name = rs.getString("name");
                        level = rs.getInt("level");
                        jobID = rs.getInt("job");
                    } else {
                        log.error("Could not load character information of chrId {} in loadAllFamilies(). (RECORD DOES NOT EXIST)", cid);
                        continue;
                    }
                } catch (SQLException e) {
                    log.error("Could not load character information of chrId {} in loadAllFamilies(). (SQL ERROR)", cid, e);
                    continue;
                }
                int familyid = rsEntries.getInt("familyid");
                int seniorid = rsEntries.getInt("seniorid");
                int reputation = rsEntries.getInt("reputation");
                int todaysRep = rsEntries.getInt("todaysrep");
                int totalRep = rsEntries.getInt("totalreputation");
                int repsToSenior = rsEntries.getInt("reptosenior");
                String precepts = rsEntries.getString("precepts");
                //Timestamp lastResetTime = rsEntries.getTimestamp("lastresettime"); //taken care of by FamilyDailyResetTask
                World wserv = Server.getInstance().getWorld(world);
                if (wserv == null) {
                    continue;
                }
                Family family = wserv.getFamily(familyid);
                if (family == null) {
                    family = new Family(familyid, world);
                    Server.getInstance().getWorld(world).addFamily(familyid, family);
                }
                FamilyEntry familyEntry = new FamilyEntry(family, cid, name, level, Job.getById(jobID));
                family.addEntry(familyEntry);
                if (seniorid <= 0) {
                    family.setLeader(familyEntry);
                    family.setMessage(precepts, false);
                }
                FamilyEntry senior = family.getEntryByID(seniorid);
                if (senior != null) {
                    familyEntry.setSenior(family.getEntryByID(seniorid), false);
                } else {
                    if (seniorid > 0) {
                        unmatchedJuniors.add(new Pair<>(new Pair<>(world, seniorid), familyEntry));
                    }
                }
                familyEntry.setReputation(reputation);
                familyEntry.setTodaysRep(todaysRep);
                familyEntry.setTotalReputation(totalRep);
                familyEntry.setRepsToSenior(repsToSenior);
                //load used entitlements
                try (PreparedStatement ps = con.prepareStatement("SELECT entitlementid FROM family_entitlement WHERE charid = ?")) {
                    ps.setInt(1, familyEntry.getChrId());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        familyEntry.setEntitlementUsed(rs.getInt("entitlementid"));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Could not get family_character entries", e);
        }
        // link missing ones (out of order)
        for (Pair<Pair<Integer, Integer>, FamilyEntry> unmatchedJunior : unmatchedJuniors) {
            int world = unmatchedJunior.getLeft().getLeft();
            int seniorid = unmatchedJunior.getLeft().getRight();
            FamilyEntry junior = unmatchedJunior.getRight();
            FamilyEntry senior = Server.getInstance().getWorld(world).getFamily(junior.getFamily().getID()).getEntryByID(seniorid);
            if (senior != null) {
                junior.setSenior(senior, false);
            } else {
                log.error("Missing senior for chr {} in world {}", junior.getName(), world);
            }
        }

        for (World world : Server.getInstance().getWorlds()) {
            for (Family family : world.getFamilies()) {
                if (family.getLeader() == null && !repairMissingLeader(family)) {
                    continue;
                }
                family.getLeader().doFullCount();
            }
        }
    }

    /**
     * 1.6.1: a family whose leader row is gone (its senior chain ends in a missing character) must not stop the boot.
     * The member with no senior and the lowest id becomes the leader in memory; nothing is written, so a staff fix in
     * the database still wins at the next boot. Returns false for an empty family, which is skipped.
     */
    static boolean repairMissingLeader(Family family) {
        FamilyEntry pick = null;
        for (FamilyEntry entry : family.members.values()) {
            if (entry.getSenior() == null && (pick == null || entry.getChrId() < pick.getChrId())) {
                pick = entry;
            }
        }
        if (pick == null) {
            log.error("Family {} has no leader and no member without a senior; skipped", family.getID());
            return false;
        }
        log.error("Family {} has no leader row; {} (id {}) leads it until staff fix the data", family.getID(),
                pick.getName(), pick.getChrId());
        family.setLeader(pick);
        return true;
    }

    public void saveAllMembersRep() { //was used for autosave task, but character autosave should be enough
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            boolean success = true;
            for (FamilyEntry entry : members.values()) {
                success = entry.saveReputation(con);
                if (!success) {
                    break;
                }
            }
            if (!success) {
                con.rollback();
                log.error("Family rep autosave failed for family {}", getID());
            }
            con.setAutoCommit(true);
            //reset repChanged after successful save
            for (FamilyEntry entry : members.values()) {
                entry.savedSuccessfully();
            }
        } catch (SQLException e) {
            log.error("Could not get connection to DB while saving all members rep", e);
        }
    }
}
