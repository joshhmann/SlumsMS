package soloMapling.autoqa;

import client.Character;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.guild.Guild;
import net.server.guild.GuildPackets;
import net.server.world.World;
import server.maps.HiredMerchant;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds;
import soloMapling.qa.QaLog;
import soloMapling.qa.QaSuite;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server side of the check runner: synthetic players, the {@code !qa} lines they cause, and the probes
 * (server-side facts and fixtures a check asks for by name). A probe answers with lines, the first one saying
 * PASS / FAIL / SKIP; a line "VAR name=value" sets a variable the next steps can use as {name}.
 */
final class GameAutoQaWorld implements AutoQaWorld {

    static final int KEEP_LINES = 20_000;

    /** The ten bot guilds the 1.5.0 notes promise (t-bg-1). */
    static final List<String> BOT_GUILDS = List.of("Legendz", "MoonLiteX", "MesoMafia", "SlumLords", "ZakumSlayerz",
            "KoreanBBQ", "SnailGang", "Pwnage", "NoobTeam", "HenesysKidz");

    /** A qa line and the GM whose run wrote it. */
    private record Owned(int gmId, AutoQaLine line) {
    }

    private final Deque<Owned> qa = new ArrayDeque<>();
    private final Map<String, Long> remembered = new ConcurrentHashMap<>();
    private Runnable unlisten;
    private LlmJudge judge;

    synchronized void open() {
        if (unlisten == null) {
            unlisten = QaLog.listen((gmId, scenario, kind, message) -> {
                SyntheticPlayer p = SyntheticPlayers.get(1);
                SyntheticPlayer p2 = SyntheticPlayers.get(2);
                if ((p != null && p.id() == gmId) || (p2 != null && p2.id() == gmId)) {
                    add(new AutoQaLine(System.currentTimeMillis(), "qa", kind, message), gmId);
                }
            });
        }
        judge = LlmJudge.fromConfig(YamlConfig.config.server);
    }

    synchronized void close() {
        if (unlisten != null) {
            unlisten.run();
            unlisten = null;
        }
    }

    private synchronized void add(AutoQaLine line, int gmId) {
        qa.addLast(new Owned(gmId, line));
        while (qa.size() > KEEP_LINES) {
            qa.removeFirst();
        }
    }

    @Override
    public String unavailableReason() {
        if (!QaSuite.isEnabled()) {
            return "the !qa suite is off on this server (-Dslums.qa=true or attack capture)";
        }
        if (!AutoQa.isEnabled()) {
            return "auto-QA is off (-Dslums.autoqa=true or AUTOQA_ENABLED)";
        }
        return null;
    }

    @Override
    public Actor actor(int slot) {
        return SyntheticPlayers.login(slot);
    }

    @Override
    public void logoutAll() {
        SyntheticPlayers.logoutAll();
    }

    @Override
    public synchronized List<AutoQaLine> qaLines(int gmId, long sinceMs) {
        List<AutoQaLine> out = new ArrayList<>();
        for (Owned o : qa) {
            if (o.gmId() == gmId && o.line().timeMs() >= sinceMs) {
                out.add(o.line());
            }
        }
        return out;
    }

