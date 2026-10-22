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
                case "npcPrices" -> npcPrices();
                case "cosmeticStock" -> cosmeticStock();
                case "botShops" -> botShops();
                case "clumpsOnMyMap" -> clumpsOnMyMap(me, Integer.parseInt(a[0]), Integer.parseInt(a[1]));
                case "nearbyBot" -> nearbyBot(me);
                case "buddy" -> buddy(me, a[0], a[1]);
                case "engaged" -> engaged(actor(Integer.parseInt(a[0])));
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
        List<String> bad = new ArrayList<>();
        if (per.length != 5) {
            bad.add(per.length + " channels, want 5");
        }
        for (int i = 0; i < Math.min(4, per.length); i++) {
            if (per[i] == 0) {
                bad.add("ch" + (i + 1) + " has no bots");
            }
            if (per[i] > per[0]) {
                bad.add("ch" + (i + 1) + " busier than ch1");
            }
        }
        if (per.length >= 5 && per[4] > 0) {
            bad.add("ch5 has " + per[4] + " bots, want 0");
        }
        out.add(bad.isEmpty() ? "PASS" + sb : "FAIL " + String.join(", ", bad));
        out.add("bots per channel:" + sb);
        out.add(BotChannels.describe());
        return out;
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
        if (emptyOnline > 0) {
            bad.add(emptyOnline + " guild(s) with nobody online");
        }
        out.add(bad.isEmpty() ? "PASS 10 bot guilds, " + rivals / 2 + " rival pairs" : "FAIL " + String.join("; ", bad));
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

    /** t-bb-1: my buddy entry for a name: want "accepted", "pending" or "none". */
    private static List<String> buddy(Character me, String name, String want) {
        var e = me.getBuddylist().get(name);
        String state = e == null ? "none" : e.isVisible() ? "accepted" : "pending";
        return List.of((state.equals(want) ? "PASS " : "FAIL ") + name + " on " + me.getName() + "'s buddy list: " + state
                + " (want " + want + ")");
    }

    /** t-mr-1: player 1 and the given player are engaged to each other. */
    private List<String> engaged(Actor other) {
        Character a = ((SyntheticPlayer) actor(1)).character();
        Character b = ((SyntheticPlayer) other).character();
        boolean ok = a.getPartnerId() == b.getId() && b.getPartnerId() == a.getId();
        return List.of((ok ? "PASS " : "FAIL ") + a.getName() + " partner " + a.getPartnerId() + ", " + b.getName()
                + " partner " + b.getPartnerId() + ", ring item " + a.getMarriageItemId() + "/" + b.getMarriageItemId());
    }
}
