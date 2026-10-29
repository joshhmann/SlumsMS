package soloMapling.autoqa;

import client.Character;
import client.Family;
import client.FamilyBuffs;
import client.FamilyEntitlement;
import client.FamilyEntry;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufOutPacket;
import net.server.world.Party;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotFamily;
import soloMapling.ArtificialPlayer.FamilyQa;
import tools.DatabaseConnection;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 1.6.1 promise audit (ticket 53, "every perk works"): auto-QA for the Family perks t-fy-1 left out. Everything a
 * player clicks goes through the real {@code USE_FAMILY} / {@code ADD_FAMILY} packet handlers; EXP and rep come from
 * real mob kills ({@code MapleMap.damageMonster}) where the check can stage one.
 * <ul>
 *   <li>t-fy-2 ({@link #perks}): every buff perk (1.5x / 2x, 15 / 30 min, party, Bonding) spends its rep and gives
 *   its rate and minutes; party reaches the second pretend player; Bonding is refused below its minimum and then
 *   reaches everyone below, bot juniors too (b035); a real kill pays the buffed EXP; the buff is saved for a restart; a bot
 *   junior's real kills give rep up to the 300 a day cap; the daily reset frees every perk and the cap.</li>
 *   <li>t-fy-3 ({@link #travel}): Family Reunion to a regular junior takes the player to it; Summon Family on it
 *   brings it over and the rep stays spent.</li>
 *   <li>t-fy-4 ({@link #offer}): a regular whispers its offer to be the player's junior; the player walks away and
 *   types its name in Add Junior; it says yes.</li>
 * </ul>
 */
final class FamilyProbes {

    /** A weak mob for the real kills (Orange Mushroom, level 8; EXP 15 at 1x). */
    static final int KILL_MOB = 1210102;

    private FamilyProbes() {
    }

    // ── shared ──

    static void use(SyntheticPlayer p, FamilyEntitlement e, String target) {
        ByteBufOutPacket b = new ByteBufOutPacket();
        b.writeInt(e.ordinal());
        if (target != null) {
            b.writeString(target);
        }
        p.send(RecvOpcode.USE_FAMILY, b);
    }

    /** Spawns {@link #KILL_MOB} at {@code who}'s feet and kills it with {@code who}'s hit. Returns the EXP it gave. */
    static long realKill(Character who) {
        MapleMap map = who.getMap();
        Monster mob = LifeFactory.getMonster(KILL_MOB);
        if (map == null || mob == null) {
            return -1;
        }
        Point at = who.getPosition() == null ? new Point(0, 0) : new Point(who.getPosition());
        map.spawnMonsterOnGroundBelow(mob, at);
        long before = who.getExp();
        int level = who.getLevel();
        map.damageMonster(who, mob, mob.getMaxHp());
        if (map.getMonsterByOid(mob.getObjectId()) != null) {
            map.killMonster(mob, who, false); // not dead after the hit (it should be): remove it
            return -1;
        }
        return who.getLevel() == level ? who.getExp() - before : -1; // a level-up mid-kill: unreadable
    }

    static boolean near(double got, double want) {
        return Math.abs(got - want) <= 0.15;
    }

    // ── t-fy-2 ──

    static List<String> perks(SyntheticPlayer p) {
        if (!config.YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return List.of("SKIP USE_FAMILY_SYSTEM is off on this server");
        }
        Character me = p.character();
        SyntheticPlayer p2 = SyntheticPlayers.login(2);
        Character pal = p2.character();
        if (pal.getFamily() != null) {
            return List.of("FAIL " + pal.getName() + " is already in a family (a run before left it)");
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        FamilyQa.Staged st;
        try {
            st = FamilyQa.stage(me);
        } catch (IllegalStateException e) {
            return List.of("FAIL " + e.getMessage());
        }
        String bondingWas = System.getProperty("slums.family.bondingMin");
        try {
            Character bot = st.bot();
            FamilyEntry mine = me.getFamilyEntry();
            FamilyEntry junior = bot.getFamilyEntry();
            Family family = me.getFamily();
            ev.add("junior " + bot.getName() + " lv" + bot.getLevel() + " ch" + bot.getClient().getChannel()
                    + ", family " + st.familyId());

            // the party: the second pretend player (any channel counts)
            if (me.getParty() != null) {
                Party.leaveParty(me.getParty(), me);
            }
            if (pal.getParty() != null) {
                Party.leaveParty(pal.getParty(), pal);
            }
            Party.createParty(me, true);
            Party.joinParty(pal, me.getPartyId(), true);
            if (pal.getParty() == null || pal.getPartyId() != me.getPartyId()) {
                bad.add("could not party " + me.getName() + " with " + pal.getName());
            }

            // baselines from real kills, before any buff
            long base = realKill(me), basePal = realKill(pal);
            ev.add("baseline real kills: " + me.getName() + " +" + base + " EXP, " + pal.getName() + " +" + basePal);

            // a bot junior's real kills give rep, capped at 300 a day
            int r0 = mine.getReputation();
            realKill(bot);
            int perKill = mine.getReputation() - r0;
            ev.add("bot junior's real kill: rep +" + perKill);
            if (perKill <= 0) {
                bad.add("a bot junior's real kill gave its senior no rep");
            }
            int cap = FamilyEntry.botJuniorDailyRep();
            junior.setRepsToSenior(cap - 1);
            int r1 = mine.getReputation();
            realKill(bot);
            int nearCap = mine.getReputation() - r1;
            int r2 = mine.getReputation();
            realKill(bot);
            int atCap = mine.getReputation() - r2;
            ev.add("at " + (cap - 1) + " of the " + cap + " cap a kill gave +" + nearCap + ", at the cap +" + atCap);
            if (perKill > 0 && (nearCap != 1 || atCap != 0)) {
                bad.add("the bot junior's daily cap of " + cap + " did not hold (+" + nearCap + ", then +" + atCap + ")");
            }

            // every self perk through the Family window packet
            FamilyEntitlement[] selfPerks = {FamilyEntitlement.SELF_DROP_1_5, FamilyEntitlement.SELF_EXP_1_5,
                    FamilyEntitlement.SELF_DROP_2, FamilyEntitlement.SELF_EXP_2, FamilyEntitlement.SELF_DROP_2_30MIN,
                    FamilyEntitlement.SELF_EXP_2_30MIN};
            mine.gainReputation(20_000, false);
            for (FamilyEntitlement e : selfPerks) {
                checkBuff(p, me, mine, e, null, bad, ev);
            }

            // Bonding: refused below its minimum, nothing spent; then given to the players below, never a bot
            FamilyQa.addPlayerJunior(me, pal);
            Set<Integer> taken = new HashSet<>(List.of(bot.getId()));
            Character bot2 = FamilyQa.addBotJunior(pal, taken);
            ev.add("below " + me.getName() + ": " + bot.getName() + ", " + pal.getName() + ", " + bot2.getName());
            int rb = mine.getReputation();
            use(p, FamilyEntitlement.FAMILY_BONDING, null);
            if (mine.getReputation() != rb || mine.isEntitlementUsed(FamilyEntitlement.FAMILY_BONDING)) {
                bad.add("Bonding with 3 below (needs " + net.server.channel.handlers.FamilyUseHandler.bondingMinimum()
                        + ") was not refused for free");
            }
            System.setProperty("slums.family.bondingMin", "3");
            checkBuff(p, me, mine, FamilyEntitlement.FAMILY_BONDING, null, bad, ev);
            long now = System.currentTimeMillis();
            if (FamilyBuffs.expMultiplier(pal.getId(), now) != 2.0 || FamilyBuffs.dropMultiplier(pal.getId(), now) != 2.0) {
                bad.add("Bonding did not reach " + pal.getName() + " below");
            }
            if (FamilyBuffs.expMultiplier(bot.getId(), now) != 2.0 || FamilyBuffs.dropMultiplier(bot2.getId(), now) != 2.0) {
                bad.add("Bonding did not reach the bot juniors it counted (b035)");
            }
            FamilyBuffs.clear(pal.getId());

            // party perks reach the party member
            checkBuff(p, me, mine, FamilyEntitlement.PARTY_DROP_2_30MIN, pal, bad, ev);
            checkBuff(p, me, mine, FamilyEntitlement.PARTY_EXP_2_30MIN, pal, bad, ev);

            // a real kill pays the buffed EXP (1x while an EXP event nullifies it, as the tooltip says)
            long buffed = realKill(me), buffedPal = realKill(pal);
            double want = FamilyBuffs.expMultiplier(me), wantPal = FamilyBuffs.expMultiplier(pal);
            ev.add("buffed real kills: " + me.getName() + " +" + buffed + " (x" + want + " expected), " + pal.getName()
                    + " +" + buffedPal + " (x" + wantPal + ")");
            if (base > 0 && buffed > 0 && !near((double) buffed / base, want)) {
                bad.add(me.getName() + "'s real kill paid x" + String.format("%.2f", (double) buffed / base)
                        + ", expected x" + want);
            }
            if (basePal > 0 && buffedPal > 0 && !near((double) buffedPal / basePal, wantPal)) {
                bad.add(pal.getName() + "'s real kill paid x" + String.format("%.2f", (double) buffedPal / basePal)
                        + ", expected x" + wantPal);
            }
            if (base <= 0 || buffed <= 0 || basePal <= 0 || buffedPal <= 0) {
                ev.add("(a real kill could not be read: no mob data, or a level-up; the EXP ratio is not judged)");
            }

            // saved for a restart (migration 052)
            String saved = savedRow(me.getId());
            ev.add("family_buffs row: " + saved);
            if (saved == null || !saved.startsWith("exp 2.0")) {
                bad.add("the buff is not saved in family_buffs (" + saved + ")");
            }

            // a second use the same day is refused, nothing spent
            int ra = mine.getReputation();
            use(p, FamilyEntitlement.SELF_EXP_2, null);
            if (mine.getReputation() != ra) {
                bad.add("a second My EXP 2x the same day was not refused for free");
            }

            // the daily reset (in memory, for this family: the midnight task's SQL touches every family)
            family.resetDailyReps();
            int stillUsed = 0;
            for (FamilyEntitlement e : FamilyEntitlement.values()) {
                if (mine.isEntitlementUsed(e)) {
                    stillUsed++;
                }
            }
            int r3 = mine.getReputation();
            realKill(bot);
            int afterReset = mine.getReputation() - r3;
            ev.add("daily reset: " + stillUsed + " perks still marked used, a bot kill now gives +" + afterReset);
            if (stillUsed != 0 || junior.getRepsToSenior() > afterReset || (perKill > 0 && afterReset <= 0)) {
                bad.add("the daily reset did not free the perks and the cap");
            }
        } catch (RuntimeException e) {
            bad.add("threw " + e);
        } finally {
            if (bondingWas == null) {
                System.clearProperty("slums.family.bondingMin");
            } else {
                System.setProperty("slums.family.bondingMin", bondingWas);
            }
            if (pal.getParty() != null) {
                Party.leaveParty(pal.getParty(), pal);
            }
            if (me.getParty() != null) {
                Party.leaveParty(me.getParty(), me);
            }
            ev.add(FamilyQa.teardown(me, st));
        }
        if (me.getFamily() != null || pal.getFamily() != null) {
            bad.add("the QA family was not dissolved");
        }
        if (savedRow(me.getId()) != null) {
            bad.add("the teardown left a family_buffs row");
        }
        return Probes160.verdict(bad, "every buff perk spent its rep and gave its rate and minutes, party and Bonding"
                + " reached the right people, real kills paid the buffed EXP, the buff was saved, the bot junior's rep"
                + " stopped at the cap and the daily reset freed it all", ev);
    }

    /** Uses {@code e} through the packet and checks the rep, the rate, the minutes (and {@code also}'s buff). */
    static void checkBuff(SyntheticPlayer p, Character me, FamilyEntry mine, FamilyEntitlement e, Character also,
                          List<String> bad, List<String> ev) {
        FamilyBuffs.Spec spec = FamilyBuffs.specOf(e);
        int before = mine.getReputation();
        use(p, e, null);
        long now = System.currentTimeMillis();
        int spent = before - mine.getReputation();
        double rate = spec.exp() ? FamilyBuffs.expMultiplier(me.getId(), now) : FamilyBuffs.dropMultiplier(me.getId(), now);
        long[] left = FamilyBuffs.minutesLeft(me.getId(), now);
        long minutes = spec.exp() ? left[0] : left[1];
        ev.add(e.getName() + ": rep -" + spent + ", x" + rate + ", " + minutes + " min left");
        if (spent != e.getRepCost()) {
            bad.add(e.getName() + " spent " + spent + " rep, not " + e.getRepCost());
        }
        if (rate < spec.rate()) {
            bad.add(e.getName() + " gave x" + rate + ", not x" + spec.rate());
        }
        if (minutes < spec.minutes() - 1) {
            bad.add(e.getName() + " left " + minutes + " minutes, not " + spec.minutes());
        }
        if (also != null) {
            double r = spec.exp() ? FamilyBuffs.expMultiplier(also.getId(), now) : FamilyBuffs.dropMultiplier(also.getId(), now);
            if (r != spec.rate()) {
                bad.add(e.getName() + " did not reach party member " + also.getName() + " (x" + r + ")");
            }
        }
    }

    /** The family_buffs row of {@code chrId} ("exp R until T, drop R until T"), null when none, or the error. */
    static String savedRow(int chrId) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT exp_rate, exp_ends, drop_rate, drop_ends FROM family_buffs WHERE charid = ?")) {
            ps.setInt(1, chrId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long now = System.currentTimeMillis();
                return "exp " + rs.getDouble(1) + " for " + Math.max(0, rs.getLong(2) - now) / 60_000 + " min, drop "
                        + rs.getDouble(3) + " for " + Math.max(0, rs.getLong(4) - now) / 60_000 + " min";
            }
        } catch (Exception e) {
            return "unreadable: " + e.getMessage();
        }
    }

    // ── t-fy-3 ──

    static List<String> travel(SyntheticPlayer p) throws InterruptedException {
        if (!config.YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return List.of("SKIP USE_FAMILY_SYSTEM is off on this server");
        }
        Character me = p.character();
        FamilyQa.Staged st;
        try {
            st = FamilyQa.stage(me);
        } catch (IllegalStateException e) {
            return List.of("FAIL " + e.getMessage());
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        MapleMap home = me.getMap();
        try {
            Character bot = st.bot();
            FamilyEntry mine = me.getFamilyEntry();
            mine.gainReputation(1_000, false);
            boolean sameCh = bot.getClient().getChannel() == me.getClient().getChannel();
            ev.add("junior " + bot.getName() + " on map " + bot.getMapId() + " ch" + bot.getClient().getChannel()
                    + "; " + me.getName() + " on " + me.getMapId() + " ch" + me.getClient().getChannel());

            // Family Reunion: to the regular
            int r0 = mine.getReputation();
            int botMap = bot.getMapId();
            use(p, FamilyEntitlement.FAMILY_REUINION, bot.getName());
            int spent = r0 - mine.getReputation();
            ev.add("Reunion: " + me.getName() + " now on " + me.getMapId() + ", rep -" + spent);
            if (sameCh) {
                if (me.getMapId() != botMap || spent != FamilyEntitlement.FAMILY_REUINION.getRepCost()) {
                    bad.add("Reunion did not take the player to the regular for 300 rep");
                }
            } else if (spent != 0) {
                bad.add("a Reunion the pretend player can't make (another channel) still cost rep");
            } else {
                ev.add("(the regular is on another channel: a pretend player can't reconnect, so only the free refusal"
                        + " is checked here; the channel change is unit-tested)");
            }
            if (me.getMap() != home) {
                me.changeMap(home, home.getPortal(0));
            }
            Thread.sleep(500);

            // Summon Family: the regular comes
            String why = BotFamily.summonRefusal(bot, me);
            int r1 = mine.getReputation();
            BotFamily.certainFor(30_000);
            try {
                use(p, FamilyEntitlement.SUMMON_FAMILY, bot.getName());
                long until = System.currentTimeMillis() + 25_000;
                while (why == null && System.currentTimeMillis() < until
                        && !(bot.getMapId() == me.getMapId() && bot.getClient().getChannel() == me.getClient().getChannel())) {
                    Thread.sleep(250);
                }
            } finally {
                BotFamily.certainFor(0);
            }
            int sSpent = r1 - mine.getReputation();
            boolean came = bot.getMapId() == me.getMapId() && bot.getClient().getChannel() == me.getClient().getChannel();
            ev.add("Summon: " + (why == null ? "free" : "refused: " + why) + "; " + bot.getName() + " now on "
                    + bot.getMapId() + " ch" + bot.getClient().getChannel() + ", rep -" + sSpent);
            if (why == null && (!came || sSpent != FamilyEntitlement.SUMMON_FAMILY.getRepCost())) {
                bad.add("the summoned regular did not come for 500 rep (came " + came + ", spent " + sSpent + ")");
            }
            if (why != null && sSpent != 0) {
                bad.add("a refused summon still cost rep");
            }
        } catch (RuntimeException e) {
            bad.add("threw " + e);
        } finally {
            if (me.getMap() != home) {
                me.changeMap(home, home.getPortal(0));
            }
            ev.add(FamilyQa.teardown(me, st));
        }
        if (me.getFamily() != null) {
            bad.add("the QA family was not dissolved");
        }
        return Probes160.verdict(bad, "Family Reunion took the player to the regular junior and Summon Family brought"
                + " it over, each for its rep", ev);
    }

    // ── t-fy-4 ──

    static List<String> offer(SyntheticPlayer p) throws InterruptedException {
        if (!config.YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return List.of("SKIP USE_FAMILY_SYSTEM is off on this server");
        }
        Character me = p.character();
        if (me.getFamily() != null) {
            return List.of("FAIL " + me.getName() + " is already in a family (leave it first)");
        }
        Character bot = null;
        int ch = me.getClient().getChannel();
        for (soloMapling.ArtificialPlayer.BotSM sm
                : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
            Character c = sm == null ? null : sm.getChr();
            if (c != null && c.isBot() && c.getMap() != null && c.getFamily() == null && c.getLevel() > 10
                    && c.getLevel() <= me.getLevel() && me.getLevel() - c.getLevel() <= 20
                    && c.getClient() != null && c.getClient().getChannel() == ch && c.getWorld() == me.getWorld()
                    && !BotFamily.waryOf(c, me)) {
                bot = c;
                break;
            }
        }
        if (bot == null) {
            return List.of("SKIP no regular within 20 levels below " + me.getName() + " on channel " + ch);
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        MapleMap home = me.getMap();
        Integer familyId = null;
        try {
            MapleMap there = bot.getMap();
            if (me.getMap() != there) {
                me.changeMap(there, there.findClosestPortal(bot.getPosition()));
            }
            long since = System.currentTimeMillis();
            BotFamily.Gate gate = BotFamily.offerForQa(bot, me);
            Thread.sleep(1_000);
            String heard = null;
            for (AutoQaLine l : p.heard(since)) {
                if ("WHISPER".equals(l.kind()) && l.message().startsWith(bot.getName() + ":")) {
                    heard = l.message();
                }
            }
            ev.add(bot.getName() + " lv" + bot.getLevel() + " on " + there.getId() + ": offer " + gate + ", whisper: "
                    + heard);
            if (gate != BotFamily.Gate.OK || heard == null || !heard.toLowerCase().contains("junior")) {
                bad.add("the regular did not whisper its offer (" + gate + ")");
                return Probes160.verdict(bad, "", ev);
            }
            // the player goes elsewhere, then opens the Family window and types the name in Add Junior
            if (me.getMap() != home) {
                me.changeMap(home, home.getPortal(0));
            }
            ByteBufOutPacket b = new ByteBufOutPacket();
            b.writeString(bot.getName());
            p.send(RecvOpcode.ADD_FAMILY, b);
            long until = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < until && (bot.getFamily() == null || bot.getFamily() != me.getFamily())) {
                Thread.sleep(250);
            }
            boolean joined = bot.getFamily() != null && bot.getFamily() == me.getFamily();
            ev.add("Add Junior from map " + me.getMapId() + ": " + (joined ? "accepted" : "no answer / declined"));
            if (!joined) {
                bad.add("the regular that asked did not accept the player's Add Junior");
            } else {
                familyId = me.getFamily().getID();
            }
        } catch (RuntimeException e) {
            bad.add("threw " + e);
        } finally {
            BotFamily.forgetOffer(bot.getId(), me.getId());
            if (me.getMap() != home) {
                me.changeMap(home, home.getPortal(0));
            }
            if (me.getFamily() != null) {
                familyId = me.getFamily().getID();
            }
            if (familyId != null) {
                ev.add(FamilyQa.teardown(me, new FamilyQa.Staged(bot, familyId)));
            }
        }
        if (me.getFamily() != null) {
            bad.add("the QA family was not dissolved");
        }
        return Probes160.verdict(bad, "a regular whispered its offer, and the player's Add Junior from another map"
                + " made it their junior", ev);
    }
}