    @Override
    public String nameOf(int characterId) {
        for (World w : Server.getInstance().getWorlds()) {
            Character c = w.getPlayerStorage().getCharacterById(characterId);
            if (c != null) {
                return c.getName();
            }
        }
        return null;
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    @Override
    public LlmJudge judge() {
        return judge;
    }

    @Override
    public Map<String, Object> lastHealth() {
        return HealthSweeper.get().last();
    }

    @Override
    public Map<String, Object> chatQuality() {
        return ChatQuality.runNow();
    }

    // ───────────────────────────────────────────────────────────────── probes

    @Override
    public List<String> probe(String what, Actor actor, String... a) {
        Character me = actor instanceof SyntheticPlayer sp ? sp.character() : null;
        try {
            return switch (what) {
                case "guildless" -> guildless(me);
                case "inPlayerGuild" -> inPlayerGuild(me);
                case "isGuildless" -> List.of(me.getGuildId() <= 0 ? "PASS guildless" : "FAIL still in guild " + me.getGuildId());
                case "partyHas" -> partyHas(me, a[0], true);
                case "partyLacks" -> partyHas(me, a[0], false);
                case "onMyMap" -> onMyMap(me, a[0], true);
                case "notOnMyMap" -> onMyMap(me, a[0], false);
                case "warp" -> warp(me, Integer.parseInt(a[0]));
                case "warpPlayer" -> warp(((SyntheticPlayer) actor(Integer.parseInt(a[0]))).character(), Integer.parseInt(a[1]));
                case "rememberMeso" -> {
                    remembered.put("meso", (long) me.getMeso());
                    yield List.of("PASS meso " + me.getMeso());
                }
                case "mesoGained" -> mesoGained(me, Long.parseLong(a[0]), Long.parseLong(a[1]));
                case "giveItem" -> giveItem(actor(Integer.parseInt(a[0])), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                case "channelSplit" -> channelSplit();
                case "botGuilds" -> botGuilds();
                case "clientGate" -> clientGate();
                case "gachaMachines" -> gachaMachines(me);
                case "remoteLastSlot" -> remoteLastSlot();
                case "gachaOdds" -> gachaOdds(a[0]);
                case "npcPrices" -> npcPrices();
                case "cosmeticStock" -> cosmeticStock();
                case "botShops" -> botShops();
                case "clumpsOnMyMap" -> clumpsOnMyMap(me, Integer.parseInt(a[0]), Integer.parseInt(a[1]));
                case "nearbyBot" -> nearbyBot(me);
                case "buddy" -> buddy(me, a[0], a[1]);
                case "buddyRoom" -> buddyRoom(me, Integer.parseInt(a[0]));
                case "buddyFresh" -> buddyFresh(me, a[0]);
                case "engaged" -> engaged(actor(Integer.parseInt(a[0])));
                case "notEngaged" -> notEngaged();
                case "equipRoom" -> equipRoom(me, Integer.parseInt(a[0]));
                case "certainBuddies" -> {
                    soloMapling.ArtificialPlayer.BotBuddies.certainFor(java.util.concurrent.TimeUnit.MINUTES.toMillis(
                            Long.parseLong(a[0])));
                    yield List.of("PASS bots accept every buddy request for " + a[0] + " min");
                }
                default -> List.of("FAIL no probe '" + what + "'");
            };
        } catch (RuntimeException e) {
            return List.of("FAIL probe " + what + " threw " + e);
        }
    }

    private static List<String> guildless(Character me) {
        if (me.getGuildId() <= 0) {
            return List.of("PASS already guildless");
        }
        int was = me.getGuildId();
        Guild g = Server.getInstance().getGuild(was);
        if (g != null && !BotGuilds.isBotGuild(was) && g.getLeaderId() == me.getId()) {
            Server.getInstance().disbandGuild(was); // our own test guild: gone, so nightly runs do not pile them up
            me.getMGC().setGuildId(0);
            me.getMGC().setGuildRank(5);
            me.sendPacket(GuildPackets.showGuildInfo(null));
            return List.of("PASS disbanded own guild " + was);
        }
        leaveGuild(me);
        return List.of(me.getGuildId() <= 0 ? "PASS left guild " + was : "FAIL could not leave guild " + was);
    }

    static void leaveGuild(Character me) {
        if (me.getGuildId() <= 0 || me.getMGC() == null) {
            return;
        }
        Server.getInstance().leaveGuild(me.getMGC());
        me.sendPacket(GuildPackets.showGuildInfo(null));
        me.getMGC().setGuildId(0);
        me.getMGC().setGuildRank(5);
        me.saveGuildStatus();
        me.getMap().broadcastPacket(me, GuildPackets.guildNameChanged(me.getId(), ""));
    }

    /** In a guild of players (made here, led by this player) - never a bot guild. */
    private static List<String> inPlayerGuild(Character me) {
        if (me.getGuildId() > 0 && !BotGuilds.isBotGuild(me.getGuildId())) {
            return List.of("PASS already in player guild " + me.getGuildId());
        }
        leaveGuild(me);
        String name = "AutoQa" + (System.currentTimeMillis() / 1000 % 100000);
        int gid = Server.getInstance().createGuild(me.getId(), name);
        if (gid <= 0) {
            return List.of("FAIL could not create guild " + name);
        }
        me.getMGC().setGuildId(gid);
        Guild guild = Server.getInstance().getGuild(me.getGuildId(), me.getWorld(), me);
        Server.getInstance().changeRank(gid, me.getId(), 1);
        me.sendPacket(GuildPackets.showGuildInfo(me));
        if (guild != null) {
            guild.broadcastNameChanged();
        }
        return List.of("PASS made player guild " + name + " #" + gid);
    }

    private static List<String> partyHas(Character me, String name, boolean want) {
        boolean has = me.getParty() != null && me.getParty().getMembers().stream().anyMatch(m -> name.equalsIgnoreCase(m.getName()));
        String line = name + (has ? " is" : " is not") + " in " + me.getName() + "'s party";
        return List.of((has == want ? "PASS " : "FAIL ") + line);
    }

    private static List<String> onMyMap(Character me, String name, boolean want) {
        boolean on = me.getMap().getCharacterByName(name) != null;
        String line = name + (on ? " is" : " is not") + " on map " + me.getMapId();
        return List.of((on == want ? "PASS " : "FAIL ") + line);
    }

    private static List<String> warp(Character who, int mapId) {
        MapleMap target = who.getClient().getChannelServer().getMapFactory().getMap(mapId);
        if (target == null) {
            return List.of("FAIL no map " + mapId);
        }
        who.changeMap(target, target.getRandomPlayerSpawnpoint());
        return List.of("PASS " + who.getName() + " now on " + who.getMapId());
    }

    private List<String> mesoGained(Character me, long min, long max) {
        Long before = remembered.get("meso");
        if (before == null) {
            return List.of("FAIL no meso remembered (rememberMeso first)");
        }
        long gained = me.getMeso() - before;
        return List.of((gained >= min && gained <= max ? "PASS" : "FAIL") + " gained " + gained + " mesos (want "
                + min + ".." + max + ")");
    }

    private static List<String> giveItem(Actor actor, int itemId, int qty) {
        Character c = ((SyntheticPlayer) actor).character();
        boolean ok = server.ItemInformationProvider.getInstance().getName(itemId) != null
                && client.inventory.manipulator.InventoryManipulator.addById(c.getClient(), itemId, (short) qty, "autoqa", -1);
        return List.of(ok ? "PASS gave " + qty + " x " + itemId + " to " + c.getName() : "FAIL could not give " + itemId);
    }

    /** t-ch-1: five channels, bots on 1-4 (most on 1), none on 5. */
    private static List<String> channelSplit() {
        int[] per = BotChannels.botsPerChannel();
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < per.length; i++) {
            sb.append(" ch").append(i + 1).append('=').append(per[i]);
        }
        String bad = channelVerdict(per, BotChannels.currentShares());
        out.add(bad == null ? "PASS" + sb : "FAIL " + bad);
        out.add("bots per channel:" + sb);
        out.add(BotChannels.describe());
        return out;
    }

    /**
     * t-ch-1: five channels, ch1 the busiest, ch5 the quietest, and each near its configured share. rc6 auto-QA
     * triage: the probe still wanted ch5 empty (the old split); the owner later gave ch5 a share (weights
     * 100,55,35,20,15), and the server matched it exactly. Null when fine, else what is wrong.
     */
    static String channelVerdict(int[] per, double[] shares) {
        List<String> bad = new ArrayList<>();
        if (per.length != 5) {
            bad.add(per.length + " channels, want 5");
        }
        int total = 0;
        for (int n : per) {
            total += n;
        }
        for (int i = 0; i < per.length; i++) {
            if (i < 4 && per[i] == 0) {
                bad.add("ch" + (i + 1) + " has no bots");
            }
            if (per[i] > per[0]) {
                bad.add("ch" + (i + 1) + " busier than ch1");
            }
            if (i < 4 && per.length >= 5 && per[4] > per[i]) {
                bad.add("ch5 (" + per[4] + ") busier than ch" + (i + 1) + " (" + per[i] + ")");
            }
            if (i < shares.length) {
                long want = Math.round(shares[i] * total);
                long slack = Math.max(10, Math.round(want * 0.25));
                if (Math.abs(per[i] - want) > slack) {
                    bad.add("ch" + (i + 1) + " has " + per[i] + ", want about " + want);
                }
            }
        }
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    /** t-bg-1: how long the population director runs before every bot guild must have someone online. */
    static final long GUILD_PRESENCE_SETTLE_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);

    /**
     * t-bg-1's verdict on empty guilds: null when fine. rc6 (owner): the director keeps at least one member of every
     * bot guild online, so once it has run ~10 minutes a guild with nobody on is a FAIL. Before that (just booted, or
     * the director off) empty guilds are reported, not judged.
     */
    static String emptyGuildsVerdict(int emptyOnline, long directorRunningMs) {
        if (emptyOnline == 0 || directorRunningMs < GUILD_PRESENCE_SETTLE_MS) {
            return null;
        }
        return emptyOnline + " guild(s) with nobody online (want every guild to have one, director up "
                + directorRunningMs / 60_000 + " min)";
    }

    /** t-bg-1: the ten bot guilds exist with members online and two rival pairs. */
    private static List<String> botGuilds() {
        List<String> lines = BotGuilds.describe();
        List<String> missing = new ArrayList<>();
        for (String g : BOT_GUILDS) {
            boolean found = lines.stream().anyMatch(l -> l.matches("#\\d+ " + Pattern.quote(g) + " .*"));
            if (!found) {
                missing.add(g);
            }
        }
        int rivals = 0, emptyOnline = 0;
        Pattern online = Pattern.compile("\\((\\d+) online\\)");
        for (String l : lines) {
            if (l.contains(", rival ")) {
                rivals++;
            }
            Matcher m = online.matcher(l);
            if (m.find() && Integer.parseInt(m.group(1)) == 0) {
                emptyOnline++;
            }
        }
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        if (!missing.isEmpty()) {
            bad.add("missing " + missing);
        }
        if (rivals < 4) {
            bad.add(rivals / 2 + " rival pair(s), want 2");
        }
        long directorUp = soloMapling.ArtificialPlayer.BotPopulationDirector.runningForMs();
        String empty = emptyGuildsVerdict(emptyOnline, directorUp);
        if (empty != null) {
            bad.add(empty);
        }
        out.add(bad.isEmpty() ? "PASS 10 bot guilds, " + rivals / 2 + " rival pairs"
                + (emptyOnline > 0 ? ", " + emptyOnline + " with nobody online right now (not judged: director up "
                        + directorUp / 60_000 + " min, under 10)" : "")
                : "FAIL " + String.join("; ", bad));
        out.addAll(lines);
        return out;
    }

    /** t-vg-1: the version gate refuses an old build and lets the current one in (no restart, scratch config). */
    private static List<String> clientGate() {
        String current = net.server.handlers.login.ClientVersionGate.verdictFor(10500, 10500);
        String old = net.server.handlers.login.ClientVersionGate.verdictFor(10410, 10500);
        boolean ok = "ALLOWED".equals(current) && old.startsWith("REFUSED") && old.contains("out of date");
        return List.of((ok ? "PASS" : "FAIL") + " build 10500 vs min 10500: " + current + "; build 10410: " + old);
    }

    /** t-ga-2: every town machine stands on a foothold and not on a portal. */
    private static List<String> gachaMachines(Character me) {
        Map<String, int[]> machines = new java.util.LinkedHashMap<>();
        machines.put("ellinia", new int[]{101000000, 9100101});
        machines.put("perion", new int[]{102000000, 9100102});
        machines.put("kerning", new int[]{103000000, 9100103});
        machines.put("sleepywood", new int[]{105040300, 9100104});
        machines.put("mushroom_shrine", new int[]{800000000, 9100105});
        machines.put("new_leaf_city", new int[]{600000000, 9100109});
        List<String> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        Channel ch = me.getClient().getChannelServer();
        for (Map.Entry<String, int[]> e : machines.entrySet()) {
            MapleMap map = ch.getMapFactory().getMap(e.getValue()[0]);
            if (map == null) {
                bad.add(e.getKey() + ": no map");
                continue;
            }
            var npc = map.getNPCById(e.getValue()[1]);
            if (npc == null) {
                bad.add(e.getKey() + ": no machine NPC " + e.getValue()[1] + " on " + map.getId());
                continue;
            }
            Point p = npc.getPosition();
            List<FootholdCheck.Seg> segs = new ArrayList<>();
            for (var f : map.getFootholds().getAllFootholds()) {
                segs.add(new FootholdCheck.Seg(f.getX1(), f.getY1(), f.getX2(), f.getY2()));
            }
            FootholdCheck.Verdict v = FootholdCheck.check(segs, p.x, p.y, FootholdCheck.Pose.GROUNDED, 3);
            double nearestPortal = Double.MAX_VALUE;
            for (Portal portal : map.getPortals()) {
                if (portal.getType() == Portal.MAP_PORTAL || portal.getType() == 2) {
                    nearestPortal = Math.min(nearestPortal, portal.getPosition().distance(p));
                }
            }
            String line = e.getKey() + " " + map.getId() + " at (" + p.x + "," + p.y + "): " + v.kind()
                    + (Double.isNaN(v.dy()) ? "" : " dy " + Math.round(v.dy())) + ", nearest portal "
                    + (nearestPortal == Double.MAX_VALUE ? "none" : Math.round(nearestPortal) + " px");
            out.add(line);
            if (v.kind() != FootholdCheck.Kind.OK || nearestPortal < 40) {
                bad.add(line);
            }
        }
        out.add(0, bad.isEmpty() ? "PASS all 6 machines on the ground, off the portals" : "FAIL " + String.join("; ", bad));
        return out;
    }

    /** t-ga-1: the town machine's odds are 70 / 24.5 / 5 / 0.5 %. */
    private static List<String> gachaOdds(String town) {
        var pools = soloMapling.Gachapon.GachaPools.get();
        var t = pools.find(town);
        if (t == null) {
            return List.of("FAIL no gachapon town " + town);
        }
        double[] want = {0.70, 0.245, 0.05, 0.005};
        List<String> out = new ArrayList<>();
        boolean ok = true;
        int i = 0;
        for (var o : pools.odds(t)) {
            out.add(o.tier().key() + " " + o.chance());
            ok &= i < want.length && Math.abs(o.chance() - want[i]) < 1e-6;
            i++;
        }
        ok &= i == want.length;
        out.add(0, (ok ? "PASS " : "FAIL ") + t.name() + " odds " + out + " (want 0.70 / 0.245 / 0.05 / 0.005)");
        return out;
    }

    /** t-ga-3: the last slot of the remote ticket's list is Ludibrium. */
    private static List<String> remoteLastSlot() {
        var town = server.gachapon.Gachapon.remote(11);
        String name = town == null ? "none" : town.key() + " (npc " + town.npcId() + ")";
        return List.of((town != null && "ludibrium".equals(town.key()) ? "PASS" : "FAIL") + " remote slot 11 = " + name);
    }

    /** t-sk-2: Pet Food 2,000 and the VIP Teleport Rock 3,000,000 in NPC shops. */
    private static List<String> npcPrices() {
        int petFood = soloMapling.Economy.MesoSinks.npcPrice(2120000, 30);
        int vip = soloMapling.Economy.MesoSinks.npcPrice(5041000, 1_500_000);
        boolean ok = petFood == 2000 && vip == 3_000_000;
        return List.of((ok ? "PASS" : "FAIL") + " Pet Food " + petFood + " (want 2000), VIP Teleport Rock " + vip
                + " (want 3000000)");
    }

    /** t-sk-1: Hanako's list has the Gachapon Ticket (3m) and the Transparent Hat (10m). */
    private static List<String> cosmeticStock() {
        List<int[]> stock = soloMapling.Economy.MesoSinks.cosmeticStock();
        Map<Integer, Integer> price = new java.util.HashMap<>();
        List<String> out = new ArrayList<>();
        for (int[] s : stock) {
            price.put(s[0], s[1]);
            out.add(s[0] + " @ " + s[1]);
        }
        Integer ticket = price.get(5220000);
        Integer hat = price.get(1002186);
        boolean ok = ticket != null && ticket == 3_000_000 && hat != null && hat == 10_000_000;
        out.add(0, (ok ? "PASS" : "FAIL") + " Hanako (npc " + soloMapling.Economy.MesoSinks.cosmeticNpc() + "): ticket "
                + ticket + " (want 3000000), Transparent Hat " + hat + " (want 10000000), " + stock.size() + " items");
        return out;
    }

    /** t-bs-1: FM rooms carry bot shops on channels 1, 2 and 3, most on 1. */
    private static List<String> botShops() {
        List<Channel> chs = BotChannels.channels();
        int[] per = new int[Math.max(3, chs.size())];
        for (int i = 0; i < chs.size(); i++) {
            for (int room = 910000001; room <= 910000022; room++) {
                if (!chs.get(i).getMapFactory().isMapLoaded(room)) {
                    continue;
                }
                for (HiredMerchant hm : chs.get(i).getMapFactory().getMap(room).getAllHiredMerchants()) {
                    if (BotHelpers.isArtificial(hm.getOwnerId())) {
                        per[i]++;
                    }
                }
            }
        }
        boolean ok = per[0] > 0 && per[1] > 0 && per[2] > 0 && per[0] >= per[1] && per[0] >= per[2];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < per.length; i++) {
            sb.append(" ch").append(i + 1).append('=').append(per[i]);
        }
        return List.of((ok ? "PASS" : "FAIL") + " bot hired merchants in FM rooms:" + sb);
    }

