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
            "family", "botRebirth");

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
                case "family" -> family(p);
                case "botRebirth" -> botRebirth(p);
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
            out.add("following the owner to another map is not built yet (ticket 13 defers movement): not judged");
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
                out.add("owner moved from " + homeMap + " to " + gm.getMapId());
            }
        }
        return verdict(bad, ALT_A + " joins the party on the owner's map, a repeat is refused, the cap of 5 holds,"
                + " @dismiss saves and removes it", out);
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

    // ── t-rb-1: a regular earns a rebirth (ticket 30, 1.6.1) ──────────────────────────────────────

    /**
     * t-rb-1: (1) an online bot is raised to 200 on its build (4th job); (2) with the pace forced it gathers both boss
     * drops, crafts the key and is granted the trip; (3) the rebirth at Wyatt's rules: rebirths +1, level 1 beginner,
     * key and drops gone, the rebirth EXP rate, same worn gear, same mesos, no cash items, past skills kept, no second
     * rebirth at level 1; (4) the bot is put back as it was.
     */
    static List<String> botRebirth(SyntheticPlayer p) {
        if (!soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.enabled()) {
            return List.of("SKIP bot rebirths are off (BOT_REBIRTH_ENABLED / USE_REBIRTH_SYSTEM)");
        }
        if (config.YamlConfig.config.server.BOT_REBIRTH_PER_DAY <= 0) {
            return List.of("SKIP BOT_REBIRTH_PER_DAY is 0");
        }
        soloMapling.ArtificialPlayer.BotRebirthQa.Before b;
        try {
            b = soloMapling.ArtificialPlayer.BotRebirthQa.pick(p.character());
        } catch (IllegalStateException e) {
            return List.of("FAIL " + e.getMessage());
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        ev.add("bot " + b.bot().getName() + " lv " + b.level() + " job " + b.job() + " rebirths " + b.reborns());
        try {
            if (!soloMapling.ArtificialPlayer.BotRebirthQa.raise(b)) {
                bad.add("the bot did not reach a 4th job at 200: " + soloMapling.ArtificialPlayer.BotRebirthQa.describe(b));
                return verdict(bad, "", ev);
            }
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
        return verdict(bad, "a level 200 bot gathered both boss drops, crafted its key and was reborn at level 1 as a"
                + " beginner with the rebirth EXP rate, keeping its gear, mesos and skills; then put back", ev);
    }

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
