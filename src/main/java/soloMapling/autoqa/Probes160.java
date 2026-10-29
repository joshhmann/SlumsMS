package soloMapling.autoqa;

import client.Character;
import client.Client;
import client.Job;
import client.Stat;
import client.SyntheticClient;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import net.packet.ByteBufOutPacket;
import net.opcodes.RecvOpcode;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import scripting.npc.NPCScriptManager;
import server.ItemInformationProvider;
import server.checkin.DailyCheckin;
import server.checkin.DailyCheckinRewards;
import server.checkin.DailyCheckinStreak;
import server.quest.Quest;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns;
import soloMapling.ArtificialPlayer.BotLifeSystem.TownPulse;
import soloMapling.ArtificialPlayer.BotConvo.DecideShadow;
import soloMapling.companions.OwnedAltService;
import soloMapling.fishing.FishingPolicy;
import soloMapling.fishing.StarterChairs;
import soloMapling.site.auth.SiteAuthQa;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.0 auto-QA probes for the features that shipped without a check (owner 2026-10-27, "Done means switched on and
 * noted"): companions (t-oa-1), busy towns (t-tw-1), 4-byte HP/MP (t-hp-1), the site auth API (t-au-1), the decide
 * shadow (t-ds-1), the custom NPC caps (t-np-4), Daily Check-In (t-ck-1) and the starter chairs (t-sc-3).
 * <p>
 * Each probe stages what it needs on the pretend players' own accounts, judges, and undoes its staging in a
 * {@code finally}, because the runner's cleanup only sends {@code !qa stop}. Commands go through the GM's chat
 * ({@code @botme}, {@code !setmaxhp}, {@code !invite}, {@code !resetpw}), so a FAIL also carries the server's answers
 * ({@link AutoQaRunner#GM_ANSWERS_ON_FAIL}). The first line says PASS / FAIL / SKIP, the rest is evidence. The verdict
 * rules are static and pure, for the tests.
 */
final class Probes160 {

    /** Every probe name here, for the catalog test. */
    static final List<String> NAMES = List.of("ownedAlts", "townSample", "townVerdict", "townDressing", "hpMp4Byte",
            "authFlow", "decideShadow", "customNpcCaps", "dailyCheckin", "starterChairs", "slumsStamps", "nxExchange",
            "family", "tutorialIsland", "botRebirth", "botRebirthBoss", "achievements", "newerAreaCards", "leafExchange", "playerInvites", "siteInvites", "slumsChan", "marketVolatility", "marketWar", "botTalk", "botCrew", "botSayDo", "bugReport", "achievementClear", "lazyPenguins", "trainingLadder", "familyPerks", "familyTravel", "familyOffer");

    /** t-oa-1: the QA account's two alts (made once, kept: the second one is for the cap). */
    static final String ALT_A = "AutoQaAlt", ALT_B = "AutoQaAltB";
    /** t-oa-1: companion ids that are never characters, reserved to fill the cap and always released. */
    static final int[] FAKE_COMPANIONS = {-900_001, -900_002, -900_003, -900_004};
    /** t-oa-1 / t-sc-3: character slots the QA account needs (AutoQaOne, two alts, a throwaway, vb-2's). */
    static final int QA_SLOTS = 8;
    /** t-tw-1: the fill (bots / target, capped per map) a town index counts as at its target. */
    static final double TOWN_FULL = 0.9, TOWN_HOLD = 0.6;

    private Probes160() {
    }

    /** The probe's lines, or null when {@code what} is not one of these. */
    static List<String> probe(String what, SyntheticPlayer p, String... a) {
        try {
            return switch (what) {
                case "ownedAlts" -> ownedAlts(p);
                case "townSample" -> townSample();
                case "townVerdict" -> townVerdictProbe();
                case "townDressing" -> townDressing();
                case "hpMp4Byte" -> hpMp4Byte(p);
                case "authFlow" -> authFlow(p);
                case "decideShadow" -> DecideShadow.selfCheck(AutoQa.dataDir().resolve("decide-shadow-selfcheck"));
                case "customNpcCaps" -> customNpcCaps(p);
                case "dailyCheckin" -> dailyCheckin(p);
                case "starterChairs" -> starterChairs(p);
                case "slumsStamps" -> slumsStamps(p);
                case "nxExchange" -> nxExchange(p);
                case "leafExchange" -> leafExchange(p);
                case "marketVolatility" -> soloMapling.Economy.exchange.volatility.MarketQa.check(System.currentTimeMillis());
                case "marketWar" -> soloMapling.Economy.exchange.volatility.MarketQa.warCheck(System.currentTimeMillis());
                case "family" -> family(p);
                case "familyPerks" -> FamilyProbes.perks(p);
                case "familyTravel" -> FamilyProbes.travel(p);
                case "familyOffer" -> FamilyProbes.offer(p);
                case "tutorialIsland" -> tutorialIsland(p);
                case "botRebirth" -> botRebirth(p);
                case "botRebirthBoss" -> botRebirthBoss(p);
                case "achievements" -> achievements(p);
                case "achievementClear" -> achievementClear(p);
                case "lazyPenguins" -> lazyPenguins(p);
                case "newerAreaCards" -> newerAreaCards();
                case "trainingLadder" -> trainingLadder();
                case "playerInvites" -> playerInvites();
                case "siteInvites" -> siteInvites();
                case "slumsChan" -> slumsChan(p);
                case "botTalk" -> botTalk(p);
                case "botCrew" -> botCrew(p);
                case "botSayDo" -> botSayDo(p);
                case "bugReport" -> bugReport(p);
                default -> null;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("SKIP interrupted");
        } catch (Exception e) {
            return List.of("FAIL probe " + what + " threw " + e);
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────────

    /** Says a line (a command) and waits up to {@code maxMs} for a YELLOW answer matching {@code want}; null if none. */
    static String sayAndHear(SyntheticPlayer p, String line, String want, long maxMs) throws InterruptedException {
        long since = System.currentTimeMillis();
        List<AutoQaLine> before = p.heard(since); // 1.6.0 release day: the last command's answer in this same ms
        p.say(line);
        Pattern pat = Pattern.compile(want);
        long end = since + maxMs;
        while (true) {
            String got = firstNewAnswer(before, p.heard(since), pat);
            if (got != null) {
                return got;
            }
            if (System.currentTimeMillis() >= end) {
                return null;
            }
            Thread.sleep(200);
        }
    }

    /**
     * The first YELLOW answer matching {@code pat} among {@code heard} that was not already there before the command
     * ({@code before}, by identity). TEST 2026-10-27 18:41: t-oa-1 ran in 30 ms, so "@botme AutoQaAlt again" read the
     * first call's "has joined you" (same millisecond) instead of its own "already online".
     */
    static String firstNewAnswer(List<AutoQaLine> before, List<AutoQaLine> heard, Pattern pat) {
        java.util.Set<AutoQaLine> old = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        old.addAll(before);
        for (AutoQaLine l : heard) {
            if (!old.contains(l) && "YELLOW".equals(l.kind()) && pat.matcher(l.message()).find()) {
                return l.message();
            }
        }
        return null;
    }

    /** The first line: PASS with {@code passText} when nothing is wrong, else FAIL with every problem. */
    static List<String> verdict(List<String> bad, String passText, List<String> evidence) {
        List<String> out = new ArrayList<>();
        out.add(bad.isEmpty() ? "PASS " + passText : "FAIL " + String.join("; ", bad));
        out.addAll(evidence);
        return out;
    }

    /**
     * The pretend player's own account gets {@link #QA_SLOTS} character slots (in the database too: deleting a
     * character reloads the slots from the account row, and vb-2 makes and deletes one after the alts exist).
     */
    private static void roomForCharacters(Character gm) throws Exception {
        if (gm.getClient().getCharacterSlots() < QA_SLOTS) {
            gm.getClient().setCharacterSlots((byte) QA_SLOTS);
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET characterslots = ? WHERE id = ? AND characterslots < ?")) {
            ps.setInt(1, QA_SLOTS);
            ps.setInt(2, gm.getAccountID());
            ps.setInt(3, QA_SLOTS);
            ps.executeUpdate();
        }
    }

    private static Character online(int characterId) {
        for (World w : Server.getInstance().getWorlds()) {
            Character c = w.getPlayerStorage().getCharacterById(characterId);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static long dbMeso(int characterId) throws Exception {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT meso FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    /** A beginner on the GM's account with this name, made now if missing; its id, or -1 (and why in {@code out}). */
    private static int ensureCharacter(Character gm, String name, List<String> out) {
        int id = Character.getIdByName(name);
        if (id >= 0) {
            return id;
        }
        int made = client.creator.novice.BeginnerCreator.createCharacter(gm.getClient(), name, 20000, 30000, 0, 0, 0,
                0, 0, 0);
        id = Character.getIdByName(name);
        out.add(made == 0 ? "made " + name + " (" + id + ") on " + gm.getName() + "'s account"
                : "could not make " + name + " (status " + made + ")");
        return made == 0 ? id : -1;
    }

    // ── t-oa-1: owned alts as companions ──────────────────────────────────────────────────────────

    /**
     * t-oa-1: {@code @botme} lists the alt, {@code @botme <alt>} brings it onto the GM's map and into the GM's party, a
     * second call is refused, four reserved companion places plus the alt make five and the sixth (the second alt) is
     * refused, a change to the alt is saved by {@code @dismiss}, which takes it out of the world and the party.
     */
    static List<String> ownedAlts(SyntheticPlayer p) throws Exception {
        if (!YamlConfig.config.server.OWNED_ALTS_ENABLED) {
            return List.of("SKIP OWNED_ALTS_ENABLED is off on this server (companions are switched off)");
        }
        if (!YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED) {
            return List.of("SKIP OWNED_ALTS_SUMMON_ENABLED is off on this server (@botme summoning is resting)");
        }
        Character gm = p.character();
        OwnedAltService svc = OwnedAltService.getInstance();
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        roomForCharacters(gm);
        int altA = ensureCharacter(gm, ALT_A, out);
        int altB = ensureCharacter(gm, ALT_B, out);
        if (altA < 0 || altB < 0) {
            return verdict(List.of("the QA account has no alts to call"), "", out);
        }
        if (OwnedAltService.CAP != 5) {
            bad.add("the companion cap is " + OwnedAltService.CAP + ", want 5");
        }
        int homeMap = gm.getMapId();
        boolean hadParty = gm.getParty() != null;
        if (hadParty && (gm.getParty().getLeaderId() != gm.getId() || gm.getParty().getMembers().size() >= 6)) {
            gm.leaveParty();   // @botme needs the caller to lead a party with a free place
            hadParty = false;
            out.add("left a party " + gm.getName() + " did not lead");
        }
        List<Integer> reserved = new ArrayList<>();
        try {
            String list = sayAndHear(p, "@botme", "Available characters|No eligible|not available", 8000);
            out.add("@botme: " + list);
            if (list == null || !list.contains(ALT_A)) {
                bad.add("@botme does not list " + ALT_A);
            }
            String joined = sayAndHear(p, "@botme " + ALT_A, "(?i)has joined you|already online|create a party|lead a party"
                    + "|choose another|not available|unable|instance|closing|five", 15000);
            out.add("@botme " + ALT_A + ": " + joined);
            Character alt = online(altA);
            if (alt == null) {
                bad.add(ALT_A + " is not in the world after @botme");
                return verdict(bad, "", out);
            }
            boolean owned = alt.isOwnedAlt() && alt.getOwnedAltOwner() != null && alt.getOwnedAltOwner().getId() == gm.getId();
            boolean sameMap = alt.getMapId() == gm.getMapId();
            boolean inParty = gm.getParty() != null && gm.getParty().getMemberById(altA) != null;
            out.add(ALT_A + ": owned by " + gm.getName() + "=" + owned + ", map " + alt.getMapId() + " (owner "
                    + gm.getMapId() + "), in the owner's party=" + inParty);
            if (!owned) {
                bad.add(ALT_A + " is not marked as " + gm.getName() + "'s alt");
            }
            if (!sameMap) {
                bad.add(ALT_A + " joined on map " + alt.getMapId() + ", not the owner's " + gm.getMapId());
            }
            if (!inParty) {
                bad.add(ALT_A + " is not in " + gm.getName() + "'s party");
            }
            // 1.6.0.4: the alt follows its owner, and through a portal when the owner changes map
            boolean following = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isFollowing(alt)
                    && soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.followTarget(alt) == gm;
            out.add(ALT_A + " follows " + gm.getName() + "=" + following);
            if (!following) {
                bad.add(ALT_A + " is not following " + gm.getName() + " after @botme");
            } else {
                String portal = followThroughPortal(gm, alt, out);
                if (portal != null) {
                    bad.add(portal);
                }
            }
            // 1.6.1 ticket 13 (full alts): the core promise, not just the setup - a mob hits the alt, the alt fights
            // what its owner hits and damages it, loots a party drop into its own bag, and stops when its owner idles
            bad.addAll(altFightsLootsAndStops(p, gm, alt, out));
            String dup = sayAndHear(p, "@botme " + ALT_A, "(?i)already online|has joined you", 8000);
            out.add("@botme " + ALT_A + " again: " + dup);
            if (dup == null || !dup.contains("already online")) {
                bad.add("a second @botme " + ALT_A + " was not refused");
            }

            // the cap: the alt and up to four reserved places fill five (a friend bot already following the GM takes
            // a place too, so the reservations stop at the first refusal); a sixth is refused, by the service and @botme
            for (int id : FAKE_COMPANIONS) {
                if (!svc.reserveCompanion(gm.getId(), id)) {
                    break;
                }
                reserved.add(id);
            }
            boolean room = svc.hasCompanionRoom(gm.getId());
            boolean sixth = svc.reserveCompanion(gm.getId(), -900_005);
            if (sixth) {
                reserved.add(-900_005);
            }
            String capped = sayAndHear(p, "@botme " + ALT_B, "(?i)up to five|has joined you|already online", 10000);
            out.add("cap: reserved " + reserved.size() + " + " + ALT_A + ", room left=" + room + ", sixth place=" + sixth
                    + ", @botme " + ALT_B + ": " + capped);
            if (room || sixth) {
                bad.add("the cap of 5 did not hold (reserved " + reserved.size() + ", room " + room + ", sixth " + sixth + ")");
            }
            if (capped == null || !capped.toLowerCase(Locale.ROOT).contains("up to five")) {
                bad.add("@botme " + ALT_B + " past the cap was not refused with the five-companion line");
            }
            for (int id : reserved) {
                svc.releaseCompanion(id);
            }
            reserved.clear();
            if (online(altB) != null) {
                svc.dismiss(gm, ALT_B);
            }

            // the alt's state saves: a marker amount of mesos, then @dismiss
            int marker = 100_000 + (int) (System.currentTimeMillis() / 1000 % 800_000);
            alt.gainMeso(marker - alt.getMeso(), false);
            String dismissed = sayAndHear(p, "@dismiss " + ALT_A, "(?i)saved and dismissed|not with you|unable", 15000);
            out.add("@dismiss " + ALT_A + ": " + dismissed);
            long saved = dbMeso(altA);
            boolean gone = online(altA) == null;
            boolean leftParty = gm.getParty() == null || gm.getParty().getMemberById(altA) == null;
            out.add("after @dismiss: in the world=" + !gone + ", in the party=" + !leftParty + ", saved mesos " + saved
                    + " (set " + marker + ")");
            if (!gone) {
                bad.add(ALT_A + " is still in the world after @dismiss");
            }
            if (!leftParty) {
                bad.add(ALT_A + " is still in the party after @dismiss");
            }
            if (saved != marker) {
                bad.add(ALT_A + "'s mesos were not saved (database " + saved + ", want " + marker + ")");
            }
            if (soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isEnabled(alt)) {
                bad.add(ALT_A + " still has a movement driver after @dismiss");
            }
        } finally {
            for (int id : reserved) {
                svc.releaseCompanion(id);
            }
            try {
                svc.dismissAll(gm, false);
            } catch (RuntimeException e) {
                bad.add("cleanup: dismissing the alts failed: " + e.getMessage());
            }
            if (!hadParty && gm.getParty() != null) {
                gm.leaveParty();
            }
            if (gm.getMapId() != homeMap) {
                out.add("owner moved from " + homeMap + " to " + gm.getMapId() + "; back home");
                gm.changeMap(homeMap);
            }
        }
        return verdict(bad, ALT_A + " joins the party on the owner's map and follows, through a portal too; on a field a"
                + " mob hits it, it damages the mob its owner hits, loots a party drop into its own bag and stops while its"
                + " owner idles; a repeat is refused, the cap of 5 holds, @dismiss saves and removes it", out);
    }

    /** t-oa-1 (1.6.1): the field the fight checks run on (Henesys Hunting Ground I: a field, not a town). */
    static final int ALT_FIELD = 104_040_000;
    /** A Blue Snail: weak, and slow enough to land a touch before the alt kills it. */
    static final int ALT_MOB = 100_101;
    /** Snail Shell: the staged party drop the alt must pick up. */
    static final int ALT_LOOT = 4_000_019;
    /** A beginner's Sword, given to the QA alt once if it holds no weapon (an alt without one cannot fight). */
    static final int ALT_SWORD = 1_302_000;

    /**
     * 1.6.1 ticket 13, t-oa-1: on {@link #ALT_FIELD} with its owner, (1) a mob spawned on the alt hits it (HP or the
     * driver's hit count), (2) a mob the owner hits is attacked and damaged by the alt through the bots' attack system,
     * (3) a party drop beside it ends up in the alt's own bag, (4) with its owner idle (the gate's QA switch) it does not
     * swing at a mob its owner hits, and swings again once its owner is active. Failures, evidence in {@code out}.
     */
    static List<String> altFightsLootsAndStops(SyntheticPlayer p, Character gm, Character alt, List<String> out)
            throws InterruptedException {
        List<String> bad = new ArrayList<>();
        server.maps.MapleMap field = gm.getClient().getChannelServer().getMapFactory().getMap(ALT_FIELD);
        if (field == null || field.isTown()) {
            out.add("fight checks: map " + ALT_FIELD + " missing or a town - not judged");
            return bad;
        }
        gm.changeMap(field, field.getRandomPlayerSpawnpoint());
        try {
            OwnedAltService.getInstance().bringHere(gm);
        } catch (IllegalStateException e) {
            bad.add("@botme here onto the field failed: " + e.getMessage());
            return bad;
        }
        if (alt.getMap() != gm.getMap()) {
            bad.add(ALT_A + " is not on the field " + ALT_FIELD + " with its owner");
            return bad;
        }
        if (alt.getInventory(InventoryType.EQUIPPED).getItem((short) -11) == null) {
            Item sword = ItemInformationProvider.getInstance().getEquipById(ALT_SWORD);
            sword.setPosition((short) -11);
            alt.getInventory(InventoryType.EQUIPPED).addItemFromDB(sword);
            out.add("staging: gave " + ALT_A + " a Sword (" + ALT_SWORD + "), it held no weapon");
        }
        alt.updateHp(alt.getCurrentMaxHp());
        List<server.life.Monster> staged = new ArrayList<>();
        try {
            // (1) a mob damages the alt
            ownerActs(p);
            long hitsBefore = soloMapling.companions.OwnedAltCompanion.stats(alt.getId()).hitsTaken();
            int hpBefore = alt.getHp();
            boolean hurt = false;
            long start = System.currentTimeMillis();
            while (!hurt && System.currentTimeMillis() - start < 25_000) {
                server.life.Monster m = server.life.LifeFactory.getMonster(ALT_MOB);
                field.spawnMonsterOnGroundBelow(m, new java.awt.Point(alt.getPosition()));
                staged.add(m);
                long until = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < until && !hurt) {
                    Thread.sleep(250);
                    hurt = soloMapling.companions.OwnedAltCompanion.stats(alt.getId()).hitsTaken() > hitsBefore
                            || alt.getHp() < hpBefore;
                }
            }
            var st = soloMapling.companions.OwnedAltCompanion.stats(alt.getId());
            out.add("mob on the alt: hits taken " + (st.hitsTaken() - hitsBefore) + ", damage " + st.damageTaken()
                    + ", HP " + hpBefore + " -> " + alt.getHp());
            if (!hurt) {
                bad.add("no mob damaged " + ALT_A + " in 25 s (mobs spawned on it: " + staged.size() + ")");
            }

            // (2) the alt attacks the mob its owner hits, and damages it
            ownerActs(p);
            long swingsBefore = soloMapling.companions.OwnedAltCompanion.stats(alt.getId()).swings();
            boolean struck = altDamagesOwnersMob(field, gm, alt, staged, 80, 20_000);
            st = soloMapling.companions.OwnedAltCompanion.stats(alt.getId());
            out.add("owner hit a mob: " + ALT_A + " damaged it=" + struck + ", swings " + (st.swings() - swingsBefore)
                    + ", gate " + st.gate());
            if (!struck) {
                bad.add(ALT_A + " did not damage the mob its owner hit within 20 s (gate " + st.gate() + ")");
            }

            // (3) a party drop beside the alt goes into its own bag
            ownerActs(p);
            int shellsBefore = alt.getInventory(InventoryType.ETC).countById(ALT_LOOT);
            long lootsBefore = soloMapling.companions.OwnedAltCompanion.stats(alt.getId()).loots();
            field.spawnItemDrop(gm, gm, new Item(ALT_LOOT, (short) 0, (short) 1),
                    new java.awt.Point(alt.getPosition().x + 30, alt.getPosition().y), (byte) 1, false);
            boolean looted = false;
            start = System.currentTimeMillis();
            while (!looted && System.currentTimeMillis() - start < 15_000) {
                Thread.sleep(250);
                looted = alt.getInventory(InventoryType.ETC).countById(ALT_LOOT) > shellsBefore;
            }
            out.add("party drop: " + ALT_A + " Snail Shells " + shellsBefore + " -> "
                    + alt.getInventory(InventoryType.ETC).countById(ALT_LOOT) + ", pickups "
                    + (soloMapling.companions.OwnedAltCompanion.stats(alt.getId()).loots() - lootsBefore));
            if (!looted) {
                bad.add(ALT_A + " did not pick up the party drop into its own bag within 15 s");
            }

            // (4) the activity gate: its owner idle, it does not fight; active again, it does
            server.life.Monster idleMob = server.life.LifeFactory.getMonster(ALT_MOB);
            field.spawnMonsterOnGroundBelow(idleMob, new java.awt.Point(gm.getPosition().x - 80, gm.getPosition().y));
            staged.add(idleMob);
            soloMapling.companions.OwnedAltGate.forceIdleForQa(gm.getId(), true);
            try {
                Thread.sleep(600); // a driver tick sees the idle owner and stands down
                long idleSwings = soloMapling.companions.OwnedAltCompanion.stats(alt.getId()).swings();
                field.damageMonster(gm, idleMob, 1);
                Thread.sleep(5_000);
                st = soloMapling.companions.OwnedAltCompanion.stats(alt.getId());
                Long onIdleMob = idleMob.damageSnapshot().get(alt.getId());
                out.add("owner idle: gate " + st.gate() + ", swings in 5 s " + (st.swings() - idleSwings)
                        + ", damage on the owner's mob " + (onIdleMob == null ? 0 : onIdleMob));
                if (!"OWNER_IDLE".equals(st.gate())) {
                    bad.add("the gate did not close for an idle owner (gate " + st.gate() + ")");
                }
                if (st.swings() > idleSwings || onIdleMob != null && onIdleMob > 0) {
                    bad.add(ALT_A + " kept fighting while its owner was idle");
                }
            } finally {
                soloMapling.companions.OwnedAltGate.forceIdleForQa(gm.getId(), false);
            }
            ownerActs(p);
            boolean resumed = altDamagesOwnersMob(field, gm, alt, staged, -80, 20_000);
            out.add("owner active again: " + ALT_A + " fights again=" + resumed);
            if (!resumed) {
                bad.add(ALT_A + " did not fight again once its owner was active");
            }
        } finally {
            for (server.life.Monster m : staged) {
                if (m.isAlive() && field.getMonsterByOid(m.getObjectId()) != null) {
                    field.killMonster(m, gm, false);
                }
            }
        }
        return bad;
    }

    /**
     * t-oa-1: a mob is spawned {@code dx} px beside the owner and the owner hits it; true once the alt has damaged it
     * within {@code maxMs}. A population bot may kill the staged mob first: then another one is staged.
     */
    private static boolean altDamagesOwnersMob(server.maps.MapleMap field, Character gm, Character alt,
                                               List<server.life.Monster> staged, int dx, long maxMs)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        server.life.Monster m = null;
        while (System.currentTimeMillis() - start < maxMs) {
            if (m == null || !m.isAlive()) {
                if (m != null && m.damageSnapshot().getOrDefault(alt.getId(), 0L) > 0) {
                    return true;
                }
                m = server.life.LifeFactory.getMonster(ALT_MOB);
                field.spawnMonsterOnGroundBelow(m, new java.awt.Point(gm.getPosition().x + dx, gm.getPosition().y));
                staged.add(m);
                field.damageMonster(gm, m, 1);
            }
            Thread.sleep(250);
            if (m.damageSnapshot().getOrDefault(alt.getId(), 0L) > 0) {
                return true;
            }
        }
        return false;
    }

    /** The pretend owner "moves": its last player action is now (the gate's idle rule reads it). */
    private static void ownerActs(SyntheticPlayer p) {
        SyntheticPlayers.noteReceived(p.client(), RecvOpcode.MOVE_PLAYER);
    }

    /**
     * 1.6.0.4 t-oa-1: the owner steps to a neighbouring map (one walkable portal away) and the alt must walk through a
     * portal after them within 90 s. Null when it did (or no such neighbour exists here: not judged), else the failure.
     */
    static String followThroughPortal(Character gm, Character alt, List<String> out) throws InterruptedException {
        server.maps.MapleMap home = gm.getMap();
        server.maps.Portal via = null;
        for (server.maps.Portal pt : home.getPortals()) {
            int to = pt.getTargetMapId();
            if (to == 999_999_999 || to == home.getId() || to <= 0) {
                continue;
            }
            List<Integer> r = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.route(alt, to);
            if (r != null && r.size() == 1 && soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.walkable(alt, to)) {
                via = pt;
                break;
            }
        }
        if (via == null) {
            out.add("follow through a portal: no walkable portal out of map " + home.getId() + " - not judged");
            return null;
        }
        server.maps.MapleMap next = gm.getClient().getChannelServer().getMapFactory().getMap(via.getTargetMapId());
        server.maps.Portal arrival = next.getPortal(via.getTarget());
        if (arrival != null) {
            gm.changeMap(next, arrival);
        } else {
            gm.changeMap(next);
        }
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 90_000 && alt.getMapId() != next.getId()) {
            Thread.sleep(500);
        }
        long took = System.currentTimeMillis() - start;
        boolean arrived = alt.getMapId() == next.getId();
        out.add("owner stepped " + home.getId() + " -> " + next.getId() + "; " + ALT_A + " " + (arrived
                ? "followed through the portal in " + took + " ms" : "is still on map " + alt.getMapId() + " after 90 s"));
        return arrived ? null : ALT_A + " did not follow its owner through a portal to map " + next.getId() + " in 90 s";
    }

    // ── t-tw-1: busy towns ───────────────────────────────────────────────────────────────────────

    /** One look at the towns: the index's target and filled sums, town bots per channel, map instances over the max. */
    record TownSample(long at, int targetSum, int filled, int[] perChannel, List<String> over, int max) {
        double fill() {
            return targetSum <= 0 ? 0 : filled / (double) targetSum;
        }
    }

    private static volatile TownSample firstTownSample;

    /** Null when the brain and the town pull are on; else why the check cannot run. */
    static String townsOff() {
        if (!LifeRuntime.enabled()) {
            return "the bot brain is off (-Dslums.botlife.enabled=true and -Dslums.botlife.execute=true)";
        }
        if (!TownPulse.on()) {
            return "BOT_TOWN_PULL is 0 (busy towns are off)";
        }
        return null;
    }

    static TownSample sampleTowns() {
        int targetSum = 0, filled = 0;
        for (List<TownPulse.Slot> slots : TownPulse.index().values()) {
            for (TownPulse.Slot s : slots) {
                targetSum += s.target();
                filled += Math.min(s.bots(), s.target());
            }
        }
        int max = YamlConfig.config.server.BOT_TOWN_MAP_MAX;
        List<Channel> chs = BotChannels.channels();
        int[] per = new int[chs.size()];
        List<String> over = new ArrayList<>();
        for (int i = 0; i < chs.size(); i++) {
            var factory = chs.get(i).getMapFactory();
            for (LifeTowns.Town town : LifeTowns.TOWNS) {
                for (int mapId : town.family()) {
                    if (!factory.isMapLoaded(mapId)) {
                        continue;
                    }
                    int bots = factory.getMap(mapId).getBotCount();
                    per[i] += bots;
                    if (bots > max) {
                        over.add("ch" + chs.get(i).getId() + " " + mapId + " (" + town.name() + ") " + bots);
                    }
                }
            }
        }
        return new TownSample(System.currentTimeMillis(), targetSum, filled, per, over, max);
    }

    static String describe(TownSample s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.perChannel().length; i++) {
            sb.append(" ch").append(i + 1).append('=').append(s.perChannel()[i]);
        }
        return String.format(Locale.ROOT, "towns: %d of %d target bots in place (%.0f%%), town bots per channel:%s",
                s.filled(), s.targetSum(), s.fill() * 100, sb);
    }

    private static List<String> townSample() {
        String off = townsOff();
        if (off != null) {
            return List.of("SKIP " + off);
        }
        TownSample s = sampleTowns();
        firstTownSample = s;
        return List.of("PASS first sample: " + describe(s));
    }

    /**
     * t-tw-1's verdict over two samples a window apart: null when fine, else what is wrong. The towns move toward their
     * targets (fill at least {@link #TOWN_FULL}, or up two points, or holding at {@link #TOWN_HOLD}+ and not down more
     * than five); no town map instance holds more than BOT_TOWN_MAP_MAX bots; channel 1 has the most town bots.
     */
    static String townVerdict(TownSample a, TownSample b) {
        List<String> bad = new ArrayList<>();
        if (b.targetSum() <= 0) {
            bad.add("the town index has no targets (the director is not ticking, or every town is out of range)");
        } else {
            double f0 = a.fill(), f1 = b.fill();
            boolean toward = f1 >= TOWN_FULL || f1 >= f0 + 0.02 || (f1 >= TOWN_HOLD && f1 >= f0 - 0.05);
            if (!toward) {
                bad.add(String.format(Locale.ROOT, "towns are not moving toward their targets: %.0f%% -> %.0f%% in %d s",
                        f0 * 100, f1 * 100, (b.at() - a.at()) / 1000));
            }
        }
        if (!b.over().isEmpty()) {
            bad.add(b.over().size() + " town map(s) over BOT_TOWN_MAP_MAX " + b.max() + ": " + String.join(", ", b.over()));
        }
        int[] per = b.perChannel();
        for (int i = 1; i < per.length; i++) {
            if (per.length > 0 && per[i] > per[0]) {
                bad.add("ch" + (i + 1) + " (" + per[i] + " town bots) is busier than ch1 (" + per[0] + ")");
            }
        }
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    private static List<String> townVerdictProbe() {
        String off = townsOff();
        if (off != null) {
            return List.of("SKIP " + off);
        }
        TownSample a = firstTownSample;
        TownSample b = sampleTowns();
        if (a == null) {
            return List.of("FAIL no first sample (townSample first)", describe(b));
        }
        String problem = townVerdict(a, b);
        List<String> out = new ArrayList<>();
        out.add(problem == null ? String.format(Locale.ROOT, "PASS towns %.0f%% -> %.0f%% of target, no map over %d,"
                + " ch1 the busiest", a.fill() * 100, b.fill() * 100, b.max()) : "FAIL " + problem);
        out.add("first:  " + describe(a));
        out.add("second: " + describe(b));
        out.add("pulse: " + TownPulse.report());
        return out;
    }

    /**
     * t-tw-1: the Henesys town dressing at boot is the scale's share of what it asked for. Each cohort rounds its
     * share by less than one bot, so the total may be off by fewer bots than there were cohorts. Null when fine,
     * "SKIP ..." when nothing was asked for, else what is wrong.
     */
    static String dressingVerdict(int asked, int given, int cohorts, double scale) {
        if (asked <= 0) {
            return "SKIP no Henesys town dressing was spawned at boot (the environment is off)";
        }
        double want = asked * Math.max(0, Math.min(1, scale));
        if (Math.abs(given - want) > Math.max(1, cohorts)) {
            return String.format(Locale.ROOT, "the Henesys dressing spawned %d of %d, want about %.1f (scale %.2f, %d cohorts)",
                    given, asked, want, scale, cohorts);
        }
        return null;
    }

    private static List<String> townDressing() {
        int[] c = BotChannels.townDressingCounts();
        double scale = BotChannels.effectiveTownDressingScale();
        String problem = dressingVerdict(c[0], c[1], c[2], scale);
        String line = String.format(Locale.ROOT, "Henesys dressing: %d of %d spawned in %d cohorts; BOT_TOWN_DRESSING_SCALE %s"
                + " (in effect %.2f)", c[1], c[0], c[2], YamlConfig.config.server.BOT_TOWN_DRESSING_SCALE, scale);
        if (problem != null && problem.startsWith("SKIP")) {
            return List.of(problem, line);
        }
        return List.of(problem == null ? "PASS " + line : "FAIL " + problem, line);
    }

    // ── t-hp-1: 4-byte HP/MP ─────────────────────────────────────────────────────────────────────

    /** The value of {@code key} in a "MAXHP=50000 HP=..." STAT line, or -1. */
    static long statValue(String line, String key) {
        Matcher m = Pattern.compile("(?:^| )" + key + "=(-?\\d+)").matcher(line == null ? "" : line);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /**
     * t-hp-1: {@code !setmaxhp 50000 45000} and the STAT packet the GM's client gets carries MaxHP/MaxMP past 32,767
     * as 4 bytes; {@code !setmaxhp 150000} stops at the 99,999 cap. Restores the GM's MaxHP, MaxMP, HP and MP.
     */
    static List<String> hpMp4Byte(SyntheticPlayer p) throws Exception {
        Character gm = p.character();
        int maxHp0 = gm.getMaxHp(), maxMp0 = gm.getMaxMp(), hp0 = gm.getHp(), mp0 = gm.getMp();
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        out.add("before: MaxHP " + maxHp0 + ", MaxMP " + maxMp0 + ", HP " + hp0 + ", MP " + mp0);
        try {
            String built = SyntheticClient.decodeHpMpStats(PacketCreator.updatePlayerStats(
                    new ArrayList<>(List.of(new Pair<>(Stat.MAXHP, 50_000), new Pair<>(Stat.MAXMP, 45_000))), true, gm)
                    .getBytes());
            out.add("a built stat packet reads back as: " + built);
            if (statValue(built, "MAXHP") != 50_000 || statValue(built, "MAXMP") != 45_000) {
                bad.add("a stat packet for MaxHP 50,000 / MaxMP 45,000 reads back as " + built);
            }
            long t0 = System.currentTimeMillis();
            String reply = sayAndHear(p, "!setmaxhp 50000 45000", "Base MaxHP|Syntax", 8000);
            out.add("!setmaxhp 50000 45000: " + reply);
            Thread.sleep(300);
            String stat = lastStat(p, t0);
            out.add("STAT heard: " + stat);
            int cmh = gm.getClientMaxHp(), cmm = gm.getClientMaxMp();
            if (gm.getMaxHp() != 50_000 || gm.getMaxMp() != 45_000) {
                bad.add("base MaxHP/MaxMP " + gm.getMaxHp() + "/" + gm.getMaxMp() + ", want 50000/45000");
            }
            if (statValue(stat, "MAXHP") != cmh || cmh <= 32_767 || statValue(stat, "MAXMP") != cmm || cmm <= 32_767) {
                bad.add("the STAT packet carried " + stat + ", want MAXHP=" + cmh + " MAXMP=" + cmm + " (over 32,767)");
            }
            long t1 = System.currentTimeMillis();
            String capped = sayAndHear(p, "!setmaxhp 150000", "Base MaxHP|Syntax", 8000);
            out.add("!setmaxhp 150000: " + capped);
            Thread.sleep(300);
            String stat2 = lastStat(p, t1);
            out.add("STAT heard: " + stat2);
            if (gm.getMaxHp() != constants.game.GameConstants.MAX_HPMP || gm.getClientMaxHp() != 99_999
                    || statValue(stat2, "MAXHP") != 99_999) {
                bad.add("150,000 was not capped at 99,999 (base " + gm.getMaxHp() + ", shown " + gm.getClientMaxHp()
                        + ", packet " + stat2 + ")");
            }
        } finally {
            gm.updateMaxHpMaxMp(maxHp0, maxMp0);
            gm.updateHpMp(hp0, mp0);
            boolean restored = gm.getMaxHp() == maxHp0 && gm.getMaxMp() == maxMp0;
            out.add("restored: MaxHP " + gm.getMaxHp() + ", MaxMP " + gm.getMaxMp() + ", HP " + gm.getHp() + ", MP " + gm.getMp());
            if (!restored) {
                bad.add("could not restore MaxHP/MaxMP to " + maxHp0 + "/" + maxMp0);
            }
        }
        return verdict(bad, "MaxHP/MaxMP past 32,767 travel as 4 bytes and stop at 99,999; restored", out);
    }

    private static String lastStat(SyntheticPlayer p, long since) {
        String last = null;
        for (AutoQaLine l : p.heard(since)) {
            if ("STAT".equals(l.kind())) {
                last = l.message();
            }
        }
        return last;
    }

    // ── t-au-1: the site auth API ────────────────────────────────────────────────────────────────

    /**
     * t-au-1: invite ({@code !invite}) -> sign-up -> sign-in -> reset request -> complete refused while pending -> GM
     * approve ({@code !resetpw approve}) -> complete -> sign in with the new password (the old one fails) -> a second
     * complete is refused. The routes run in-process on the running API, after its secret check (the secret is never
     * read); the HTTP side must refuse a request without the secret. The account, its reset rows and the invite go.
     */
    static List<String> authFlow(SyntheticPlayer p) throws Exception {
        String off = SiteAuthQa.notListening();
        if (off != null) {
            return List.of("SKIP " + off);
        }
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        long n = System.currentTimeMillis();
        String stamp = String.format(Locale.ROOT, "%06d", n / 1000 % 1_000_000);
        String user = "qaau" + stamp;
        String pass1 = "Qa1!" + stamp, pass2 = "Qb2#" + stamp;
        String ip = "autoqa-t-au-1-" + n;
        String code = null;
        int accountId = -1;
        try {
            int unauth = SiteAuthQa.statusWithoutSecret();
            out.add("POST /verify on 127.0.0.1:" + SiteAuthQa.port() + " without the secret: HTTP " + unauth);
            if (unauth != 401) {
                bad.add("a request without the secret got HTTP " + unauth + ", want 401");
            }
            String invite = sayAndHear(p, "!invite 1d autoqa t-au-1 throwaway", "Invite code: |aren't available", 10000);
            out.add("!invite: " + invite);
            Matcher m = Pattern.compile("Invite code: (\\S+)").matcher(invite == null ? "" : invite);
            if (!m.find()) {
                bad.add("!invite gave no code");
                return verdict(bad, "", out);
            }
            code = m.group(1);
            SiteAuthQa.Answer signup = SiteAuthQa.call("/signup", Map.of("code", code, "user", user, "pass", pass1, "ipHash", ip));
            out.add("signup " + user + ": " + signup.status() + " " + signup.body());
            if (!signup.ok()) {
                bad.add("sign-up with a fresh invite failed: " + signup.error());
                return verdict(bad, "", out);
            }
            accountId = ((Number) signup.body().get("account_id")).intValue();
            check(bad, out, "sign-in", SiteAuthQa.call("/verify", Map.of("user", user, "pass", pass1, "ipHash", ip)), null);
            SiteAuthQa.Answer req = SiteAuthQa.call("/reset/request", Map.of("user", user, "contact", "autoqa t-au-1", "ipHash", ip));
            out.add("reset request: " + req.status() + " ok=" + req.ok() + (req.ok() ? "" : " " + req.error()));
            if (!req.ok()) {
                bad.add("reset request failed: " + req.error());
                return verdict(bad, "", out);
            }
            String rid = (String) req.body().get("request_id");
            String secret = (String) req.body().get("secret");
            if (!SiteAuthQa.awaitReset(rid, 10_000)) {
                bad.add("the reset request was not stored within 10 s (migration 037?)");
                return verdict(bad, "", out);
            }
            Map<String, Object> ask = new LinkedHashMap<>(Map.of("request_id", rid, "secret", secret, "ipHash", ip));
            status(bad, out, ask, "pending");
            Map<String, Object> finish = new LinkedHashMap<>(ask);
            finish.put("pass", pass2);
            check(bad, out, "complete before approval", SiteAuthQa.call("/reset/complete", finish), "NOT_APPROVED");
            String approved = sayAndHear(p, "!resetpw approve " + user, "Approved the reset|No pending reset|aren't available",
                    10000);
            out.add("!resetpw approve: " + approved);
            if (approved == null || !approved.startsWith("Approved")) {
                bad.add("the GM could not approve the reset");
                return verdict(bad, "", out);
            }
            status(bad, out, ask, "approved");
            check(bad, out, "complete", SiteAuthQa.call("/reset/complete", finish), null);
            check(bad, out, "sign-in with the new password",
                    SiteAuthQa.call("/verify", Map.of("user", user, "pass", pass2, "ipHash", ip)), null);
            check(bad, out, "sign-in with the old password",
                    SiteAuthQa.call("/verify", Map.of("user", user, "pass", pass1, "ipHash", ip)), "WRONG_LOGIN");
            check(bad, out, "a second complete", SiteAuthQa.call("/reset/complete", finish), "USED");
        } finally {
            try {
                out.add("cleanup: " + SiteAuthQa.cleanup(accountId, user, code) + " row(s) removed (account, resets, invite)");
            } catch (Exception e) {
                bad.add("cleanup failed: " + e.getMessage());
            }
        }
        return verdict(bad, "invite, sign-up, sign-in, reset request, GM approval and new password all work", out);
    }

    /** One call's verdict: {@code wantError} null = must succeed, else must fail with that error. */
    private static void check(List<String> bad, List<String> out, String what, SiteAuthQa.Answer a, String wantError) {
        out.add(what + ": " + a.status() + " ok=" + a.ok() + (a.error() == null ? "" : " " + a.error()));
        if (wantError == null ? !a.ok() : a.ok() || !wantError.equals(a.error())) {
            bad.add(what + ": got " + (a.ok() ? "ok" : a.error()) + ", want " + (wantError == null ? "ok" : wantError));
        }
    }

    private static void status(List<String> bad, List<String> out, Map<String, Object> ask, String want) throws Exception {
        SiteAuthQa.Answer st = SiteAuthQa.call("/reset/status", ask);
        Object got = st.body().get("status");
        out.add("reset status: " + got);
        if (!want.equals(got)) {
            bad.add("reset status " + got + ", want " + want);
        }
    }

    // ── t-np-4: the custom NPCs' caps ────────────────────────────────────────────────────────────

    static final int COCO = 9000017, DALAIR = 9000040, SCROLL_GENERATOR_NPC = 9201088;
    static final int SCROLL_GENERATOR_QUEST = 32010;
    /** t-np-4: a plain (non-cash) hat with upgrade slots to merge into, and a sword to merge from. */
    static final int MERGE_HAT = 1002001, MERGE_SOURCE = 1302000;

    /**
     * t-np-4: Coco's number box stops at 5 and 6 is refused; Dalair turns the level-70 GM away with the level 160
     * rule; the merge gate (level 160, 110 for Cygnus, no Maker) and the 3-merge cap on a scratch character that is
     * never in the world or saved; Maple TV's scroll generator refuses after 5 prizes today and opens below that
     * (the GM's quest record is restored).
     */
    static List<String> customNpcCaps(SyntheticPlayer p) throws Exception {
        if (!YamlConfig.config.server.USE_ENABLE_CUSTOM_NPC_SCRIPT) {
            return List.of("SKIP USE_ENABLE_CUSTOM_NPC_SCRIPT is off on this server (the custom NPCs are closed)");
        }
        Character gm = p.character();
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();

        // Coco: the number box, then 6 and 5
        List<String> box = talk(p, COCO, null, new int[][]{{1, 0, 0}, {1, 0, 0}, {1, 0, 6}});
        out.add("Coco with 6: " + tail(box, 2));
        boolean max5 = box.stream().anyMatch(l -> l.startsWith("NPC_NUMBER") && l.endsWith("max 5"));
        boolean six = box.stream().anyMatch(l -> l.contains("only make up to 5"));
        if (!max5) {
            bad.add("Coco's number box does not stop at 5");
        }
        if (!six) {
            bad.add("Coco did not refuse 6 Chaos Scrolls");
        }
        List<String> five = talk(p, COCO, null, new int[][]{{1, 0, 0}, {1, 0, 0}, {1, 0, 5}});
        out.add("Coco with 5: " + tail(five, 1));
        if (five.stream().noneMatch(l -> l.contains("make 5 "))) {
            bad.add("Coco did not take an order of 5");
        }

        // Dalair: the real talk on the GM, then the gate and the cap on a scratch character
        if (!gm.meetsEquipmentMergeRequirements()) {
            List<String> d = talk(p, DALAIR, null, new int[0][]);
            out.add("Dalair (" + gm.getName() + ", level " + gm.getLevel() + "): " + tail(d, 1));
            if (d.stream().noneMatch(l -> l.contains("level 160") && !l.contains("Maker"))) {
                bad.add("Dalair did not turn away a level " + gm.getLevel() + " character with the level 160 rule (no Maker mention)");
            }
        }
        mergeChecks(gm, bad, out);

        // Maple TV: 5 prizes a day, from the quest record
        String today = java.time.LocalDate.now().toString();
        String before = questProgress(gm, SCROLL_GENERATOR_QUEST);
        try {
            gm.setQuestProgress(SCROLL_GENERATOR_QUEST, 0, today + ":5");
            List<String> full = talk(p, SCROLL_GENERATOR_NPC, "mapleTV", new int[0][]);
            out.add("Maple TV after 5 today: " + tail(full, 1));
            if (full.stream().noneMatch(l -> l.contains("already claimed") && l.contains("5 prizes"))) {
                bad.add("Maple TV's scroll generator did not refuse after 5 prizes today");
            }
            gm.setQuestProgress(SCROLL_GENERATOR_QUEST, 0, today + ":4");
            List<String> open = talk(p, SCROLL_GENERATOR_NPC, "mapleTV", new int[0][]);
            out.add("Maple TV after 4 today: " + tail(open, 1));
            if (open.stream().noneMatch(l -> l.contains("Place your supplies")) || open.stream().anyMatch(l -> l.contains("already claimed"))) {
                bad.add("Maple TV's scroll generator did not open with a prize left today");
            }
        } finally {
            gm.setQuestProgress(SCROLL_GENERATOR_QUEST, 0, before == null ? "" : before);
        }
        return verdict(bad, "Coco 5 per talk, Dalair's level-only 160/110 gate and 3 merges, Maple TV 5 a day", out);
    }

    private static String questProgress(Character gm, int quest) {
        var qs = gm.getQuest(Quest.getInstance(quest));
        return qs == null ? null : qs.getProgress(0);
    }

    private static String tail(List<String> lines, int n) {
        return lines.isEmpty() ? "(no answer)" : String.join(" | ", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    /** Talks to an NPC (its script, or {@code file}) and answers each {@code {mode, type, selection}}; what was shown. */
    static List<String> talk(SyntheticPlayer p, int npc, String file, int[][] answers) throws InterruptedException {
        Character gm = p.character();
        Client c = gm.getClient();
        long since = System.currentTimeMillis();
        NPCScriptManager m = NPCScriptManager.getInstance();
        c.removeClickedNPC();
        boolean started = file == null ? m.start(c, npc, gm) : m.start(c, npc, file, gm);
        if (started) {
            for (int[] a : answers) {
                Thread.sleep(150);
                m.action(c, (byte) a[0], (byte) a[1], a[2]);
                c.removeClickedNPC();
            }
        }
        Thread.sleep(200);
        m.dispose(c);
        c.removeClickedNPC();
        List<String> out = new ArrayList<>();
        if (!started) {
            out.add("(the script did not start)");
        }
        for (AutoQaLine l : p.heard(since)) {
            if ("NPC_TALK".equals(l.kind()) || "NPC_NUMBER".equals(l.kind())) {
                out.add(l.kind() + " " + l.message());
            }
        }
        return out;
    }

    /** Dalair's merge gate and its 3-merge cap on a scratch character: never in the world, never saved. */
    static void mergeChecks(Character gm, List<String> bad, List<String> out) {
        SyntheticClient scratch = new SyntheticClient(97, gm.getWorld(), gm.getClient().getChannel());
        Character chr = Character.getDefault(scratch);
        scratch.setPlayer(chr);
        chr.setName("QaMerge");
        bad.addAll(mergeGateProblems());
        chr.setJob(Job.HERO);
        chr.setLevel(160);
        boolean hero160 = chr.meetsEquipmentMergeRequirements();
        chr.setLevel(159);
        boolean hero159 = chr.meetsEquipmentMergeRequirements();
        chr.setJob(Job.DAWNWARRIOR3);
        chr.setLevel(110);
        boolean cyg110 = chr.meetsEquipmentMergeRequirements();
        chr.setLevel(109);
        boolean cyg109 = chr.meetsEquipmentMergeRequirements();
        out.add("merge gate (level only): Hero 160=" + hero160 + ", Hero 159=" + hero159
                + ", Dawn Warrior 110=" + cyg110 + ", Dawn Warrior 109=" + cyg109);
        if (!hero160 || hero159 || !cyg110 || cyg109) {
            bad.add("Dalair's gate is wrong (want only level 160, or 110 for Cygnus, to pass)");
        }

        chr.setJob(Job.HERO);
        chr.setLevel(160);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item hatItem = ii.getEquipById(MERGE_HAT);
        if (!(hatItem instanceof Equip hat)) {
            bad.add("no equip " + MERGE_HAT + " for the merge check");
            return;
        }
        hat.setPosition((short) -1);
        chr.getInventory(InventoryType.EQUIPPED).addItemFromDB(hat);
        String junkName = ii.getName(MERGE_SOURCE);
        List<String> runs = new ArrayList<>();
        int fourth = -1;
        boolean kept = false;
        for (int run = 1; run <= 4; run++) {
            Equip junk = new Equip(MERGE_SOURCE, (short) 1);
            junk.setStr((short) 50);
            junk.setDex((short) 50);
            junk.setWdef((short) 50);   // a stat the hat has, so every merge lands; STR/DEX alone may all round to 0
            chr.getInventory(InventoryType.EQUIP).addItemFromDB(junk);
            int r = chr.mergeItemsFromName(junkName);
            runs.add(r + "/" + Character.getMergeCount(hat));
            if (run == 4) {
                fourth = r;
                kept = chr.getInventory(InventoryType.EQUIP).findById(MERGE_SOURCE) != null;
            }
        }
        boolean flagHidden = ItemConstants.getMergeCount(ItemConstants.clientFlag(hat.getFlag())) == 0;
        out.add("merges (result/count): " + runs + ", 4th kept the source=" + kept + ", count hidden from the client="
                + flagHidden);
        boolean threeOk = runs.size() == 4 && runs.get(0).equals(Character.MERGE_OK + "/1")
                && runs.get(1).equals(Character.MERGE_OK + "/2") && runs.get(2).equals(Character.MERGE_OK + "/3");
        if (!threeOk || fourth != Character.MERGE_ALL_CAPPED || !kept || !flagHidden) {
            bad.add("an item did not stop at 3 merges (" + runs + ", 4th kept the source=" + kept + ")");
        }
    }

    /** Dalair's gate on the pure rule (no character, no Skill.wz); empty when it is right. */
    static List<String> mergeGateProblems() {
        List<String> bad = new ArrayList<>();
        if (!Character.meetsMergeGate(160, false) || !Character.meetsMergeGate(110, true)) {
            bad.add("Dalair's gate refuses level 160 (110 Cygnus) ");
        }
        if (Character.meetsMergeGate(159, false) || Character.meetsMergeGate(109, true)) {
            bad.add("Dalair's gate lets in a character below level 160 (110 for Cygnus)");
        }
        return bad;
    }

    /** The 3-merge cap on the item flag (Character.getMergeableEquipped takes fewer than 3): it stops at 3 and never reaches the client. */
    static List<String> mergeCapProblems() {
        List<String> bad = new ArrayList<>();
        short two = ItemConstants.withMergeCount((short) 0, 2), three = ItemConstants.withMergeCount((short) 0, 4);
        if (ItemConstants.getMergeCount(two) != 2 || ItemConstants.getMergeCount(three) != ItemConstants.MAX_MERGES_PER_ITEM
                || ItemConstants.MAX_MERGES_PER_ITEM != 3) {
            bad.add("an item's merge count does not stop at 3 (2 -> " + ItemConstants.getMergeCount(two) + ", 4 -> "
                    + ItemConstants.getMergeCount(three) + ")");
        }
        if (ItemConstants.getMergeCount(ItemConstants.clientFlag(three)) != 0) {
            bad.add("the merge count reaches the client");
        }
        return bad;
    }

    // ── t-ck-1: Daily Check-In ───────────────────────────────────────────────────────────────────

    /** The loaded table, one short line per day: NX, items, mesos, slots. */
    static List<String> checkinTable() {
        List<String> out = new ArrayList<>();
        List<Integer> nxDays = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (int d = 1; d <= DailyCheckinStreak.CYCLE_DAYS; d++) {
            DailyCheckinRewards.Reward r = DailyCheckinRewards.of(d);
            if (r == null) {
                break;
            }
            if (r.nxCredit() > 0) {
                nxDays.add(d);
            }
            row.append(" d").append(d).append('=').append(r.nxCredit() > 0 ? r.nxCredit() + "nx" : "")
                    .append(r.grants().size()).append("it").append(r.slotCount() > 0 ? "+" + r.slotCount() + "sl" : "");
            if (d % 7 == 0) {
                out.add("table:" + row);
                row.setLength(0);
            }
        }
        if (row.length() > 0) {
            out.add("table:" + row);
        }
        out.add(0, "NX on days " + nxDays);
        return out;
    }

    /**
     * t-ck-1: the GM's account claims today's day once through the window's packet (CP 0x3732): the day's items,
     * slots, mesos and NX land; a second claim (the same day and the next) is refused and changes nothing. If the QA
     * account claimed within 24 h, its last claim is moved back first (the streak goes on). The items are taken back.
     */
    static List<String> dailyCheckin(SyntheticPlayer p) throws Exception {
        if (!DailyCheckin.enabled()) {
            return List.of("SKIP DAILY_CHECKIN_ENABLED is off on this server");
        }
        Character gm = p.character();
        if (!DailyCheckin.eligible(gm)) {
            return List.of("SKIP " + gm.getName() + " is below DAILY_CHECKIN_MIN_LEVEL");
        }
        List<String> out = new ArrayList<>(checkinTable());
        List<String> bad = new ArrayList<>();
        int acc = gm.getAccountID();
        long now = System.currentTimeMillis();
        DailyCheckinStreak s0 = DailyCheckin.streakForQa(acc);
        DailyCheckinStreak s = DailyCheckin.rewindForQa(acc, now);
        if (!s.equals(s0)) {
            out.add("rewound the QA account's last claim by 24 h (claimed " + (now - s0.lastClaim()) / 60_000
                    + " min ago) so it can claim again");
        }
        int day = s.claimableDay(now);
        DailyCheckinRewards.Reward r = DailyCheckinRewards.of(day);
        if (day <= 0 || r == null) {
            return verdict(List.of("nothing claimable after the rewind (day " + day + ")"), "", out);
        }
        out.add("claiming day " + day + ": " + DailyCheckinRewards.tooltip(day).replace('\n', ' '));
        if (!DailyCheckinRewards.canReceive(gm, day)) {
            return verdict(List.of("no room for day " + day + "'s reward in " + gm.getName() + "'s bags"), "", out);
        }
        Map<Integer, Integer> items0 = new LinkedHashMap<>();
        for (DailyCheckinRewards.Grant g : r.grants()) {
            items0.merge(g.itemId(), gm.getItemQuantity(g.itemId(), false), (x, y) -> x);
        }
        long nx0 = gm.getCashShop().getCash(1), meso0 = gm.getMeso();
        int slots0 = r.slotType() > 0 ? gm.getSlots(r.slotType()) : 0;
        claim(p, day);
        DailyCheckinStreak s1 = DailyCheckin.streakForQa(acc);
        long nx1 = gm.getCashShop().getCash(1), meso1 = gm.getMeso();
        int slots1 = r.slotType() > 0 ? gm.getSlots(r.slotType()) : 0;
        int room = Math.max(0, 96 - slots0);
        int addedSlots = r.slotType() > 0 ? Math.min(room, r.slotCount()) : 0;
        long wantNx = r.nxCredit() + (long) (r.slotType() > 0 ? r.slotCount() - addedSlots : 0) * 500;
        List<String> got = new ArrayList<>();
        for (DailyCheckinRewards.Grant g : r.grants()) {
            int delta = gm.getItemQuantity(g.itemId(), false) - items0.get(g.itemId());
            got.add(g.itemId() + " +" + delta);
            if (delta < g.qty()) {
                bad.add("item " + g.itemId() + " +" + delta + ", want +" + g.qty());
            }
        }
        out.add("claim: streak day " + s.day() + " -> " + s1.day() + ", items " + got + ", NX +" + (nx1 - nx0)
                + ", mesos +" + (meso1 - meso0) + ", slots +" + (slots1 - slots0));
        if (s1.day() != day) {
            bad.add("the claim was not recorded (streak day " + s1.day() + ", want " + day + ")");
        }
        if (nx1 - nx0 != wantNx) {
            bad.add("NX +" + (nx1 - nx0) + ", want +" + wantNx);
        }
        if (meso1 - meso0 != r.mesos()) {
            bad.add("mesos +" + (meso1 - meso0) + ", want +" + r.mesos());
        }
        if (slots1 - slots0 != addedSlots) {
            bad.add("slots +" + (slots1 - slots0) + ", want +" + addedSlots);
        }
        // once per 24 h: the same day again, and the next day, change nothing
        claim(p, day);
        claim(p, day + 1);
        DailyCheckinStreak s2 = DailyCheckin.streakForQa(acc);
        long nx2 = gm.getCashShop().getCash(1);
        out.add("claiming again (day " + day + ", then " + (day + 1) + "): streak day " + s2.day() + ", NX +" + (nx2 - nx1));
        if (!s2.equals(s1) || nx2 != nx1) {
            bad.add("a second claim within 24 h was paid (streak " + s1 + " -> " + s2 + ", NX +" + (nx2 - nx1) + ")");
        }
        // take the items back so a month of nightly runs does not fill the bags (NX, mesos and slots stay)
        for (DailyCheckinRewards.Grant g : r.grants()) {
            int extra = gm.getItemQuantity(g.itemId(), false) - items0.get(g.itemId());
            if (extra > 0) {
                client.inventory.manipulator.InventoryManipulator.removeById(gm.getClient(),
                        ItemConstants.getInventoryType(g.itemId()), g.itemId(), extra, false, false);
            }
        }
        return verdict(bad, "day " + day + " claimed once, its reward landed, a second claim was refused", out);
    }

    // ── t-ss-1: Slums Stamps (ticket 111) ────────────────────────────────────────────────────────

    /** t-ss-1: a Donor NX cosmetic (Rooty's first Donor Cap) and its price in stamps. */
    static final int STAMP_CAP = 1003536, STAMP_CAP_PRICE = 6;

    /**
     * t-ss-1: claim -> stamp -> buy on the GM's account. (1) A claim as staff lands the day but no stamps. (2) With the
     * check's own earn override, the next claim (the last one moved back 24 h first, as t-ck-1 does) credits exactly
     * the day's stamps in the same write. (3) Topped up to the price if needed, Rooty's purchase call buys a Donor
     * cosmetic for exactly 6 stamps; (4) one stamp short, it is refused and takes nothing. The balance, the claimed
     * items and the cap are put back afterwards.
     */
    static List<String> slumsStamps(SyntheticPlayer p) throws Exception {
        if (!DailyCheckin.enabled()) {
            return List.of("SKIP DAILY_CHECKIN_ENABLED is off on this server");
        }
        Character gm = p.character();
        if (!DailyCheckin.eligible(gm)) {
            return List.of("SKIP " + gm.getName() + " is below DAILY_CHECKIN_MIN_LEVEL");
        }
        int acc = gm.getAccountID();
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        int b0 = server.checkin.SlumsStamps.balance(acc);
        int caps0 = gm.getItemQuantity(STAMP_CAP, false);
        Map<Integer, Integer> items0 = new LinkedHashMap<>();
        for (int d = 1; d <= DailyCheckinStreak.CYCLE_DAYS; d++) {
            for (DailyCheckinRewards.Grant g : DailyCheckinRewards.of(d).grants()) {
                items0.putIfAbsent(g.itemId(), gm.getItemQuantity(g.itemId(), false));
            }
        }
        out.add("balance before: " + b0);
        try {
            // (1) staff: the day lands, no stamps
            DailyCheckinStreak s = DailyCheckin.rewindForQa(acc, System.currentTimeMillis());
            int day = s.claimableDay(System.currentTimeMillis());
            claim(p, day);
            DailyCheckinStreak s1 = DailyCheckin.streakForQa(acc);
            int b1 = server.checkin.SlumsStamps.balance(acc);
            out.add("staff claim of day " + day + ": streak day " + s1.day() + ", stamps " + b0 + " -> " + b1);
            if (s1.day() != day) {
                bad.add("the staff claim of day " + day + " was not recorded (streak day " + s1.day() + ")");
            }
            if (b1 != b0) {
                bad.add("a staff account earned " + (b1 - b0) + " stamps");
            }
            // (2) the earn override: exactly the day's stamps
            server.checkin.SlumsStamps.qaEarnerForCheck(acc, true);
            s = DailyCheckin.rewindForQa(acc, System.currentTimeMillis());
            int day2 = s.claimableDay(System.currentTimeMillis());
            int want = DailyCheckinRewards.of(day2).stamps();
            claim(p, day2);
            server.checkin.SlumsStamps.qaEarnerForCheck(acc, false);
            int b2 = server.checkin.SlumsStamps.balance(acc);
            out.add("earning claim of day " + day2 + ": stamps " + b1 + " -> " + b2 + " (want +" + want + ")");
            if (b2 - b1 != want) {
                bad.add("the claim of day " + day2 + " credited " + (b2 - b1) + " stamps, want " + want);
            }
            claim(p, day2);   // again within 24 h: nothing
            if (server.checkin.SlumsStamps.balance(acc) != b2) {
                bad.add("a second claim within 24 h credited stamps");
            }
            // (3) buy a Donor cosmetic through Rooty's purchase call
            if (b2 < STAMP_CAP_PRICE) {
                server.checkin.SlumsStamps.add(acc, STAMP_CAP_PRICE - b2);
                out.add("topped up " + (STAMP_CAP_PRICE - b2) + " stamps to reach the price");
            }
            int b3 = server.checkin.SlumsStamps.balance(acc);
            scripting.npc.NPCConversationManager cm = new scripting.npc.NPCConversationManager(gm.getClient(), 9010004,
                    "rooty");
            String bought = cm.buyWithStamps(STAMP_CAP_PRICE, STAMP_CAP, -1);
            int b4 = server.checkin.SlumsStamps.balance(acc);
            int caps1 = gm.getItemQuantity(STAMP_CAP, false);
            out.add("buy " + STAMP_CAP + " for " + STAMP_CAP_PRICE + ": " + bought + ", stamps " + b3 + " -> " + b4
                    + ", caps " + caps0 + " -> " + caps1);
            if (!"BOUGHT".equals(bought) || b3 - b4 != STAMP_CAP_PRICE || caps1 - caps0 != 1) {
                bad.add("the purchase: " + bought + ", stamps -" + (b3 - b4) + ", caps +" + (caps1 - caps0));
            }
            // (4) one short: refused, nothing taken or given
            server.checkin.SlumsStamps.restoreForQa(acc, STAMP_CAP_PRICE - 1);
            String refused = cm.buyWithStamps(STAMP_CAP_PRICE, STAMP_CAP, -1);
            int b5 = server.checkin.SlumsStamps.balance(acc);
            out.add("one stamp short: " + refused + ", stamps " + b5 + ", caps " + gm.getItemQuantity(STAMP_CAP, false));
            if (!"SHORT".equals(refused) || b5 != STAMP_CAP_PRICE - 1 || gm.getItemQuantity(STAMP_CAP, false) != caps1) {
                bad.add("a purchase one stamp short: " + refused + ", stamps " + b5);
            }
        } finally {
            server.checkin.SlumsStamps.qaEarnerForCheck(acc, false);
            server.checkin.SlumsStamps.restoreForQa(acc, b0);
            int extraCaps = gm.getItemQuantity(STAMP_CAP, false) - caps0;
            if (extraCaps > 0) {
                client.inventory.manipulator.InventoryManipulator.removeById(gm.getClient(),
                        ItemConstants.getInventoryType(STAMP_CAP), STAMP_CAP, extraCaps, false, false);
            }
            for (Map.Entry<Integer, Integer> e : items0.entrySet()) {
                int extra = gm.getItemQuantity(e.getKey(), false) - e.getValue();
                if (extra > 0) {
                    client.inventory.manipulator.InventoryManipulator.removeById(gm.getClient(),
                            ItemConstants.getInventoryType(e.getKey()), e.getKey(), extra, false, false);
                }
            }
        }
        return verdict(bad, "a staff claim earned nothing, an earning claim credited the day's stamps once, a Donor "
                + "cosmetic cost exactly " + STAMP_CAP_PRICE + " and one short was refused", out);
    }

    // ── t-fy-1: the Family system (ticket 53, 1.6.1) ─────────────────────────────────────────────

    /**
     * t-fy-1: (1) the pretend player invites an online bot as junior and the bot accepts (its family rules, staged
     * to say yes); (2) a kill by the junior gives the senior rep; (3) My Drop Rate 1.5x and My EXP 2x spend their rep
     * and give 1.5x drop / 2x EXP; (4) the same entitlement again is refused, nothing spent; (5) the family is
     * dissolved again.
     */
    static List<String> family(SyntheticPlayer p) {
        if (!config.YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return List.of("SKIP USE_FAMILY_SYSTEM is off on this server");
        }
        Character me = p.character();
        soloMapling.ArtificialPlayer.FamilyQa.Staged st;
        try {
            st = soloMapling.ArtificialPlayer.FamilyQa.stage(me);
        } catch (IllegalStateException e) {
            return List.of("FAIL " + e.getMessage());
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        try {
            client.FamilyEntry mine = me.getFamilyEntry();
            client.FamilyEntry junior = st.bot().getFamilyEntry();
            ev.add("junior " + st.bot().getName() + " lv" + st.bot().getLevel() + ", family " + st.familyId());
            if (mine == null || junior == null || junior.getSenior() != mine) {
                bad.add("the bot is not linked as the player's junior");
                return verdict(bad, "", ev);
            }
            int r0 = mine.getReputation();
            junior.giveReputationToSenior(config.YamlConfig.config.server.FAMILY_REP_PER_KILL, true);
            if (mine.getReputation() <= r0) {
                bad.add("a junior's kill gave no rep (" + r0 + " -> " + mine.getReputation() + ")");
            }
            mine.gainReputation(700 + 1500, false);
            int r1 = mine.getReputation();
            long now = System.currentTimeMillis();
            String a = net.server.channel.handlers.FamilyUseHandler.useBuffEntitlement(me,
                    client.FamilyEntitlement.SELF_DROP_1_5, now);
            String b = net.server.channel.handlers.FamilyUseHandler.useBuffEntitlement(me,
                    client.FamilyEntitlement.SELF_EXP_2, now);
            double drop = client.FamilyBuffs.dropMultiplier(me.getId(), now + 1000);
            double exp = client.FamilyBuffs.expMultiplier(me.getId(), now + 1000);
            ev.add("drop 1.5x: " + (a == null ? "given" : a) + ", exp 2x: " + (b == null ? "given" : b) + "; drop x"
                    + drop + ", exp x" + exp + ", rep " + r1 + " -> " + mine.getReputation());
            if (a != null || b != null || drop != 1.5 || exp != 2.0) {
                bad.add("the buffs were not given as their tooltips say");
            }
            if (mine.getReputation() != r1 - 2200) {
                bad.add("rep spent " + (r1 - mine.getReputation()) + ", expected 2200");
            }
            mine.gainReputation(1500, false);
            int r2 = mine.getReputation();
            String again = net.server.channel.handlers.FamilyUseHandler.useBuffEntitlement(me,
                    client.FamilyEntitlement.SELF_EXP_2, now);
            if (again == null || mine.getReputation() != r2) {
                bad.add("the same entitlement twice in a day was not refused for free");
            }
        } finally {
            ev.add(soloMapling.ArtificialPlayer.FamilyQa.teardown(me, st));
        }
        if (me.getFamily() != null) {
            bad.add("the QA family was not dissolved");
        }
        return verdict(bad, "a bot accepted being the junior, its kill gave rep, 1.5x drop and 2x EXP were given for"
                + " their rep, a second use was refused, and the family was dissolved", ev);
    }

    // ── t-ti-1: "Start on Maple Island?" (ticket 29, 1.6.1) ─────────────────────────────────────

    /**
     * t-ti-1: (1) the rule asks a new real Beginner at the start map and nobody else (Cygnus, Aran, a bot, a second
     * time, the switch off); (2) the question's Yes warps the pretend player to the island start (map 10000), where
     * the Maple Road quests are not hidden and Southperry has Shanks; (3) coming back, No leaves the player where
     * they were. The player is put back on the map they started on.
     */
    static List<String> tutorialIsland(SyntheticPlayer p) throws InterruptedException {
        if (!config.YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT) {
            return List.of("SKIP TUTORIAL_ISLAND_PROMPT is off on this server");
        }
        Character me = p.character();
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        int hub = constants.id.MapId.MAPLEROOT_HUB;
        if (!soloMapling.menus.TutorialIslandMenu.shouldAsk(true, 0, 1, hub, false, true)
                || !soloMapling.menus.TutorialIslandMenu.shouldAsk(true, 1000, 1, hub, false, true)
                || !soloMapling.menus.TutorialIslandMenu.shouldAsk(true, 2000, 1, hub, false, true)
                || soloMapling.menus.TutorialIslandMenu.shouldAsk(false, 0, 1, hub, false, true)
                || soloMapling.menus.TutorialIslandMenu.shouldAsk(true, 0, 1, hub, true, true)
                || soloMapling.menus.TutorialIslandMenu.shouldAsk(true, 0, 1, hub, false, false)) {
            bad.add("the rule does not ask exactly a new real starter-job character");
        }
        for (int q = 1000; q <= 1049; q++) {
            if (server.quest.HiddenQuests.isHidden(q, 0)) {
                bad.add("Maple Island quest " + q + " is hidden");
            }
        }
        var maps = me.getClient().getChannelServer().getMapFactory();
        var island = maps.getMap(soloMapling.menus.TutorialIslandMenu.ISLAND_START);
        var southperry = maps.getMap(constants.id.MapId.SOUTHPERRY);
        if (island == null || island.getPortals().isEmpty()) {
            bad.add("the island start map is missing or has no portals");
        }
        if (southperry == null || southperry.getMapObjects().stream().noneMatch(o -> o instanceof server.maps.MapObject
                && o.getType() == server.maps.MapObjectType.NPC
                && ((server.life.NPC) o).getId() == 22000)) {
            bad.add("Southperry has no Shanks (npc 22000) for the boat to Victoria");
        }
        int home = me.getMapId();
        boolean guideSeen = soloMapling.menus.GuideMenu.seen(me);
        if (!guideSeen) {
            me.getAreaInfos().put(soloMapling.menus.GuideMenu.SEEN_AREA, "autoqa"); // so no guide window opens over the run
        }
        try {
            var page = soloMapling.menus.TutorialIslandMenu.pageForQa(me);
            page.find("No").pick().apply(me);
            if (me.getMapId() != home) {
                bad.add("No moved the player (" + home + " -> " + me.getMapId() + ")");
            }
            ev.add("No: stayed on map " + me.getMapId());
            page.find("Yes").pick().apply(me);
            long end = System.currentTimeMillis() + 5000;
            while (me.getMapId() != soloMapling.menus.TutorialIslandMenu.ISLAND_START && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            ev.add("Yes: now on map " + me.getMapId());
            if (me.getMapId() != soloMapling.menus.TutorialIslandMenu.ISLAND_START) {
                bad.add("Yes did not land on the island start");
            }
            // b036: the other starter jobs, and a starter who is not on the start map (Lith Harbour), the same way
            var homeJob = me.getJob();
            int[][] cases = {{client.Job.BEGINNER.getId(), constants.id.MapId.LITH_HARBOUR},
                    {client.Job.NOBLESSE.getId(), home}, {client.Job.NOBLESSE.getId(), constants.id.MapId.LITH_HARBOUR},
                    {client.Job.LEGEND.getId(), home}, {client.Job.LEGEND.getId(), constants.id.MapId.LITH_HARBOUR}};
            for (int[] c : cases) {
                String label = client.Job.getById(c[0]).name() + " on map " + c[1];
                if (!soloMapling.menus.TutorialIslandMenu.shouldAsk(true, c[0], 1, c[1], false, true)) {
                    bad.add("the rule skips " + label);
                }
                me.setJob(client.Job.getById(c[0]));
                try {
                    toMap(me, c[1]);
                    me.getAreaInfos().remove(soloMapling.menus.TutorialIslandMenu.TRIP_AREA);
                    soloMapling.menus.TutorialIslandMenu.pageForQa(me).find("No").pick().apply(me);
                    if (me.getMapId() != c[1]) {
                        bad.add("No moved " + label + " to " + me.getMapId());
                    }
                    soloMapling.menus.TutorialIslandMenu.pageForQa(me).find("Yes").pick().apply(me);
                    long until = System.currentTimeMillis() + 5000;
                    while (me.getMapId() != soloMapling.menus.TutorialIslandMenu.ISLAND_START
                            && System.currentTimeMillis() < until) {
                        Thread.sleep(100);
                    }
                    boolean landed = me.getMapId() == soloMapling.menus.TutorialIslandMenu.ISLAND_START;
                    boolean trip = soloMapling.menus.TutorialIslandMenu.freeTrip(me);
                    ev.add(label + ": No stayed, Yes -> " + me.getMapId() + (trip ? ", free trip marked" : ", NO free trip"));
                    if (!landed) {
                        bad.add("Yes did not land " + label + " on the island");
                    }
                    if (!trip) {
                        bad.add("Yes did not mark the free trip for " + label);
                    }
                } finally {
                    me.setJob(homeJob);
                    me.getAreaInfos().remove(soloMapling.menus.TutorialIslandMenu.TRIP_AREA);
                }
            }
            for (int[] c : new int[][]{{client.Job.NOBLESSE.getId(), constants.id.MapId.MUSHROOM_TOWN},
                    {client.Job.LEGEND.getId(), constants.id.MapId.ARAN_TUTORIAL_START},
                    {client.Job.EVAN.getId(), constants.id.MapId.LITH_HARBOUR}, {client.Job.BEGINNER.getId(), 104000000}}) {
                boolean ask = soloMapling.menus.TutorialIslandMenu.shouldAsk(true, c[0], 1, c[1], false, true);
                boolean want = c[1] == 104000000 || c[1] == constants.id.MapId.LITH_HARBOUR;
                if (ask != want) {
                    bad.add("the rule " + (ask ? "asks" : "skips") + " job " + c[0] + " on map " + c[1]);
                }
            }
            if (soloMapling.menus.TutorialIslandMenu.shouldAsk(true, 0, 10, hub, false, true)) {
                bad.add("the rule asks a level 10 character");
            }
        } finally {
            if (me.getMapId() != home) {
                me.changeMap(home);
            }
            if (!guideSeen) {
                soloMapling.menus.GuideMenu.forget(me);
            }
        }
        return verdict(bad, "only a new real starter (Beginner, Noblesse, Legend, Evan) below level 10 off the island is asked, on the start map or another, No stayed put, Yes landed on the island"
                + " start, the island quests are not hidden and Southperry has Shanks", ev);
    }

    /** Moves the pretend player to a map and waits for it (up to 5 s). */
    private static void toMap(Character me, int mapId) throws InterruptedException {
        if (me.getMapId() == mapId) {
            return;
        }
        me.changeMap(mapId);
        long until = System.currentTimeMillis() + 5000;
        while (me.getMapId() != mapId && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
    }

    // ── t-rb-1: a regular earns a rebirth (ticket 30, 1.6.1) ──────────────────────────────────────

    /**
     * t-rb-1: (1) an online bot without rebirths is raised to 200 on its build (4th job); (2) with the pace forced its
     * decisions run Zakum and Horntail off screen on the players' rules (clear limits, its real damage against their
     * real HP; each outcome is real) until it holds both items, craft the key and grant the trip; (3) the rebirth at
     * Wyatt's rules: rebirths +1, level 1 beginner, key and items gone, the rebirth EXP rate, only level-1 gear worn
     * and every old piece kept, same mesos, no cash items, past skills kept, no second rebirth at level 1; (4) the bot
     * (and its boss-log rows) put back as they were.
     */
    static List<String> botRebirth(SyntheticPlayer p) {
        if (!soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.enabled()) {
            return List.of("SKIP bot rebirths are off (BOT_REBIRTH_ENABLED / USE_REBIRTH_SYSTEM)");
        }
        if (config.YamlConfig.config.server.BOT_REBIRTH_PER_DAY <= 0) {
            return List.of("SKIP BOT_REBIRTH_PER_DAY is 0");
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        // b034 run 3: a bot too weak for Horntail (a Dark Knight at 49,940 a second: 153 min of 120) can never earn its
        // key, which is right for it but proves nothing here: raise candidates until one's group clears both bosses
        // nine runs in ten, putting each weaker one back exactly as it was
        soloMapling.ArtificialPlayer.BotRebirthQa.Before b = null;
        java.util.Set<Integer> tried = new java.util.HashSet<>();
        for (int i = 0; i < RB1_CANDIDATES && b == null; i++) {
            soloMapling.ArtificialPlayer.BotRebirthQa.Before c;
            try {
                c = soloMapling.ArtificialPlayer.BotRebirthQa.pick(p.character(), tried);
            } catch (IllegalStateException e) {
                break;
            }
            tried.add(c.bot().getId());
            if (soloMapling.ArtificialPlayer.BotRebirthQa.raise(c)
                    && soloMapling.ArtificialPlayer.BotRebirthQa.strongEnough(c, ev)) {
                b = c;
            } else {
                ev.add(soloMapling.ArtificialPlayer.BotRebirthQa.restore(c));
            }
        }
        if (b == null) {
            bad.add("no online bot of " + tried.size() + " tried is strong enough at 200 for Zakum and Horntail");
            return verdict(bad, "", ev);
        }
        ev.add("bot " + b.bot().getName() + " lv " + b.level() + " job " + b.job() + " rebirths " + b.reborns());
        try {
            List<String> walk = soloMapling.ArtificialPlayer.BotRebirthQa.walk(b);
            ev.addAll(walk);
            bad.addAll(soloMapling.ArtificialPlayer.BotRebirthQa.check(b));
            ev.add("after: " + soloMapling.ArtificialPlayer.BotRebirthQa.describe(b));
        } finally {
            ev.add(soloMapling.ArtificialPlayer.BotRebirthQa.restore(b));
        }
        if (b.bot().getLevel() != b.level() || b.bot().getReborns() != b.reborns()
                || b.bot().getJob().getId() != b.job()) {
            bad.add("the bot was not put back");
        }
        return verdict(bad, "a level 200 bot earned Zakum's and Horntail's items in off-screen runs on the players'"
                + " rules, crafted its key and was reborn at level 1 as a beginner with the rebirth EXP rate, wearing"
                + " only level-1 gear with the rest kept, and its mesos and skills; then put back", ev);
    }

    // ── t-rb-2: a regular earns its key item from a real boss kill (ticket 30 promise audit, 1.6.1) ──────────────

    /** t-rb-1: how many online bots it raises at most looking for one strong enough. */
    static final int RB1_CANDIDATES = 6;

    /** t-rb-2: how long the Von Leon run may take before the check gives up (the catalog allows 20 minutes). */
    static final long RB2_RUN_MS = 17 * 60_000L;

    /**
     * t-rb-2: (1) an online bot without rebirths is raised to 200 (4th job) and staged at rebirth 1 holding Pink Bean's
     * item (bots cannot run Pink Bean's statue stage alone; staged and said so); (2) its next item must be Von Leon's,
     * which bots run themselves, and a real Von Leon run is formed for it: a real expedition with up to five free
     * regulars, the event started as the recruiter does, Von Leon summoned as his NPC does (staged at 1% of his HP so
     * the check fits in minutes; the run's notes give the real fight's expected length), fought with the bot attack
     * driver through the map's boss rules; (3) the run must clear (the event's own
     * monsterKilled / clearPQ) and the bot must hold Von Leon's item afterwards, earned in that run (the clear reward
     * or the floor), never before it; (4) its decisions craft the 2nd key, it is reborn (rebirth 2) at level 1 with
     * the rebirth checks of t-rb-1; (5) the bot, its items and the run's boss-log rows are put back.
     */
    static List<String> botRebirthBoss(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.enabled()) {
            return List.of("SKIP bot rebirths are off (BOT_REBIRTH_ENABLED / USE_REBIRTH_SYSTEM)");
        }
        if (config.YamlConfig.config.server.BOT_REBIRTH_PER_DAY <= 0) {
            return List.of("SKIP BOT_REBIRTH_PER_DAY is 0");
        }
        if (!soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBossRuns.liveRuns()) {
            return List.of("SKIP -Dslums.botRebirth.liveRuns=false (every boss off screen)");
        }
        soloMapling.ArtificialPlayer.BotRebirthQa.Before b;
        try {
            b = soloMapling.ArtificialPlayer.BotRebirthQa.pick(p.character());
        } catch (IllegalStateException e) {
            return List.of("FAIL " + e.getMessage());
        }
        int vlItem = soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss.VONLEON.clearItem;
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        ev.add("bot " + b.bot().getName() + " lv " + b.level() + " job " + b.job() + " rebirths " + b.reborns());
        try {
            if (!soloMapling.ArtificialPlayer.BotRebirthQa.raise(b)) {
                bad.add("the bot did not reach a 4th job at 200: " + soloMapling.ArtificialPlayer.BotRebirthQa.describe(b));
                return verdict(bad, "", ev);
            }
            ev.add(soloMapling.ArtificialPlayer.BotRebirthQa.stageVonLeon(b));
            int before = b.bot().getInventory(client.inventory.InventoryType.ETC).countById(vlItem);
            if (before != 0) bad.add("held Von Leon's item before the run");
            var run = soloMapling.ArtificialPlayer.BotRebirthQa.runVonLeon(b, RB2_RUN_MS);
            ev.add("run: started " + run.started() + ", cleared " + run.cleared() + ", item by " + run.gotBy()
                    + ", " + run.result());
            ev.addAll(run.notes());
            if (!run.started()) bad.add("no Von Leon run formed: " + run.result());
            else if (!run.cleared()) bad.add("the Von Leon run did not clear: " + run.result());
            int after = b.bot().getInventory(client.inventory.InventoryType.ETC).countById(vlItem);
            if (after != 1 || run.gotBy() == null) {
                bad.add("Von Leon's item " + vlItem + ": " + after + " held, earned by " + run.gotBy());
            }
            if (bad.isEmpty()) {
                List<String> fin = soloMapling.ArtificialPlayer.BotRebirthQa.finishRebirth(b);
                ev.addAll(fin);
                bad.addAll(soloMapling.ArtificialPlayer.BotRebirthQa.check(b, 1));
                ev.add("after: " + soloMapling.ArtificialPlayer.BotRebirthQa.describe(b));
            }
        } finally {
            ev.add(soloMapling.ArtificialPlayer.BotRebirthQa.restore(b));
        }
        if (b.bot().getLevel() != b.level() || b.bot().getReborns() != b.reborns()
                || b.bot().getJob().getId() != b.job()) {
            bad.add("the bot was not put back");
        }
        return verdict(bad, "a level 200 regular went into a real Von Leon run (boss staged at 1% HP), the kill cleared"
                + " the event, it came out with Von Leon's item from that kill, crafted its 2nd key and was reborn; then"
                + " put back", ev);
    }

    // ── t-ac-1: achievements and medals (ticket 93, 1.6.1) ────────────────────────────────────────

    /**
     * t-ac-1: through the real event path a Mano kill and ten kept catches each hand the pretend player exactly one
     * zero-stat medal and record it; an unrelated kill, nine catches and a repeat hand over nothing; @achievements lists
     * the earned goal; everything is cleaned up.
     */
    static List<String> achievements(SyntheticPlayer p) throws Exception {
        var svc = soloMapling.achievements.Achievements.service();
        if (svc == null) {
            return List.of("SKIP achievements are off (-Dslums.achievements=true)");
        }
        List<String> ev = new ArrayList<>();
        List<String> bad = soloMapling.achievements.AchievementsQa.selfCheck(svc, p.character(), ev);
        return verdict(bad, "a Mano kill and ten catches each gave one zero-stat medal exactly once, nothing else did,"
                + " and the list shows it", ev);
    }

    /**
     * t-ac-2 (1.6.1 promise audit): a real, named Kerning PQ instance on the pretend player's channel is cleared through
     * the event's own completion path (EventInstanceManager.clearPQ); the pq-kerning goal must be earned and its medal
     * handed over. t-ac-1 fed events straight to the service and could not see that clears keyed on the instance name.
     */
    static List<String> achievementClear(SyntheticPlayer p) throws Exception {
        var svc = soloMapling.achievements.Achievements.service();
        if (svc == null) {
            return List.of("SKIP achievements are off (-Dslums.achievements=true)");
        }
        Character me = p.character();
        var em = me.getClient().getChannelServer().getEventSM()
                .getEventManager(soloMapling.achievements.AchievementsQa.CLEAR_SCRIPT);
        List<String> ev = new ArrayList<>();
        List<String> bad = soloMapling.achievements.AchievementsQa.realClear(svc, me, em, 15_000, ev);
        return verdict(bad, "clearing a named Kerning PQ instance through clearPQ earned pq-kerning and handed over its medal",
                ev);
    }

    // ── t-pg-1: Lazy Penguins (migrations 046/048/049/051, 1.6.1) ─────────────────────────────────

    /** t-pg-1: {npc, map} for the 15 Lazy Penguins, as tools/data-align/lazy-penguins/penguins.tsv (a test keeps them equal). */
    static final int[][] PENGUINS = {{2985, 209000000}, {2986, 100000000}, {2987, 101000000}, {2988, 102000000},
            {2989, 103000000}, {2990, 104000000}, {2991, 105040300}, {2992, 120000000}, {2993, 200000000},
            {2994, 220000000}, {2995, 230000000}, {2996, 240000000}, {2997, 250000000}, {2998, 801000000},
            {2999, 600000000}};

    /**
     * t-pg-1: each penguin stands once on its town map on the pretend player's channel, is named "Lazy Penguin" (the
     * host WZ step), and a click on it opens its one lazy line. Mushroom Shrine (800000000) has none (051 moved it).
     */
    static List<String> lazyPenguins(SyntheticPlayer p) throws InterruptedException {
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        var maps = p.character().getClient().getChannelServer().getMapFactory();
        for (int[] pg : PENGUINS) {
            int id = pg[0];
            var map = maps.getMap(pg[1]);
            long here = map == null ? 0 : penguinsOn(map, id);
            String name = server.life.LifeFactory.getNPC(id).getName();
            List<String> said = talk(p, id, null, new int[0][]);
            ev.add(id + " @ " + pg[1] + ": " + here + " here, name " + name + ", click " + said);
            if (here != 1) {
                bad.add("penguin " + id + ": " + here + " on map " + pg[1] + ", want 1 (plife rows, 046/048/049/051)");
            }
            if (!"Lazy Penguin".equals(name)) {
                bad.add("penguin " + id + " is named '" + name + "' (host step: tools/data-align/add_lazy_penguins.py)");
            }
            if (said.stream().noneMatch(l -> l.startsWith("NPC_TALK"))) {
                bad.add("penguin " + id + ": a click showed no line " + said);
            }
        }
        var shrine = maps.getMap(800000000);
        if (shrine != null && penguinsOn(shrine, 2998) != 0) {
            bad.add("the Mint penguin still stands in Mushroom Shrine (800000000): migration 051 not applied");
        }
        return verdict(bad, "all 15 Lazy Penguins stand on their towns, are named and answer a click", ev);
    }

    private static long penguinsOn(server.maps.MapleMap map, int id) {
        return map.getMapObjects().stream()
                .filter(o -> o.getType() == server.maps.MapObjectType.NPC && ((server.life.NPC) o).getId() == id).count();
    }

    // ── t-nc-1: Monster Book cards on the Arcane River / Grandis mobs (migration 045, 1.6.1) ────────

    /** t-nc-1: (mob, card) samples from migration 045's list: first, a middle one, the last. */
    static final int[][] NEWER_AREA_CARDS = {{2400000, 2386030}, {8641004, 2387154}, {8210000, 2387082}};

    /**
     * t-nc-1: each sampled mob rolls its card at 2% (chance 20000, qty 1) and the card maps back to the mob in
     * monstercarddata (the book cover packet needs it). FAIL before migration 045 is applied, and until !reloaddrops
     * or a restart after it.
     */
    static List<String> newerAreaCards() {
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        for (int[] pair : NEWER_AREA_CARDS) {
            int chance = -1;
            for (server.life.MonsterDropEntry d : server.life.MonsterInformationProvider.getInstance().retrieveDrop(pair[0])) {
                if (d.itemId == pair[1]) {
                    chance = d.chance;
                }
            }
            int mob = -1;
            try {
                mob = server.ItemInformationProvider.getInstance().getCardMobId(pair[1]);
            } catch (NullPointerException e) {
                // no monstercarddata row
            }
            ev.add("mob " + pair[0] + " card " + pair[1] + " chance=" + chance + " cardMob=" + mob);
            String why = cardVerdict(chance, mob, pair[0]);
            if (why != null) {
                bad.add("mob " + pair[0] + " card " + pair[1] + ": " + why);
            }
        }
        return verdict(bad, "the sampled Arcane River / Grandis mobs drop their card at 2% and the card maps back to the mob", ev);
    }

    /** Pure: null when the drop chance is 20000 and the card maps to the mob. */
    static String cardVerdict(int chance, int cardMob, int mob) {
        if (chance != 20000) {
            return "drop chance is " + chance + ", want 20000 (migration 045 not applied, or !reloaddrops not run)";
        }
        if (cardMob != mob) {
            return "monstercarddata maps the card to " + cardMob + ", want " + mob + " (needs a restart after 045)";
        }
        return null;
    }

    // ── t-ld-1: the 120+ training ladder (ticket 81, 1.6.1) ──────────────────────────────────────

    /** t-ld-1: maps a level-120 character may enter without rebirths (two Future, two lower Grandis). */
    static final int[] LADDER_OPEN = {271010000, 273000000, 400000000, 410000010};
    /** t-ld-1: maps that stay rebirth 3 whatever the level (Heliseum HQ, Arcane River). */
    static final int[] LADDER_CLOSED = {401000000, 450001000};
    /**
     * t-ld-1 (1.6.1 b039): the deeper Grandis maps the notes keep at 3 rebirths that the first list opened: the Fox
     * Tree, the Heliseum Hideout, Heliseum's Transitional Dimensional Door and Savage Terminal's hidden back streets.
     */
    static final int[] LADDER_DEEPER = {410000002, 400020300, 401000002, 402000400, 402000401, 402000402};
    /** t-ld-1 (1.6.1 b040): Lion King's Castle training maps open with no rebirth (lv 117 character, Hyper Rock). */
    static final int[] LADDER_LKC_OPEN = {211060000, 211060100, 211060300, 211060830, 211080100};
    /** t-ld-1 (1.6.1 b040): Von Leon's wing keeps its rebirth 1. */
    static final int LADDER_LKC_BOSS_WING = 211070000;
    /** t-ld-1 (1.6.1 b040): the Gate of Time's lower-Grandis destination. */
    static final int LADDER_GATE_GRANDIS = 400000000;
    /** t-ld-1 (owner 2026-10-29): the Gate of Time's third choice, Leafre of the Past (Crack in Time), and a field. */
    static final int LADDER_GATE_PAST = 272000000;
    static final int LADDER_PAST_FIELD = 272000100;
    /** t-ld-1: one mob per region with a stat row (Arcane, Grandis, Future, Lion King's Castle) and a buffed classic one. */
    static final int[] LADDER_MOBS = {8644001, 2400000, 8610008, 8210000, 8190003};
    /** t-ld-1: mobs that must carry a migration-050 meso row (Arcane, Grandis, Future, Lion King's Castle). */
    static final int[] LADDER_DROP_MOBS = {8644001, 2400000, 8610008, 8210000};

    /**
     * t-ld-1: a level-119 character (no rebirths) is refused at the Future and lower Grandis and a level-120 one is let
     * in, by the players' rule (AreaGates: Hyper Rock, teleporter, portals) and the bots' (BotMapGates); upper Grandis,
     * the deeper Grandis maps (Fox Tree, Heliseum, the back streets) and Arcane stay closed at level 200; Lion King's
     * Castle and the Rose Garden let a rebirth-0 character in (Von Leon's wing keeps rebirth 1) and the Rose Garden's
     * walk-in portal has its script; the Gate of Time offers lower Grandis and Leafre of the Past behind the same gate; each sampled mob loads with its mob-stat-overrides HP and EXP; the
     * sampled newer-area mobs carry migration 050's meso row and the ledger table has rows.
     */
    static List<String> trainingLadder() {
        List<String> ev = new ArrayList<>();
        List<String> bad = new ArrayList<>(ladderGates(ev, java.nio.file.Path.of("scripts")));
        for (int mob : LADDER_MOBS) {
            server.life.Monster m = server.life.LifeFactory.getMonster(mob);
            long wantHp = server.life.MobStatOverrides.maxHp(mob, -1);
            int wantExp = server.life.MobStatOverrides.exp(mob, -1);
            if (m == null) {
                bad.add("mob " + mob + " does not load");
                continue;
            }
            ev.add("mob " + mob + " hp=" + m.getMaxHp() + " (row " + wantHp + ") exp=" + m.getStats().getExp() + " (row " + wantExp + ")");
            if (wantHp < 0 && wantExp < 0) {
                bad.add("mob " + mob + " has no row in mob-stat-overrides.tsv (old jar?)");
            }
            if (wantHp >= 0 && m.getMaxHp() != wantHp) {
                bad.add("mob " + mob + " HP " + m.getMaxHp() + ", want " + wantHp);
            }
            if (wantExp >= 0 && m.getStats().getExp() != wantExp) {
                bad.add("mob " + mob + " EXP " + m.getStats().getExp() + ", want " + wantExp);
            }
        }
        for (int mob : LADDER_DROP_MOBS) {
            boolean meso = false;
            for (server.life.MonsterDropEntry d : server.life.MonsterInformationProvider.getInstance().retrieveDrop(mob)) {
                meso |= d.itemId == 0;
            }
            if (!meso) {
                bad.add("mob " + mob + " has no meso drop (migration 050 not applied, or !reloaddrops not run)");
            }
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM bb_drops_050_rows");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            int rows = rs.getInt(1);
            ev.add("bb_drops_050_rows=" + rows);
            if (rows < 5000) {
                bad.add("bb_drops_050_rows has " + rows + " rows, want the ~8,000 migration 050 inserts");
            }
        } catch (java.sql.SQLException e) {
            bad.add("bb_drops_050_rows unreadable (migration 050 not applied?): " + e.getMessage());
        }
        return verdict(bad, "lv119 refused and lv120 let in at the Future and lower Grandis (players and bots), upper "
                + "and deeper Grandis and Arcane still rebirth 3, Lion King's Castle open at rebirth 0, the Gate of Time "
                + "offers lower Grandis and Leafre of the Past, the Rose Garden walk-in works, sampled mobs carry their "
                + "HP/EXP rows and 050's drops", ev);
    }

    /** t-ld-1's gate half: no database or WZ needed (unit-tested); scripts is the server's scripts folder. */
    static List<String> ladderGates(List<String> ev, java.nio.file.Path scripts) {
        List<String> bad = new ArrayList<>();
        for (int map : LADDER_OPEN) {
            String at119 = server.maps.AreaGates.denyReason(map, 119, 0);
            String at120 = server.maps.AreaGates.denyReason(map, 120, 0);
            ev.add("map " + map + " lv119=" + (at119 == null ? "in" : "refused") + " lv120=" + (at120 == null ? "in" : "refused"));
            if (at119 == null || at120 != null) {
                bad.add("map " + map + ": want lv119 refused and lv120 in");
            }
            if (soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(map, 0, 119)
                    || !soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(map, 0, 120)) {
                bad.add("map " + map + ": bots disagree with players at 119/120");
            }
        }
        for (int[] list : new int[][]{LADDER_CLOSED, LADDER_DEEPER}) {
            for (int map : list) {
                if (server.maps.AreaGates.denyReason(map, 200, 2) == null) {
                    bad.add("map " + map + " lets a level-200, 2-rebirth character in (wants rebirth 3)");
                }
                if (soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(map, 2, 200)) {
                    bad.add("map " + map + " lets a level-200, 2-rebirth bot in (wants rebirth 3)");
                }
                if (server.maps.AreaGates.denyReason(map, 1, 3) != null) {
                    bad.add("map " + map + " refuses a 3-rebirth character");
                }
            }
        }
        ev.add("deeper Grandis closed below rebirth 3: " + java.util.Arrays.toString(LADDER_DEEPER));
        for (int map : LADDER_LKC_OPEN) {
            String rock = net.server.channel.handlers.HyperTPHandler.gateRefusal(map, 0, 117);
            if (rock != null) {
                bad.add("map " + map + " (Lion King's Castle / Rose Garden) refuses a rebirth-0 character: " + rock);
            }
            if (!soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(map, 0, 117)) {
                bad.add("map " + map + " (Lion King's Castle / Rose Garden) is closed to a rebirth-0 bot");
            }
        }
        if (net.server.channel.handlers.HyperTPHandler.gateRefusal(LADDER_LKC_BOSS_WING, 0, 200) == null) {
            bad.add("Von Leon's wing " + LADDER_LKC_BOSS_WING + " lets a rebirth-0 character in by Hyper Rock");
        }
        try {
            for (String f : List.of("portal/in_lkc.js", "portal/211060000.4.js", "portal/out_VLRoof4.js",
                    "portal/gotoNext1.js", "portal/gotoNext4.js")) {
                if (java.nio.file.Files.readString(scripts.resolve(f)).contains("getReborns")) {
                    bad.add(f + " still checks rebirths");
                }
            }
            String portal = java.nio.file.Files.readString(scripts.resolve("portal/270000000.10.js"));
            String menu = java.nio.file.Files.readString(scripts.resolve("npc/gateOfTime.js"));
            boolean offers = portal.contains("\"gateOfTime\"") && menu.contains("[" + LADDER_GATE_GRANDIS + ",")
                    && menu.contains("[271000000,") && menu.contains("[" + LADDER_GATE_PAST + ",")
                    && menu.contains("AreaGates.denyReason");
            ev.add("Gate of Time: Future + lower Grandis + Leafre of the Past menu=" + offers);
            if (!offers) {
                bad.add("the Gate of Time does not offer the Future, lower Grandis and Leafre of the Past behind AreaGates");
            }
            String rose = java.nio.file.Files.readString(scripts.resolve("portal/pt_rosegarden.js"));
            if (!rose.contains("warp(211080000")) {
                bad.add("pt_rosegarden.js does not walk into the Rose Garden");
            }
        } catch (java.io.IOException e) {
            bad.add("scripts unreadable: " + e.getMessage());
        }
        for (int map : new int[]{LADDER_GATE_PAST, LADDER_PAST_FIELD}) {
            if (server.maps.AreaGates.denyReason(map, 119, 0) == null || server.maps.AreaGates.denyReason(map, 120, 0) != null
                    || soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(map, 0, 119)
                    || !soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(map, 0, 120)) {
                bad.add("Leafre of the Past " + map + ": want lv119 refused and lv120 in, for players and bots");
            }
        }
        if (!server.maps.AreaGates.isLowerGrandis(LADDER_GATE_GRANDIS)) {
            bad.add("the Gate of Time's Grandis destination " + LADDER_GATE_GRANDIS + " is not lower Grandis");
        }
        return bad;
    }

    // ── t-iv-1: @invite, the player path (ticket 105 part 2, 1.6.1) ───────────────────────────────

    /** t-iv-1: a throwaway account id that is never a real account (real ones are positive). */
    static final int INVITE_QA_ACCOUNT = -900_101;

    /**
     * t-iv-1: the rules of {@code @invite} for a made-up account (negative id, so no real player's count or code is
     * touched; the typing GM's own path is not the player's, so the command's player entry is called directly): a
     * level 29 character is refused, level 30 gets a code, the second code works, the third in the week is refused,
     * {@code @invite list} shows two unused codes, a code signs one account up and then reads used, and a second
     * sign-up with it fails. Removes its invites and the sign-up account in a {@code finally}.
     */
    static List<String> playerInvites() throws Exception {
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        int acct = INVITE_QA_ACCOUNT;
        String user = "qaiv" + String.format(Locale.ROOT, "%06d", System.currentTimeMillis() / 1000 % 1_000_000);
        String firstCode = null;
        int signedUp = -1;
        try {
            long now = System.currentTimeMillis();
            String[] none = new String[0];
            List<String> low = client.command.commands.gm3.InviteCommand.runPlayerForQa(none, acct, 0, "AutoQaInvite", 29, now);
            ev.add("level 29: " + low);
            if (low.isEmpty() || !low.get(0).contains("level 30")) {
                bad.add("a level 29 character was not told it needs level 30: " + low);
            }
            List<String> codes = new ArrayList<>();
            for (int k = 1; k <= 3; k++) {
                List<String> r = client.command.commands.gm3.InviteCommand.runPlayerForQa(none, acct, 0, "AutoQaInvite", 30, now + k);
                ev.add("level 30, request " + k + ": " + r);
                Matcher m = Pattern.compile("Invite code: (\\S+)").matcher(r.isEmpty() ? "" : r.get(0));
                if (k <= 2) {
                    if (!m.find()) {
                        bad.add("request " + k + " at level 30 gave no code: " + r);
                    } else {
                        codes.add(m.group(1));
                    }
                } else if (m.find() || r.isEmpty() || !r.get(0).contains("2 invite codes")) {
                    bad.add("the third code in a week was not refused: " + r);
                }
            }
            List<String> list = client.command.commands.gm3.InviteCommand.runPlayerForQa(new String[]{"list"}, acct, 0, "AutoQaInvite", 30, now + 4);
            ev.add("@invite list: " + list);
            if (list.size() != 2 || list.stream().anyMatch(l -> !"unused".equals(inviteListState(l)))) {
                bad.add("@invite list should show exactly two unused codes: " + list);
            }
            if (codes.size() == 2) {
                firstCode = codes.get(0);
                String off = SiteAuthQa.notListening();
                if (off != null) {
                    ev.add("redeem part skipped: " + off);
                } else {
                    String ip = "autoqa-t-iv-1-" + now;
                    SiteAuthQa.Answer a = SiteAuthQa.call("/signup", Map.of("code", firstCode, "user", user, "pass", "Qa1!" + user.substring(user.length() - 6), "ipHash", ip));
                    ev.add("sign-up with the code: " + a.status() + " ok=" + a.ok() + (a.ok() ? "" : " " + a.error()));
                    if (!a.ok()) {
                        bad.add("a fresh @invite code did not sign anyone up: " + a.error());
                    } else {
                        signedUp = ((Number) a.body().get("account_id")).intValue();
                        SiteAuthQa.Answer again = SiteAuthQa.call("/signup", Map.of("code", firstCode, "user", user + "x", "pass", "Qa1!" + user.substring(user.length() - 6), "ipHash", ip));
                        ev.add("second sign-up with the same code: " + again.status() + " ok=" + again.ok() + (again.ok() ? "" : " " + again.error()));
                        if (again.ok()) {
                            bad.add("the same code signed up a second account");
                            SiteAuthQa.cleanup(((Number) again.body().get("account_id")).intValue(), user + "x", null);
                        }
                        List<String> after = client.command.commands.gm3.InviteCommand.runPlayerForQa(new String[]{"list"}, acct, 0, "AutoQaInvite", 30, now + 5);
                        ev.add("@invite list after use: " + after);
                        String usedProblem = inviteListUsedProblem(after, firstCode);
                        if (usedProblem != null) {
                            bad.add(usedProblem + ": " + after);
                        }
                    }
                }
            }
        } finally {
            int gone = SiteAuthQa.cleanupInvitesMadeBy(acct);
            if (signedUp > 0) {
                gone += SiteAuthQa.cleanup(signedUp, user, null);
            }
            ev.add("cleanup removed " + gone + " rows");
        }
        return verdict(bad, "@invite refuses level 29, gives two codes at level 30, refuses the third, lists them, and a code works exactly once", ev);
    }

    // ── t-iv-2: the site invite page's routes (ticket 105, "Site invite page", 1.6.1) ──────────────

    /** t-iv-2: made-up accounts (negative ids, never real): a level 40 player, a level 29 one, a banned one, a GM. */
    static final int SITE_IV_PLAYER = -900_201, SITE_IV_LOW = -900_202, SITE_IV_BANNED = -900_203, SITE_IV_GM = -900_204;

    /**
     * t-iv-2: {@code /invite/create}, {@code /invite/list} and {@code /invite/cancel} for made-up accounts against
     * the real invite table: the running API has the routes and refuses an unknown account; a player's code made with
     * {@code @invite} counts on the site (one more, then WEEKLY_LIMIT); the player list shows only their two codes,
     * never who used them; a player can't cancel the GM's code (NOT_FOUND) but can cancel their own, which still
     * counts; level 29 is NOT_ELIGIBLE and a banned account REFUSED; a GM sets the life and a note, sees the codes
     * with their owner, and cancels the player's other code. Removes every code those ids made in a {@code finally}.
     */
    static List<String> siteInvites() throws Exception {
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        int[] ids = {SITE_IV_PLAYER, SITE_IV_LOW, SITE_IV_BANNED, SITE_IV_GM};
        List<SiteAuthQa.FakeAccount> fakes = List.of(
                new SiteAuthQa.FakeAccount(SITE_IV_PLAYER, "qaivplayer", 0, 40, false),
                new SiteAuthQa.FakeAccount(SITE_IV_LOW, "qaivlow", 0, 29, false),
                new SiteAuthQa.FakeAccount(SITE_IV_BANNED, "qaivbanned", 0, 90, true),
                new SiteAuthQa.FakeAccount(SITE_IV_GM, "qaivgm", 3, 1, false));
        try {
            for (int id : ids) {
                SiteAuthQa.cleanupInvitesMadeBy(id);   // a crashed earlier run's leftovers would use up the week
            }
            long now = System.currentTimeMillis();
            String off = SiteAuthQa.notListening();
            if (off != null) {
                ev.add("running API part skipped: " + off);
            } else {
                SiteAuthQa.Answer live = SiteAuthQa.call("/invite/list", Map.of("account_id", SITE_IV_PLAYER));
                ev.add("running API /invite/list for an unknown account: " + live.status() + " " + live.body());
                if (!"REFUSED".equals(live.error())) {
                    bad.add("the running API's /invite/list did not refuse an unknown account (are the routes deployed?): " + live.body());
                }
            }
            List<String> game = client.command.commands.gm3.InviteCommand.runPlayerForQa(new String[0], SITE_IV_PLAYER, 0, "qaivplayer", 40, now);
            ev.add("@invite in game: " + game);
            SiteAuthQa.Answer c1 = SiteAuthQa.callInvitesAs(fakes, "/invite/create", Map.of("account_id", SITE_IV_PLAYER, "life", 1, "note", "x"));
            ev.add("player create on the site: " + c1.body());
            if (!c1.ok() || c1.body().get("expiresAt") != null || !Integer.valueOf(0).equals(c1.body().get("left"))) {
                bad.add("the player's site code should never expire and leave 0 this week after one in game: " + c1.body());
            }
            String playerCode = c1.ok() ? (String) c1.body().get("code") : null;
            SiteAuthQa.Answer c2 = SiteAuthQa.callInvitesAs(fakes, "/invite/create", Map.of("account_id", SITE_IV_PLAYER));
            ev.add("player's third code: " + c2.body());
            if (!"WEEKLY_LIMIT".equals(c2.error())) {
                bad.add("the third code in a week (one in game, one on the site) was not WEEKLY_LIMIT: " + c2.body());
            }
            SiteAuthQa.Answer low = SiteAuthQa.callInvitesAs(fakes, "/invite/create", Map.of("account_id", SITE_IV_LOW));
            SiteAuthQa.Answer ban = SiteAuthQa.callInvitesAs(fakes, "/invite/create", Map.of("account_id", SITE_IV_BANNED));
            ev.add("level 29: " + low.body() + "; banned: " + ban.body());
            if (!"NOT_ELIGIBLE".equals(low.error())) {
                bad.add("a level 29 account was not NOT_ELIGIBLE: " + low.body());
            }
            if (!"REFUSED".equals(ban.error())) {
                bad.add("a banned account was not REFUSED: " + ban.body());
            }
            SiteAuthQa.Answer g = SiteAuthQa.callInvitesAs(fakes, "/invite/create", Map.of("account_id", SITE_IV_GM, "life", 3, "note", "autoqa t-iv-2"));
            ev.add("GM create: " + g.body());
            String gmCode = g.ok() ? (String) g.body().get("code") : null;
            Object exp = g.body().get("expiresAt");
            long want = (now + 3 * 86_400_000L) / 1000;
            if (gmCode == null || !(exp instanceof Number n) || Math.abs(n.longValue() - want) > 120) {
                bad.add("the GM's 3-day code should expire about 3 days out (unix seconds " + want + "): " + g.body());
            }
            SiteAuthQa.Answer pl = SiteAuthQa.callInvitesAs(fakes, "/invite/list", Map.of("account_id", SITE_IV_PLAYER));
            ev.add("player list: " + pl.body());
            List<?> rows = pl.ok() ? (List<?>) pl.body().get("codes") : List.of();
            if (rows.size() != 2 || !Integer.valueOf(0).equals(pl.body().get("left"))
                    || rows.stream().anyMatch(r -> ((Map<?, ?>) r).containsKey("owner") || ((Map<?, ?>) r).containsKey("note")
                    || !(((Map<?, ?>) r).get("usedBy") instanceof Boolean))) {
                bad.add("the player list should show exactly their 2 codes, left 0, no owner/note, usedBy a boolean: " + pl.body());
            }
            if (gmCode != null) {
                SiteAuthQa.Answer steal = SiteAuthQa.callInvitesAs(fakes, "/invite/cancel", Map.of("account_id", SITE_IV_PLAYER, "code", gmCode));
                ev.add("player cancels the GM's code: " + steal.body());
                if (!"NOT_FOUND".equals(steal.error())) {
                    bad.add("a player could see or cancel someone else's code: " + steal.body());
                }
            }
            if (playerCode != null) {
                SiteAuthQa.Answer cx = SiteAuthQa.callInvitesAs(fakes, "/invite/cancel", Map.of("account_id", SITE_IV_PLAYER, "code", soloMapling.site.auth.InviteCodes.normalize(playerCode)));
                SiteAuthQa.Answer again = SiteAuthQa.callInvitesAs(fakes, "/invite/create", Map.of("account_id", SITE_IV_PLAYER));
                ev.add("player cancels own code: " + cx.body() + "; then creates: " + again.body());
                if (!"CANCELLED".equals(cx.body().get("result"))) {
                    bad.add("a player could not cancel their own unused code: " + cx.body());
                }
                if (!"WEEKLY_LIMIT".equals(again.error())) {
                    bad.add("a cancelled code stopped counting toward the week: " + again.body());
                }
            }
            SiteAuthQa.Answer gl = SiteAuthQa.callInvitesAs(fakes, "/invite/list", Map.of("account_id", SITE_IV_GM, "page", 1, "limit", 100));
            List<?> all = gl.ok() ? (List<?>) gl.body().get("codes") : List.of();
            Map<?, ?> mine = all.stream().map(r -> (Map<?, ?>) r).filter(r -> gmCode != null && gmCode.equals(r.get("code"))).findFirst().orElse(null);
            ev.add("GM list: total " + gl.body().get("total") + ", pages " + gl.body().get("pages") + ", own row " + mine);
            if (mine == null || !"qaivgm".equals(mine.get("owner")) || !"autoqa t-iv-2".equals(mine.get("note")) || !"open".equals(mine.get("status"))) {
                bad.add("the GM list should show the GM's new code open, with owner and note: " + mine);
            }
            String gameCode = all.stream().map(r -> (Map<?, ?>) r)
                    .filter(r -> "qaivplayer".equals(r.get("owner")) && "open".equals(r.get("status")))
                    .map(r -> (String) r.get("code")).findFirst().orElse(null);
            if (gameCode == null) {
                bad.add("the GM list does not show the player's open in-game code");
            } else {
                SiteAuthQa.Answer gx = SiteAuthQa.callInvitesAs(fakes, "/invite/cancel", Map.of("account_id", SITE_IV_GM, "code", gameCode));
                ev.add("GM cancels the player's in-game code: " + gx.body());
                if (!"CANCELLED".equals(gx.body().get("result"))) {
                    bad.add("a GM could not cancel a player's unused code: " + gx.body());
                }
            }
        } finally {
            int gone = 0;
            for (int id : ids) {
                gone += SiteAuthQa.cleanupInvitesMadeBy(id);
            }
            ev.add("cleanup removed " + gone + " rows");
        }
        return verdict(bad, "site invite routes: player and GM rules, the week shared with @invite, list privacy, cancel rules", ev);
    }

    // ── t-br-1: @bug with its automatic context (ticket 131) ─────────────────────────────────────

    /**
     * t-br-1: the pretend player files {@code @bug}; the reply carries a ref, the reports file has the line (source
     * ingame, ref, the text, a context summary pointing at its file), and the context file has the versions, the
     * character, the trail and the server's WARN line about this character, but not a WARN line that mentions a
     * password, and fits in 64 KB. The line and the file are removed in a {@code finally} (only that ref).
     */
    static List<String> bugReport(SyntheticPlayer p) throws Exception {
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        Character me = p.character();
        server.reports.ReportStore store = server.reports.ReportStore.getInstance();
        store.resetLimitsForQa(me.getAccountID());
        server.reports.WarnLogRing.install();
        org.slf4j.Logger qaLog = org.slf4j.LoggerFactory.getLogger("soloMapling.autoqa.BugReportQa");
        String marker = "tbr1-" + System.currentTimeMillis();
        qaLog.warn("auto-QA t-br-1: staged warning {} for chr {}", marker, me.getName());
        qaLog.warn("auto-QA t-br-1: {} password check for {} (must never reach a report)", marker, me.getName());
        String text = "autoqa t-br-1 " + marker + " the door does not open";
        long since = System.currentTimeMillis();
        List<AutoQaLine> before = p.heard(since);
        p.say("@bug " + text);
        Pattern thanks = Pattern.compile("Thanks! Bug #(B-\\d+) sent");
        String ref = null;
        for (long end = since + 15_000; ref == null && System.currentTimeMillis() < end; ) {
            for (AutoQaLine l : p.heard(since)) {
                Matcher m = thanks.matcher(l.message());
                if (!before.contains(l) && m.find()) {
                    ref = m.group(1);
                    ev.add("reply: " + l.message());
                    break;
                }
            }
            if (ref == null) {
                Thread.sleep(200);
            }
        }
        if (ref == null) {
            return verdict(List.of("no 'Thanks! Bug #B-.. sent.' reply within 15 s"), "", ev);
        }
        try {
            Map<String, Object> line = store.find(ref);
            ev.add("reports file " + store.file().toAbsolutePath() + ": " + (line == null ? "no line" : "line " + line.get("id")));
            if (line == null) {
                bad.add("no line with ref " + ref + " in " + store.file());
            } else {
                if (!"ingame".equals(line.get("source")) || !"bug".equals(line.get("type"))) {
                    bad.add("source/type are " + line.get("source") + "/" + line.get("type"));
                }
                if (!String.valueOf(line.get("details")).equals(text) || !String.valueOf(line.get("title")).startsWith("autoqa t-br-1")) {
                    bad.add("details/title do not carry the text");
                }
                if (!me.getName().equals(line.get("character"))) {
                    bad.add("character is " + line.get("character"));
                }
                if (!(line.get("context") instanceof Map<?, ?> sum) || !("context/" + ref + ".json").equals(sum.get("file"))) {
                    bad.add("the line has no context summary pointing at context/" + ref + ".json");
                }
            }
            Map<String, Object> ctx = store.readContext(ref);
            if (ctx == null) {
                bad.add("no context file " + store.contextDir().resolve(ref + ".json"));
            } else {
                int bytes = server.reports.BugContext.bytes(ctx);
                ev.add("context: " + bytes + " bytes, keys " + ctx.keySet());
                if (bytes > server.reports.BugContext.MAX_BYTES) {
                    bad.add("context is " + bytes + " bytes, over the cap");
                }
                if (!(ctx.get("versions") instanceof Map<?, ?> v) || !constants.net.ServerConstants.SLUMSMS_VERSION.equals(v.get("server"))) {
                    bad.add("versions.server is not " + constants.net.ServerConstants.SLUMSMS_VERSION);
                }
                if (!(ctx.get("character") instanceof Map<?, ?> ch) || !me.getName().equals(ch.get("name"))) {
                    bad.add("character section missing or not " + me.getName());
                }
                for (String k : List.of("trail", "surroundings", "quests", "buffs")) {
                    if (!ctx.containsKey(k) || (ctx.get(k) instanceof Map<?, ?> m && m.containsKey("error"))) {
                        bad.add(k + " section missing or failed: " + ctx.get(k));
                    }
                }
                String logs = String.valueOf(ctx.get("serverLog"));
                if (!logs.contains("staged warning " + marker)) {
                    bad.add("the staged WARN line about " + me.getName() + " is not in serverLog");
                }
                if (logs.contains("password")) {
                    bad.add("a WARN line mentioning a password reached the report");
                }
            }
        } finally {
            int gone = store.removeForQa(ref).get(10, java.util.concurrent.TimeUnit.SECONDS);
            ev.add("cleanup removed " + gone + " line(s) and the context file");
            if (store.find(ref) != null || store.readContext(ref) != null) {
                bad.add("cleanup left " + ref + " behind");
            }
            store.resetLimitsForQa(me.getAccountID());
        }
        return verdict(bad, "@bug replied " + ref + ", wrote the line and a capped context with versions, trail and the staged warning (no password line), and cleanup removed it", ev);
    }

    /** t-iv-1: the state word(s) after the code on one {@code @invite list} line ("CODE  used" gives "used"). */
    static String inviteListState(String line) {
        int gap = line.indexOf("  ");
        return gap < 0 ? "" : line.substring(gap).trim();
    }

    /**
     * t-iv-1: null when exactly one line of an {@code @invite list} reads "used" and it is {@code usedCode}, in any
     * order (the list is newest first, so the used first code is usually last). Matches the state word exactly:
     * "unused" also ends in "used", which is what made the old suffix check fail.
     */
    static String inviteListUsedProblem(List<String> lines, String usedCode) {
        List<String> used = lines.stream().filter(l -> "used".equals(inviteListState(l))).toList();
        if (used.size() != 1) {
            return "@invite list should show exactly one code as used, saw " + used.size();
        }
        if (usedCode != null && !used.get(0).startsWith(usedCode + "  ")) {
            return "@invite list shows " + used.get(0) + " as used, expected " + usedCode;
        }
        return null;
    }

    // ── t-sn-1: Slums-chan (1.6.1, migration 042) ─────────────────────────────────────────────────

    /**
     * t-sn-1 (read-only): NPC 9990000 has her name in String.wz, stands on Base of Roots (993910000) and Henesys
     * (100000000) as loaded from plife, and the first-login guide and settings are framed by her.
     */
    static List<String> slumsChan(SyntheticPlayer p) {
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        int id = soloMapling.menus.SlumsChanMenu.NPC;
        String name = server.life.LifeFactory.getNPC(id).getName();
        ev.add("npc " + id + " name: " + name);
        if (name == null || name.isBlank() || "MISSINGNO".equals(name)) {
            bad.add("npc " + id + " has no name in String.wz (host step: tools/data-align/add_slums_chan.py)");
        }
        var maps = p.character().getClient().getChannelServer().getMapFactory();
        for (int mapId : new int[]{993910000, 100000000}) {
            var map = maps.getMap(mapId);
            long here = map == null ? 0 : map.getMapObjects().stream()
                    .filter(o -> o.getType() == server.maps.MapObjectType.NPC && ((server.life.NPC) o).getId() == id).count();
            ev.add("map " + mapId + ": " + here + " Slums-chan");
            if (here != 1) {
                bad.add("map " + mapId + " has " + here + " Slums-chan, want 1 (plife row, migration 042)");
            }
        }
        if (soloMapling.menus.GuideMenu.NPC != id || soloMapling.menus.SettingsMenu.NPC != id) {
            bad.add("the first-login guide or @settings is not framed by her (GuideMenu.NPC " + soloMapling.menus.GuideMenu.NPC
                    + ", SettingsMenu.NPC " + soloMapling.menus.SettingsMenu.NPC + ")");
        }
        try {
            var page = soloMapling.menus.SlumsChanMenu.page(p.character());
            ev.add("her welcome offers: " + page.labels());
            page.find("guide");
            page.find("settings");
        } catch (RuntimeException e) {
            bad.add("her welcome page lacks the guide or settings line: " + e.getMessage());
        }
        return verdict(bad, "Slums-chan has her name, stands on Base of Roots and Henesys, and frames the first-login guide", ev);
    }

    // ── t-bt-1 / t-bt-2: bot-to-bot talk and friend crews (ticket 114) ───────────────────────────

    /** t-bt-1: the most a tick of the talk engine may cost on average (ms), at TEST's bot count. */
    static final double TALK_TICK_MS_MAX = 20;
    /**
     * t-bt-1: after this many ticks of the engine (10 s each, so 5 minutes) with at least {@link #ORGANIC_MIN_BOTS}
     * bots online, the tick must have started at least one greeting or chat by itself (not QA's forced talks).
     */
    static final int ORGANIC_TICKS = 30, ORGANIC_MIN_BOTS = 20;

    /**
     * t-bt-1: the tick's average cost (ms). The tick thread's CPU time when measured (wall time includes waiting for a
     * CPU under a throttled cgroup, which is not our code's cost; TEST 2026-10-28: 24.8 ms wall on 3 loaded CPUs);
     * the wall time when the CPU time was not available (0 CPU ns with ticks run).
     */
    static double tickCostMs(long ticks, long threadCpuNanos, long wallNanos) {
        if (ticks <= 0) {
            return 0;
        }
        return (threadCpuNanos > 0 ? threadCpuNanos : wallNanos) / 1e6 / ticks;
    }

    /** t-bt-1 (4): the organic verdict, or null when it passes or it is too early to judge (pure, unit-tested). */
    static String organicTalkProblem(long ticks, int onlineBots, long organicTalks) {
        if (ticks < ORGANIC_TICKS || onlineBots < ORGANIC_MIN_BOTS || organicTalks > 0) {
            return null;
        }
        return "the engine ran " + ticks + " ticks (" + ticks * 10 / 60 + " min) with " + onlineBots
                + " bots online and started no greeting or chat by itself";
    }

    /**
     * t-bt-1: five fresh bots next to the pretend player. (1) Ann greets her friend Bob, chats and asks to party; Bob
     * answers from his goals, mood and bond, every line goes out with its intent, and a yes is a real party (a no is
     * none). (2) Cat, Dan and Eve talk as a group: three speakers, turns, each answers the ask itself. (3) No bot line
     * reached the classifier, and the engine's tick stays cheap.
     */
    static List<String> botTalk(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotConvo.BotTalk.on()) {
            return List.of("SKIP bot talk is off (-Dslums.bottalk=true)");
        }
        Character me = p.character();
        List<Character> bots = soloMapling.ArtificialPlayer.BotConvo.TalkQa.spawn(me, 5, false,
                Math.max(10, Math.min(120, me.getLevel())));
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        try {
            if (bots.size() < 5) {
                return List.of("FAIL could not spawn five bots (" + bots.size() + ")");
            }
            soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.stage(bots.get(0).getId(), bots.get(1).getId(),
                    soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.FRIEND);
            soloMapling.ArtificialPlayer.BotConvo.TalkQa.friends(bots.subList(2, 5));
            long classified0 = soloMapling.ArtificialPlayer.BotConvo.BotLineIntents.counts().get("classifiedBotLines");
            long since = System.currentTimeMillis();
            // 1.6.1 audit: Bob's yes is forced (when the game allows one), so the party itself is proven every run
            var pair = soloMapling.ArtificialPlayer.BotConvo.TalkQa.talkYes(List.of(bots.get(0), bots.get(1)),
                    soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask.PARTY, "auto-QA t-bt-1 pair");
            var group = soloMapling.ArtificialPlayer.BotConvo.TalkQa.talk(bots.subList(2, 5), null,
                    "auto-QA t-bt-1 group");
            ev.addAll(soloMapling.ArtificialPlayer.BotConvo.TalkQa.describe("pair", pair));
            ev.addAll(soloMapling.ArtificialPlayer.BotConvo.TalkQa.describe("group", group));
            if (pair == null || group == null) {
                bad.add("a talk did not start");
                return verdict(bad, "", ev);
            }
            if (pair.ask == null) {
                bad.add("the pair asked nothing: " + pair.noAskWhy);
            }
            if (pair.decisions.isEmpty()) {
                bad.add("Bob decided nothing");
            }
            if (!group.group || group.who.size() != 3) {
                bad.add("the group talk was not a group of three");
            }
            // judged once both talks said their last line and did their act (TEST 2026-10-28: a fixed wait judged the
            // group's party before it was formed)
            if (!soloMapling.ArtificialPlayer.BotConvo.TalkQa.awaitDone(List.of(pair, group), 120_000)) {
                bad.add("a talk did not finish within 120 s");
            }
            for (String b : soloMapling.ArtificialPlayer.BotConvo.TalkQa.judge(pair, since)) {
                bad.add("pair: " + b);
            }
            for (String b : soloMapling.ArtificialPlayer.BotConvo.TalkQa.partyFormed(pair)) {
                bad.add("pair party: " + b);
            }
            if (bots.get(0).getParty() != null) {
                ev.add("pair party " + bots.get(0).getParty().getId() + ": " + bots.get(0).getParty().getMembers().size()
                        + " members");
            }
            for (String b : soloMapling.ArtificialPlayer.BotConvo.TalkQa.judge(group, since)) {
                bad.add("group: " + b);
            }
            long classified = soloMapling.ArtificialPlayer.BotConvo.BotLineIntents.counts().get("classifiedBotLines")
                    - classified0;
            if (classified != 0) {
                bad.add(classified + " bot line(s) went to the classifier");
            }
            Map<String, Object> st = soloMapling.ArtificialPlayer.BotConvo.BotTalk.stats();
            long ticks = ((Number) st.get("ticks")).longValue();
            double wallPerTick = ticks == 0 ? 0 : ((Number) st.get("cpuNanos")).longValue() / 1e6 / ticks;
            double perTick = tickCostMs(ticks, ((Number) st.get("threadCpuNanos")).longValue(),
                    ((Number) st.get("cpuNanos")).longValue());
            ev.add(String.format(Locale.ROOT, "engine: %.2f ms CPU a tick (%.2f ms wall) over %d ticks, %d online bots; %s",
                    perTick, wallPerTick, ticks,
                    soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().size(),
                    soloMapling.ArtificialPlayer.BotConvo.TalkQa.stats()));
            if (perTick > TALK_TICK_MS_MAX) {
                bad.add(String.format(Locale.ROOT, "a talk tick costs %.1f ms on average (max %.0f)", perTick,
                        TALK_TICK_MS_MAX));
            }
            // (4) the decision tree runs on its own: bots on a map greet / talk within the first minutes, no QA push
            int online = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().size();
            long organic = ((Number) st.getOrDefault("organicTalks", 0L)).longValue();
            String organicBad = organicTalkProblem(ticks, online, organic);
            if (organicBad != null) {
                bad.add(organicBad);
            } else {
                ev.add("organic talks so far (greetings and chats the tick started): " + organic
                        + (ticks < ORGANIC_TICKS ? " (engine too young to judge: " + ticks + " ticks)" : ""));
            }
        } finally {
            ev.add(soloMapling.ArtificialPlayer.BotConvo.TalkQa.teardown(bots));
        }
        return verdict(bad, "a pair greeted, chatted, asked to party, Bob's (forced) yes formed a real party; a group"
                + " of three took turns and each answered; no bot line was classified; the tick is cheap and starts"
                + " talks by itself", ev);
    }

    /**
     * t-bt-2: three fresh training bots made a crew (mutual friends). Once their lives are free to move (up to
     * {@link #FREE_WAIT_S} s), the leader (lowest id) starts a hang-out in a town through its own life, as its brain
     * does: that town is the crew's pick, each follower's next hang-out, and each follower is handed the trip a few
     * seconds apart. 1.6.1 say-do: every member (not one, not the leader alone) must stand in the town within
     * {@link #CREW_WAIT_S} s (TEST 2026-10-29: 1 of 2 followers arrived and the check passed).
     */
    static List<String> botCrew(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews.on()) {
            return List.of("SKIP friend crews are off (-Dslums.crews=true)");
        }
        if (!LifeRuntime.enabled()) {
            return List.of("SKIP the daily-life brain is off (-Dslums.botlife.enabled/execute)");
        }
        Character me = p.character();
        int level = Math.max(15, Math.min(60, me.getLevel()));
        List<Character> crew = soloMapling.ArtificialPlayer.BotConvo.TalkQa.spawn(me, 3, true, level);
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        try {
            if (crew.size() < 3) {
                return List.of("FAIL could not spawn three training bots (" + crew.size() + ")");
            }
            soloMapling.ArtificialPlayer.BotConvo.TalkQa.friends(crew);
            int town = soloMapling.ArtificialPlayer.BotConvo.TalkQa.crewTown(me.getMapId(), level);
            Character leader = crewLeader(crew);
            List<String> held = soloMapling.ArtificialPlayer.BotConvo.TalkQa.awaitFree(crew, FREE_WAIT_S * 1000L);
            ev.add("crew " + soloMapling.ArtificialPlayer.BotConvo.TalkQa.where(crew) + " -> town " + town
                    + ", leader " + leader.getName() + (held.isEmpty() ? "" : "; still held: " + held));
            if (!held.isEmpty()) {
                bad.add("the crew was never free to move in " + FREE_WAIT_S + " s: " + held);
                return verdict(bad, "", ev);
            }
            if (!soloMapling.ArtificialPlayer.BotConvo.TalkQa.crewLeads(leader, town)) {
                bad.add("the leader's hang-out in " + town + " was not handed to its life");
            }
            // the pick is made when the leader's trip starts (its next update); then each follower heads there
            long pickBy = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < pickBy && soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews
                    .pickFor(leader.getId(), leader.getMap() == null ? 1 : leader.getMap().getChannelId(),
                            System.currentTimeMillis()) != town) {
                Thread.sleep(1_000);
            }
            for (Character c : crew) {
                if (c == leader) {
                    continue;
                }
                LifeRuntime life = soloMapling.ArtificialPlayer.BotConvo.TalkQa.lifeOf(c);
                int dest = life == null ? -1 : life.townDestination();
                if (dest != town && c.getMapId() != town) {
                    bad.add(c.getName() + "'s next hang-out is " + dest + ", not the crew's " + town);
                }
            }
            List<Character> missing = soloMapling.ArtificialPlayer.BotConvo.TalkQa.awaitIn(crew, town,
                    CREW_WAIT_S * 1000L);
            ev.add("after the wait: " + soloMapling.ArtificialPlayer.BotConvo.TalkQa.where(crew) + "; crews "
                    + soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews.stats());
            for (Character c : missing) {
                bad.add((c == leader ? "the leader " : "follower ") + c.getName() + " never reached town " + town
                        + " in " + CREW_WAIT_S + " s (on " + c.getMapId() + ")");
            }
        } finally {
            ev.add(soloMapling.ArtificialPlayer.BotConvo.TalkQa.teardown(crew));
        }
        return verdict(bad, "the leader started its hang-out, the town became the crew's pick and every follower"
                + " arrived there", ev);
    }

    /** t-bt-2 / t-bt-3: how long fresh training bots get to settle (out of their first trip) before the check. */
    static final int FREE_WAIT_S = 90;

    /**
     * t-bt-3 (1.6.1 say-do: "then they really do it"): (1) two fresh training bots, once free to move, talk; the opener
     * asks the other to come along to a town and the other's yes is forced (where the game allows one): both must
     * stand in that town within {@link #SAYDO_TRIP_S} s. (2) The talk ledger since boot: of the yeses bots gave each
     * other (party, come-along, help, trade, story), at least {@link soloMapling.ArtificialPlayer.BotConvo.TalkPromises#MIN_RATE}
     * were carried out in their window, once {@link #SAYDO_MIN_YES} are judged.
     */
    static List<String> botSayDo(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotConvo.BotTalk.on()) {
            return List.of("SKIP bot talk is off (-Dslums.bottalk=true)");
        }
        if (!LifeRuntime.enabled()) {
            return List.of("SKIP the daily-life brain is off (-Dslums.botlife.enabled/execute)");
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        // (2) first, so a slow staging run cannot hide it
        Map<String, Object> promises = soloMapling.ArtificialPlayer.BotConvo.TalkPromises.stats();
        String ledger = sayDoLedgerProblem(promises);
        ev.add("ledger since boot: " + promises);
        if (ledger != null) {
            bad.add(ledger);
        }
        Character me = p.character();
        int level = Math.max(15, Math.min(60, me.getLevel()));
        List<Character> two = soloMapling.ArtificialPlayer.BotConvo.TalkQa.spawn(me, 2, true, level);
        try {
            if (two.size() < 2) {
                return List.of("FAIL could not spawn two training bots (" + two.size() + ")");
            }
            soloMapling.ArtificialPlayer.BotConvo.TalkQa.friends(two);
            List<String> held = soloMapling.ArtificialPlayer.BotConvo.TalkQa.awaitFree(two, FREE_WAIT_S * 1000L);
            if (!held.isEmpty()) {
                bad.add("the two were never free to move in " + FREE_WAIT_S + " s: " + held);
                return verdict(bad, "", ev);
            }
            int town = soloMapling.ArtificialPlayer.BotConvo.TalkQa.crewTown(two.get(0).getMapId(), level);
            if (!soloMapling.ArtificialPlayer.BotConvo.TalkQa.aimAt(two.get(0), town)) {
                bad.add("could not point " + two.get(0).getName() + "'s hang-out at " + town);
                return verdict(bad, "", ev);
            }
            var talk = soloMapling.ArtificialPlayer.BotConvo.TalkQa.talkYes(two,
                    soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask.COME_ALONG, "auto-QA t-bt-3 come-along");
            ev.addAll(soloMapling.ArtificialPlayer.BotConvo.TalkQa.describe("talk", talk));
            if (talk == null || talk.ask != soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask.COME_ALONG) {
                bad.add("no come-along was asked (" + (talk == null ? "no talk" : talk.noAskWhy) + ")");
                return verdict(bad, "", ev);
            }
            var d = talk.decisions.get(two.get(1).getId());
            if (d == null || !d.yes()) {
                bad.add(two.get(1).getName() + " did not say yes (" + (d == null ? "no decision" : d.why()) + ")");
                return verdict(bad, "", ev);
            }
            if (!soloMapling.ArtificialPlayer.BotConvo.TalkQa.awaitDone(List.of(talk), 120_000)) {
                bad.add("the talk did not finish within 120 s");
            }
            List<Character> missing = soloMapling.ArtificialPlayer.BotConvo.TalkQa.awaitIn(two, town,
                    SAYDO_TRIP_S * 1000L);
            ev.add("after the wait: " + soloMapling.ArtificialPlayer.BotConvo.TalkQa.where(two) + "; "
                    + soloMapling.ArtificialPlayer.BotConvo.BotTalk.stats().get("promises"));
            for (Character c : missing) {
                bad.add(c.getName() + (c == two.get(0) ? " asked" : " said yes") + " but never reached town " + town
                        + " in " + SAYDO_TRIP_S + " s (on " + c.getMapId() + ")");
            }
        } finally {
            ev.add(soloMapling.ArtificialPlayer.BotConvo.TalkQa.teardown(two));
        }
        return verdict(bad, "a forced come-along yes took both bots to the town, and the talk ledger's yeses are"
                + " carried out", ev);
    }

    static final int SAYDO_TRIP_S = 240, SAYDO_MIN_YES = 10;

    /** t-bt-3 (2): the ledger's verdict, or null when it passes or too few yeses are judged yet (pure, unit-tested). */
    @SuppressWarnings("unchecked")
    static String sayDoLedgerProblem(Map<String, Object> promises) {
        long yes = ((Number) promises.getOrDefault("yes", 0L)).longValue();
        Object rate = promises.get("rate");
        if (yes < SAYDO_MIN_YES || !(rate instanceof Number r)
                || r.doubleValue() >= soloMapling.ArtificialPlayer.BotConvo.TalkPromises.MIN_RATE) {
            return null;
        }
        return String.format(Locale.ROOT, "only %.0f%% of %d yeses were carried out (min %.0f%%): %s; latest misses %s",
                r.doubleValue() * 100, yes, soloMapling.ArtificialPlayer.BotConvo.TalkPromises.MIN_RATE * 100,
                promises.get("byAsk"), promises.get("unbacked"));
    }

    /** t-bt-2: the crew's leader, as BotCrews picks it (the lowest id). */
    static Character crewLeader(List<Character> crew) {
        Character leader = crew.get(0);
        for (Character c : crew) {
            if (c.getId() < leader.getId()) {
                leader = c;
            }
        }
        return leader;
    }

    /** t-bt-2: followers (not the leader) standing in {@code town} (pure, unit-tested). */
    static int followersIn(List<Character> crew, Character leader, int town) {
        int n = 0;
        for (Character c : crew) {
            if (c != leader && c.getMapId() == town) {
                n++;
            }
        }
        return n;
    }

    /** t-bt-2: how long the crew gets to start moving. */
    static final int CREW_WAIT_S = 240;

    // ── t-xc-1: the NX exchange in game (ticket 113 round 2) ─────────────────────────────────────

    /**
     * t-xc-1, while logged in: (1) a test regular funded from the pretend player's own mesos bids for 100 NX; (2)
     * {@code @nx sell 100 <bid>} + {@code @nx confirm} fills against it, and the mesos (less the 1% fee) land on the
     * live character at once, no logout; the 100 NX left the live account; (3) {@code @nx buy 100 1000} + confirm
     * rests and holds its mesos, {@code @nx cancel} gives them back exactly; (4) the regular is taken out, its house
     * paid back. Net: NX unchanged, mesos down by exactly the two fees. An NX top-up for the sale is taken back.
     */
    static List<String> nxExchange(SyntheticPlayer p) throws Exception {
        soloMapling.Economy.exchange.Exchange ex = soloMapling.Economy.exchange.ExchangeService.exchange();
        if (ex == null) {
            return List.of("SKIP the NX exchange is off (-Dslums.exchange=true)");
        }
        if (ex.halted()) {
            return List.of("FAIL the NX exchange is halted: " + ex.haltReason());
        }
        Character me = p.character();
        soloMapling.Economy.exchange.LiveWallet w = soloMapling.Economy.exchange.GameLiveWallet.of(me);
        if (w == null || !w.locked(w::usable)) {
            return List.of("FAIL the pretend player has no usable live wallet");
        }
        var cfg = ex.config();
        long nxTop = 0;
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        long nx0 = w.have(soloMapling.Economy.exchange.Ledger.Asset.NX);
        if (nx0 < soloMapling.Economy.exchange.ExchangeQa.NX) {
            nxTop = soloMapling.Economy.exchange.ExchangeQa.NX - nx0;
            me.getCashShop().gainCash(1, (int) nxTop);
            out.add("topped up " + nxTop + " NX for the sale (taken back at the end)");
            nx0 += nxTop;
        }
        long meso0 = me.getMeso();
        out.add("before: " + nx0 + " NX, " + meso0 + " mesos");
        soloMapling.Economy.exchange.ExchangeQa.Staged st;
        try {
            st = soloMapling.Economy.exchange.ExchangeQa.stage(ex, w);
        } catch (IllegalStateException e) {
            if (nxTop > 0) {
                me.getCashShop().takeNxCredit((int) nxTop);
            }
            return List.of("FAIL staging: " + e.getMessage());
        }
        long bid = st.bidPrice();
        out.add("test regular bids " + bid + " for 100 NX (order " + st.bidOrder() + "), funded " + st.funded());
        try {
            // (2) sell in game: fills against the regular, mesos land online
            String pv = sayAndHear(p, "@nx sell 100 " + bid, "^Preview: sell|\\([A-Z_]+\\)$", 8_000);
            out.add("@nx sell: " + pv);
            long mesoBefore = me.getMeso();
            String placed = sayAndHear(p, "@nx confirm", "^Order \\d+ placed|\\([A-Z_]+\\)$", 8_000);
            out.add("@nx confirm: " + placed);
            long gets = soloMapling.Economy.exchange.ExchangeQa.sellerGets(cfg, 100, bid);
            long deadline = System.currentTimeMillis() + 5_000;
            while (me.getMeso() - mesoBefore < gets && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            long nx1 = w.have(soloMapling.Economy.exchange.Ledger.Asset.NX);
            out.add("after the sale: " + nx1 + " NX, mesos +" + (me.getMeso() - mesoBefore) + " (want +" + gets + ")");
            if (placed == null || !placed.startsWith("Order ")) {
                bad.add("the sale was not placed: " + placed);
            }
            if (me.getMeso() - mesoBefore != gets) {
                bad.add("the payout did not land online: mesos +" + (me.getMeso() - mesoBefore) + ", want +" + gets);
            }
            if (nx0 - nx1 != 100) {
                bad.add("the sale took " + (nx0 - nx1) + " NX from the live account, want 100");
            }
            // (3) a resting buy, then cancel: refunded exactly (the cooldown between orders first)
            Thread.sleep(cfg.cooldownMs() + 1_000);
            long m2 = me.getMeso();
            sayAndHear(p, "@nx buy 100 " + cfg.minPrice(), "^Preview: buy|\\([A-Z_]+\\)$", 8_000);
            String rest = sayAndHear(p, "@nx confirm", "^Order \\d+ placed|\\([A-Z_]+\\)$", 8_000);
            out.add("@nx buy confirm: " + rest);
            long held = m2 - me.getMeso();
            java.util.regex.Matcher om = rest == null ? null
                    : java.util.regex.Pattern.compile("^Order (\\d+) placed").matcher(rest);
            if (om == null || !om.find()) {
                bad.add("the resting buy was not placed: " + rest);
            } else {
                if (held != cfg.buyTotal(100, cfg.minPrice())) {
                    bad.add("the resting buy held " + held + " mesos, want " + cfg.buyTotal(100, cfg.minPrice()));
                }
                String c = sayAndHear(p, "@nx cancel " + om.group(1), "cancelled|\\([A-Z_]+\\)$", 8_000);
                out.add("@nx cancel: " + c + "; mesos back " + (me.getMeso() - (m2 - held)));
                if (me.getMeso() != m2) {
                    bad.add("the cancel refunded " + (me.getMeso() - (m2 - held)) + " of " + held + " mesos");
                }
            }
        } finally {
            Map<String, Object> td = soloMapling.Economy.exchange.ExchangeQa.teardown(ex, w);
            out.add("test regular taken out: " + td);
            if (nxTop > 0) {
                me.getCashShop().takeNxCredit((int) nxTop);
            }
        }
        long fee = cfg.fee(soloMapling.Economy.exchange.OrderBook.cost(100, bid));
        long nxEnd = w.have(soloMapling.Economy.exchange.Ledger.Asset.NX) + nxTop;
        out.add("after: " + (nxEnd - nxTop) + " NX, " + me.getMeso() + " mesos; fees paid " + (2 * fee));
        if (nxEnd != nx0) {
            bad.add("NX not conserved: " + nx0 + " -> " + nxEnd);
        }
        if (me.getMeso() != meso0 - 2 * fee) {
            bad.add("mesos: " + meso0 + " -> " + me.getMeso() + ", want exactly the two fees (" + (2 * fee) + ") less");
        }
        if (ex.halted()) {
            bad.add("the exchange halted during the check: " + ex.haltReason());
        }
        return verdict(bad, "placed in game, filled against a regular, the payout landed online, a cancel refunded "
                + "exactly, and only the two fees left", out);
    }

    // ── t-xc-2: the LEAF ticker in game (ticket 113, tickers) ────────────────────────────────────

    /**
     * t-xc-2, while logged in: (1) the test regular, funded from the pretend player's own mesos, bids for 10 Maple
     * Leaves; (2) {@code @nx leaf sell 10 <bid>} + confirm escrows 10 leaves out of the bags and fills: the mesos (less
     * the 1% fee) land at once; (3) {@code @nx leaf sell 10 <max>} + confirm rests and takes 10 more leaves, and
     * {@code @nx cancel} gives exactly those back; (4) the regular is taken out: the 10 leaves it bought come back.
     * Net: leaves unchanged, mesos down by exactly the two fees (1% each side). Leaves topped up for the check are
     * taken back.
     */
    static List<String> leafExchange(SyntheticPlayer p) throws Exception {
        var ex = soloMapling.Economy.exchange.ExchangeService.exchange();
        if (ex == null) {
            return List.of("SKIP the NX exchange is off (-Dslums.exchange=true)");
        }
        String tk = soloMapling.Economy.exchange.ExchangeQa.ITEM_TICKER;
        if (!ex.open(tk)) {
            return List.of("SKIP the " + tk + " ticker is closed (-Dslums.exchange.tickers)");
        }
        if (ex.halted()) {
            return List.of("FAIL the exchange is halted: " + ex.haltReason());
        }
        Character me = p.character();
        var w = soloMapling.Economy.exchange.GameLiveWallet.of(me);
        if (w == null || !w.locked(w::usable)) {
            return List.of("FAIL the pretend player has no usable live wallet");
        }
        var cfg = ex.config();
        var t = ex.ticker(tk);
        var leaf = t.asset();
        long n = soloMapling.Economy.exchange.ExchangeQa.ITEMS;
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        long top = Math.max(0, 2 * n - w.have(leaf));
        if (top > 0) {
            if (!w.locked(() -> w.give(leaf, top))) {
                return List.of("FAIL could not top up " + top + " Maple Leaves (bags full?)");
            }
            out.add("topped up " + top + " Maple Leaves for the check (taken back at the end)");
        }
        // the test regular's bid is funded from the tester's own mesos; bot traders bid high, so top up what is short
        // (taken back at the end) instead of failing with INSUFFICIENT_FUNDS
        long needM = soloMapling.Economy.exchange.ExchangeQa.itemFundingNeed(ex, tk, n);
        long mesoTop = 0;
        if (needM > 0) {
            long want = needM + needM / 5 + 50_000;
            if (me.getMeso() < want) {
                mesoTop = want - me.getMeso();
            }
        }
        if (mesoTop > 0) {
            if ((long) me.getMeso() + mesoTop > Integer.MAX_VALUE) {
                if (top > 0) {
                    w.locked(() -> w.take(leaf, top));
                }
                return List.of("FAIL the book's best LEAF bid needs " + needM + " mesos, more than a character holds");
            }
            me.gainMeso((int) mesoTop, false);
            out.add("topped up " + mesoTop + " mesos to fund the test regular (taken back at the end)");
        }
        final long mesoTopF = mesoTop;
        long l0 = w.have(leaf), meso0 = me.getMeso();
        out.add("before: " + l0 + " tradeable leaves, " + meso0 + " mesos");
        soloMapling.Economy.exchange.ExchangeQa.StagedItem st;
        try {
            st = soloMapling.Economy.exchange.ExchangeQa.stageItem(ex, w, tk, n);
        } catch (IllegalStateException e) {
            if (top > 0) {
                w.locked(() -> w.take(leaf, top));
            }
            if (mesoTopF > 0) {
                me.gainMeso((int) -Math.min(mesoTopF, me.getMeso()), false);
            }
            return List.of("FAIL staging: " + e.getMessage());
        }
        long bid = st.bidPrice();
        out.add("test regular bids " + bid + " per leaf for " + n + " (order " + st.bidOrder() + "), funded "
                + st.funded());
        long gets = soloMapling.Economy.exchange.ExchangeQa.sellerGets(cfg, t, n, bid);
        try {
            // (2) sell into the regular: leaves escrowed, mesos land online
            String pv = sellPreview(p, "@nx leaf sell " + n + " " + bid);
            out.add("@nx leaf sell: " + pv);
            long mesoBefore = me.getMeso();
            String placed = sayAndHear(p, "@nx confirm", "^Order \\d+ placed|\\([A-Z_]+\\)$", 8_000);
            out.add("@nx confirm: " + placed);
            long deadline = System.currentTimeMillis() + 5_000;
            while (me.getMeso() - mesoBefore < gets && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            long l1 = w.have(leaf);
            out.add("after the sale: " + l1 + " leaves, mesos +" + (me.getMeso() - mesoBefore) + " (want +" + gets + ")");
            if (placed == null || !placed.startsWith("Order ")) {
                bad.add("the leaf sale was not placed: " + placed);
            }
            if (l0 - l1 != n) {
                bad.add("the sale took " + (l0 - l1) + " leaves from the bags, want " + n);
            }
            if (me.getMeso() - mesoBefore != gets) {
                bad.add("the payout did not land online: mesos +" + (me.getMeso() - mesoBefore) + ", want +" + gets);
            }
            // (3) a resting sell at the top price takes 10 more leaves; a cancel gives exactly those back
            Thread.sleep(cfg.cooldownMs() + 1_000);
            sellPreview(p, "@nx leaf sell " + n + " " + t.maxPrice());
            String rest = sayAndHear(p, "@nx confirm", "^Order \\d+ placed|\\([A-Z_]+\\)$", 8_000);
            out.add("@nx leaf resting sell: " + rest);
            long l2 = w.have(leaf);
            java.util.regex.Matcher om = rest == null ? null
                    : java.util.regex.Pattern.compile("^Order (\\d+) placed").matcher(rest);
            if (om == null || !om.find()) {
                bad.add("the resting leaf sell was not placed: " + rest);
            } else {
                if (l1 - l2 != n) {
                    bad.add("the resting sell escrowed " + (l1 - l2) + " leaves, want " + n);
                }
                String c = sayAndHear(p, "@nx cancel " + om.group(1), "cancelled|\\([A-Z_]+\\)$", 8_000);
                out.add("@nx cancel: " + c + "; leaves " + l2 + " -> " + w.have(leaf));
                if (w.have(leaf) != l1) {
                    bad.add("the cancel returned " + (w.have(leaf) - l2) + " of " + n + " leaves");
                }
            }
        } finally {
            Map<String, Object> td = soloMapling.Economy.exchange.ExchangeQa.teardown(ex, w);
            out.add("test regular taken out: " + td);
        }
        long fee = cfg.fee(t.cost(n, bid));
        long lEnd = w.have(leaf);
        out.add("after: " + lEnd + " leaves, " + me.getMeso() + " mesos; fees paid " + (2 * fee));
        if (lEnd != l0) {
            bad.add("leaves not conserved: " + l0 + " -> " + lEnd + " (the regular's bought leaves come back)");
        }
        if (me.getMeso() != meso0 - 2 * fee) {
            bad.add("mesos: " + meso0 + " -> " + me.getMeso() + ", want exactly the two fees (" + (2 * fee) + ") less");
        }
        if (top > 0 && !w.locked(() -> w.take(leaf, top))) {
            out.add("could not take back the " + top + " topped-up leaves");
        }
        if (mesoTopF > 0) {
            me.gainMeso((int) -Math.min(mesoTopF, me.getMeso()), false);
        }
        if (ex.halted()) {
            bad.add("the exchange halted during the check: " + ex.haltReason());
        }
        return verdict(bad, "leaves escrowed on a sell, the fill paid mesos online, a cancel returned the leaves, the "
                + "bought leaves came back and only the two 1% fees left", out);
    }

    /**
     * Previews an order, waiting out the account's order cooldown first (an earlier step or check may have placed
     * one): on a COOLDOWN answer it sleeps the seconds the answer names (bounded, 3 tries, at most about 35 s).
     */
    static String sellPreview(SyntheticPlayer p, String cmd) throws Exception {
        String pv = null;
        for (int i = 0; i < 3; i++) {
            pv = sayAndHear(p, cmd, "^Preview: sell|\\([A-Z_]+\\)$", 8_000);
            if (pv == null || !pv.contains("(COOLDOWN)")) {
                return pv;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("wait (\\d+) s").matcher(pv);
            long secs = m.find() ? Long.parseLong(m.group(1)) : 10;
            Thread.sleep(Math.min(secs, 15) * 1_000 + 1_000);
        }
        return pv;
    }

    /** The window's claim request (CP 0x3732: action 1, day), after the per-account throttle. */
    private static void claim(SyntheticPlayer p, int day) throws InterruptedException {
        Thread.sleep(1_200);
        ByteBufOutPacket b = new ByteBufOutPacket();
        b.writeByte(1);
        b.writeByte(day);
        p.send(RecvOpcode.DAILY_CHECKIN, b);
        Thread.sleep(300);
    }

    // ── t-sc-3: starter chairs ───────────────────────────────────────────────────────────────────

    /**
     * t-sc-3: a character made the normal way (BeginnerCreator, the same factory a client's creation uses) owns one
     * Relaxer and one Fishing Chair; the login grant then only marks it and adds nothing, however often it runs; a
     * character from before 1.6.0 (no chairs, no mark) gets both once. The login grant runs on a copy loaded from the
     * database with a plain client (the pretend players are excluded from it like bots); the throwaway is deleted.
     */
    static List<String> starterChairs(SyntheticPlayer p) throws Exception {
        Character gm = p.character();
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        roomForCharacters(gm);
        String name = "QaChair" + String.format(Locale.ROOT, "%05d", System.currentTimeMillis() / 1000 % 100_000);
        int made = client.creator.novice.BeginnerCreator.createCharacter(gm.getClient(), name, 20000, 30000, 0, 0, 0,
                0, 0, 0);
        int cid = Character.getIdByName(name);
        if (made != 0 || cid < 0) {
            return List.of("FAIL could not make a throwaway character " + name + " (status " + made + ")");
        }
        out.add("made " + name + " (" + cid + ")");
        try {
            SyntheticClient loader = new SyntheticClient(96, gm.getWorld(), gm.getClient().getChannel());
            loader.setAccID(gm.getAccountID());
            Character fresh = Character.loadCharFromDB(cid, loader, true);
            int[] created = chairs(fresh);
            out.add("as created: Relaxer " + created[0] + ", Fishing Chair " + created[1]);
            if (created[0] != 1 || created[1] != 1) {
                bad.add("a new character has Relaxer " + created[0] + " / Fishing Chair " + created[1] + ", want 1 / 1");
            }
            fresh.setClient(Client.createMock());   // a real player's login: not a bot, not a pretend player
            boolean first = StarterChairs.onLogin(fresh), second = StarterChairs.onLogin(fresh);
            int[] afterLogins = chairs(fresh);
            out.add("two logins: granted " + first + "/" + second + ", marked " + StarterChairs.given(fresh)
                    + ", Relaxer " + afterLogins[0] + ", Fishing Chair " + afterLogins[1]);
            if (first || second || afterLogins[0] != 1 || afterLogins[1] != 1 || !StarterChairs.given(fresh)) {
                bad.add("logging in again added chairs or did not mark the character");
            }
            // a character from before 1.6.0: no chairs, no mark
            Inventory setup = fresh.getInventory(InventoryType.SETUP);
            for (int id : new int[]{StarterChairs.RELAXER, FishingPolicy.CHAIR}) {
                Item it = setup.findById(id);
                if (it != null) {
                    setup.removeItem(it.getPosition());
                }
            }
            fresh.getAreaInfos().remove(StarterChairs.GIVEN_AREA);
            boolean grant = StarterChairs.onLogin(fresh), again = StarterChairs.onLogin(fresh);
            int[] old = chairs(fresh);
            out.add("pre-1.6.0 character: granted " + grant + " then " + again + ", Relaxer " + old[0] + ", Fishing Chair " + old[1]);
            if (!grant || again || old[0] != 1 || old[1] != 1) {
                bad.add("an older character did not get both chairs exactly once");
            }
        } finally {
            boolean deleted = gm.getClient().deleteCharacter(cid, gm.getAccountID());
            out.add("deleted " + name + ": " + deleted);
            if (!deleted) {
                bad.add("could not delete the throwaway " + name);
            }
        }
        return verdict(bad, "a new character owns one Relaxer and one Fishing Chair; logins add none; older ones get both once", out);
    }

    private static int[] chairs(Character c) {
        Inventory setup = c.getInventory(InventoryType.SETUP);
        return new int[]{setup.countById(StarterChairs.RELAXER), setup.countById(FishingPolicy.CHAIR)};
    }
}