    /** t-sp-1: no clump of n+ bots within r px on my map. */
    private static List<String> clumpsOnMyMap(Character me, int radius, int min) {
        List<SweepMath.Spot> spots = new ArrayList<>();
        for (Character c : me.getMap().getAllPlayers()) {
            if (BotHelpers.isBot(c)) {
                spots.add(new SweepMath.Spot(c.getId(), c.getPosition().x, c.getPosition().y));
            }
        }
        List<SweepMath.Clump> cl = SweepMath.clumps(spots, radius, min);
        List<String> out = new ArrayList<>();
        out.add((cl.isEmpty() ? "PASS" : "FAIL") + " " + spots.size() + " bots on map " + me.getMapId() + ", "
                + cl.size() + " clump(s) of " + min + "+ within " + radius + " px");
        for (SweepMath.Clump c : cl) {
            out.add("clump at (" + c.x() + "," + c.y() + "): " + c.members().size() + " bots");
        }
        return out;
    }

    /** A bot on my map to talk to (t-bb-1): VAR near=<name>. */
    private static List<String> nearbyBot(Character me) {
        Character best = null;
        double bestD = Double.MAX_VALUE;
        for (Character c : me.getMap().getAllPlayers()) {
            if (BotHelpers.isBot(c)) {
                double d = c.getPosition().distance(me.getPosition());
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
        }
        if (best == null) {
            return List.of("SKIP no bot on map " + me.getMapId());
        }
        return List.of("PASS " + best.getName() + " at " + Math.round(bestD) + " px", "VAR near=" + best.getName());
    }

    /**
     * t-bb-1/2: at least {@code free} open slots on my buddy list, made by dropping bot buddies (never a player). Every
     * run adds one bot in t-bb-1 and the initiative's bot buddy requests add more, so on TEST 2026-10-26 AutoQaOne's
     * list sat at 25 of 25 after t-bb-1 and t-bb-2's add was refused as full: the bot never showed up.
     */
    private static List<String> buddyRoom(Character me, int free) {
        var bl = me.getBuddylist();
        List<Integer> ids = new ArrayList<>();
        for (int id : bl.getBuddyIds()) {
            ids.add(id);
        }
        List<Integer> drop = buddiesToDrop(ids, bl.getCapacity(), free, BotHelpers::isArtificial);
        for (int id : drop) {
            me.deleteBuddy(id);
        }
        int open = bl.getCapacity() - bl.getBuddyIds().length;
        return List.of((open >= free ? "PASS " : "FAIL ") + open + " of " + bl.getCapacity() + " buddy slot(s) free"
                + (drop.isEmpty() ? "" : " (dropped " + drop.size() + " bot buddy/ies)"));
    }

    /**
     * t-bb-1/2: drops a pending entry for {@code name} from my list before the add. TEST 2026-10-26 (t-bb-2): iiSugars
     * was still on AutoQaOne's list from an earlier run, unanswered; the stock add skips a name already listed, so the
     * bot was never asked and the check sat on "pending" for 200 s. An accepted buddy is kept.
     */
    private static List<String> buddyFresh(Character me, String name) {
        var e = me.getBuddylist().get(name);
        Character bot = e == null ? null : me.getWorldServer().getPlayerStorage().getCharacterById(e.getCharacterId());
        String state = buddyState(e != null, e != null && e.isVisible(), bot != null,
                bot != null && bot.getBuddylist().containsVisible(me.getId()));
        if (!dropBeforeAdd(state)) {
            return List.of("PASS " + name + " is " + state + " on " + me.getName() + "'s buddy list");
        }
        me.deleteBuddy(e.getCharacterId());
        return List.of("PASS dropped " + name + "'s unanswered entry so the add asks the bot again");
    }

    /** Only an unanswered entry goes: an accepted buddy stays, and "none" has nothing to drop. */
    static boolean dropBeforeAdd(String state) {
        return "pending".equals(state);
    }

    /** The bot buddies to drop so {@code free} slots open, first listed first; players are never dropped. */
    static List<Integer> buddiesToDrop(List<Integer> ids, int capacity, int free, java.util.function.IntPredicate bot) {
        int need = ids.size() + free - capacity;
        List<Integer> out = new ArrayList<>();
        for (int id : ids) {
            if (out.size() >= need) {
                break;
            }
            if (bot.test(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** t-bb-1: my buddy entry for a name: want "accepted", "pending" or "none". */
    private static List<String> buddy(Character me, String name, String want) {
        var e = me.getBuddylist().get(name);
        // rc6 triage (t-bb-2): the requester's own entry is visible while the request is still open, and also after
        // a bot turned it down, so the old probe said "accepted" for ElliniQueen, which had refused; buddy chat then
        // went nowhere. Accepted now means the bot's list has the player too.
        Character bot = e == null ? null : me.getWorldServer().getPlayerStorage().getCharacterById(e.getCharacterId());
        boolean botHas = bot != null && bot.getBuddylist().containsVisible(me.getId());
        String state = buddyState(e != null, e != null && e.isVisible(), bot != null, botHas);
        return List.of((state.equals(want) ? "PASS " : "FAIL ") + name + " on " + me.getName() + "'s buddy list: " + state
                + " (want " + want + ")");
    }

    /** The buddy state a probe reports: none, pending (asked, not accepted, or the bot is offline) or accepted. */
    static String buddyState(boolean entry, boolean visible, boolean botOnline, boolean botHasPlayer) {
        if (!entry) {
            return "none";
        }
        return visible && botOnline && botHasPlayer ? "accepted" : "pending";
    }

    /**
     * rc6 triage (t-rm-4, 2026-10-23): "!qa do wedding" refused twice with "make room for the ring in your equip
     * inventory": the pretend player's equip tab fills over a day of checks (each t-rm-2 divorce left its dead wedding
     * ring behind, plus gachapon rolls and bought swords) and is never emptied. Frees at least {@code want} equip
     * slots of the pretend player by dropping its unequipped equips, the highest slots first, never a live ring.
     */
    private static List<String> equipRoom(Character me, int want) {
        client.inventory.Inventory inv = me.getInventory(client.inventory.InventoryType.EQUIP);
        List<String> out = new ArrayList<>();
        List<Short> slots = new ArrayList<>();
        List<Boolean> live = new ArrayList<>();
        for (client.inventory.Item it : inv.list()) {
            slots.add(it.getPosition());
            live.add(it instanceof client.inventory.Equip eq && eq.getRingId() > 0);
        }
        List<Short> drop = slotsToFree(inv.getNumFreeSlot(), want, slots, live);
        for (short slot : drop) {
            client.inventory.Item it = inv.getItem(slot);
            if (it != null) {
                out.add("dropped " + it.getItemId() + " from slot " + slot);
                client.inventory.manipulator.InventoryManipulator.removeFromSlot(me.getClient(),
                        client.inventory.InventoryType.EQUIP, slot, (short) 1, false);
            }
        }
        int free = inv.getNumFreeSlot();
        out.add(0, (free >= want ? "PASS " : "FAIL ") + free + " free equip slot(s) (want " + want + ")");
        return out;
    }

    /** Which equip slots to empty so {@code want} are free: the highest slots first, never a live ring. */
    static List<Short> slotsToFree(int free, int want, List<Short> slots, List<Boolean> liveRing) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            order.add(i);
        }
        order.sort((x, y) -> Short.compare(slots.get(y), slots.get(x)));
        List<Short> out = new ArrayList<>();
        for (int i : order) {
            if (free + out.size() >= want) {
                break;
            }
            if (liveRing.get(i)) {
                continue;
            }
            out.add(slots.get(i));
        }
        return out;
    }

    /** t-mr-1: player 1 and the given player are engaged to each other. */
    /**
     * rc6 triage: t-mr-1 leaves the pretend players engaged to each other, and every later romance check's proposal
     * was refused ("already engaged"). Breaks any engagement or marriage of an online pretend player the way the
     * client's inventory "break" does (RING_ACTION mode 3 on its token or ring).
     */
    private List<String> notEngaged() {
        List<String> out = new ArrayList<>();
        boolean ok = true;
        for (int slot = 1; slot <= SyntheticPlayers.NAMES.length; slot++) {
            SyntheticPlayer p = SyntheticPlayers.get(slot);
            Character c = p == null ? null : p.character();
            if (c == null || c.getPartnerId() <= 0) {
                continue;
            }
            int partner = c.getPartnerId();
            int token = c.getMarriageItemId();
            if (token > 0 && c.getItemQuantity(token, false) <= 0) {
                // a vb-* reset wiped the bag: the break-engagement action needs the ring in it, so put it back first
                client.inventory.manipulator.InventoryManipulator.addById(c.getClient(), token, (short) 1, "qa", -1);
            }
            net.packet.ByteBufOutPacket body = new net.packet.ByteBufOutPacket();
            body.writeByte(3);
            body.writeInt(c.getMarriageItemId());
            p.send(net.opcodes.RecvOpcode.RING_ACTION, body);
            boolean freed = c.getPartnerId() <= 0;
            ok &= freed;
            out.add((freed ? "broke " : "could not break ") + c.getName() + "'s engagement to " + partner
                    + " (token " + c.getMarriageItemId() + ")");
        }
        out.add(0, ok ? (out.isEmpty() ? "PASS nobody engaged" : "PASS nobody engaged now") : "FAIL still engaged");
        return out;
    }

    private List<String> engaged(Actor other) {
        Character a = ((SyntheticPlayer) actor(1)).character();
        Character b = ((SyntheticPlayer) other).character();
        boolean ok = a.getPartnerId() == b.getId() && b.getPartnerId() == a.getId();
        return List.of((ok ? "PASS " : "FAIL ") + a.getName() + " partner " + a.getPartnerId() + ", " + b.getName()
                + " partner " + b.getPartnerId() + ", ring item " + a.getMarriageItemId() + "/" + b.getMarriageItemId());
    }
}
