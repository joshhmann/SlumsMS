package soloMapling.qa;

import client.Character;
import client.Client;
import client.command.commands.gm3.BotShowcaseCommand;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import server.ItemInformationProvider;
import server.life.NPC;
import server.maps.HiredMerchant;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSellerCaps;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.FMBot;
import soloMapling.itemPool.ItemDatabase;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/** {@link QaWorld} on the running server. */
final class GameQaWorld implements QaWorld {

    private static final InventoryType[] BAGS = {InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
            InventoryType.ETC, InventoryType.CASH};

    @Override
    public Character online(int characterId) {
        for (World w : Server.getInstance().getWorlds()) {
            Character c = w.getPlayerStorage().getCharacterById(characterId);
            if (c != null && c.isLoggedinWorld()) {
                return c;
            }
        }
        return null;
    }

    @Override
    public void message(Character gm, String line) {
        gm.yellowMessage(line);
    }

    @Override
    public boolean warp(Character gm, int mapId) {
        MapleMap target = gm.getClient().getChannelServer().getMapFactory().getMap(mapId);
        if (target == null) {
            return false;
        }
        gm.changeMap(target, target.getRandomPlayerSpawnpoint());
        return true;
    }

    @Override
    public boolean warpToPortal(Character gm, int mapId, String portalName) {
        MapleMap target = gm.getClient().getChannelServer().getMapFactory().getMap(mapId);
        Portal portal = target == null ? null : target.getPortal(portalName);
        if (portal == null) {
            return false;
        }
        gm.changeMap(target, portal);
        return true;
    }

    @Override
    public String whereIs(Character gm) {
        MapleMap map = gm.getMap();
        Point p = gm.getPosition();
        Portal nearest = map == null || p == null ? null : map.findClosestPortal(p);
        return "map " + gm.getMapId() + " at " + (p == null ? "?" : "(" + p.x + ", " + p.y + ")")
                + (nearest == null ? "" : ", nearest portal " + nearest.getName() + " "
                + (int) nearest.getPosition().distance(p) + " px away");
    }

    @Override
    public int meso(Character gm) {
        return gm.getMeso();
    }

    @Override
    public void gainMeso(Character gm, int delta) {
        gm.gainMeso(delta, true);
    }

    @Override
    public int giveItem(Character gm, int itemId, int quantity) {
        if (ItemInformationProvider.getInstance().getName(itemId) == null || quantity <= 0) {
            return 0;
        }
        if (!InventoryManipulator.checkSpace(gm.getClient(), itemId, quantity, "")) {
            return 0;
        }
        return InventoryManipulator.addById(gm, itemId, (short) quantity, "", -1, (short) 0, -1) ? quantity : 0;
    }

    @Override
    public boolean givePet(Character gm, int petItemId, int days) {
        if (!InventoryManipulator.checkSpace(gm.getClient(), petItemId, 1, "")) {
            return false;
        }
        int petId = Pet.createPet(petItemId);
        long expiration = System.currentTimeMillis() + days * 24L * 3600_000L;
        return InventoryManipulator.addById(gm, petItemId, (short) 1, gm.getName(), petId, expiration);
    }

    @Override
    public int countItem(Character gm, int itemId) {
        Inventory inv = gm.getInventory(ItemConstants.getInventoryType(itemId));
        return inv == null ? 0 : inv.countById(itemId);
    }

    @Override
    public int takeItem(Character gm, int itemId, int quantity) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        int have = countItem(gm, itemId);
        int take = Math.min(have, quantity);
        if (take > 0) {
            InventoryManipulator.removeById(gm.getClient(), type, itemId, take, false, false);
        }
        return take;
    }

    @Override
    public boolean petSummoned(Character gm, int petItemId) {
        for (Pet pet : gm.getPets()) {
            if (pet != null && pet.getItemId() == petItemId) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String itemName(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? String.valueOf(itemId) : name;
    }

    @Override
    public Integer marketValue(int itemId) {
        return ItemDatabase.getInstance().getItemPrice(itemId);
    }

    @Override
    public Point npcPosition(Character gm, int npcId) {
        NPC npc = gm.getMap() == null ? null : gm.getMap().getNPCById(npcId);
        return npc == null ? null : new Point(npc.getPosition());
    }

    @Override
    public List<Integer> spawnIdleBots(Character gm, Point around, int count) {
        List<Integer> ids = new ArrayList<>();
        MapleMap map = gm.getMap();
        int slotBase = BotStore.nextDynamicSlots(count);
        for (int i = 0; i < count; i++) {
            Point at = new Point(around.x + (i % 2 == 0 ? 1 : -1) * (50 + 40 * (i / 2)), around.y);
            int id = BotGeneration.createBot(slotBase + i, at, map);
            if (id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    public List<Integer> spawnFmShoppers(Character gm, int count) {
        List<Integer> ids = new ArrayList<>();
        MapleMap map = gm.getMap();
        int room = gm.getMapId() - QaScenarios.FM_ENTRANCE;
        int slotBase = BotStore.nextDynamicSlots(count);
        Point pos = gm.getPosition();
        for (int i = 0; i < count; i++) {
            int id = BotGeneration.createBot(slotBase + i, new Point(pos.x + 60 * (i + 1), pos.y), map);
            Character bot = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (bot == null) {
                continue;
            }
            BotTypeManager.convertBotType(bot, BotTypeManager.BotType.FM_BOT);
            if (CharacterStorage.getBotById(id) instanceof FMBot fm && room >= 1 && room <= 12) {
                fm.pinRoom(room);
            }
            ids.add(id);
        }
        return ids;
    }

    @Override
    public List<Integer> spawnShowcase(Character gm, String[] args, List<String> report) {
        BotShowcaseCommand.Request request = BotShowcaseCommand.parse(args);
        return request == null ? List.of() : BotShowcaseCommand.spawn(gm, request, report);
    }

    @Override
    public int stageAffinityBot(Character gm, String state, boolean training) {
        soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState s =
                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.parse(state);
        return s == null ? -1 : soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStaging.stage(gm, s, training);
    }

    @Override
    public void unstageAffinityBot(int botId) {
        soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStaging.unstage(botId);
    }

    @Override
    public boolean setBotDramaOff(Character gm, boolean off) {
        boolean was = soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.optedOut(gm.getId());
        soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.setOptedOut(gm, off);
        return was;
    }

    @Override
    public String affinityOf(int botId, Character gm) {
        String spree = soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.spreeOf(botId);
        return soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.describe(botId, gm.getId())
                + (spree == null ? "" : "; " + spree);
    }

    @Override
    public String forceGrudge(int botId, Character gm) {
        if (!soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.forceGrudge(botId, gm.getId())) {
            return "no grudge (not loaded, or @botdrama off)";
        }
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot != null && bot.getMapId() == gm.getMapId()) {
            soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.forceSpree(bot, gm);
            return "grudge + KS spree";
        }
        return "grudge (bot not on your map, no spree)";
    }

    @Override
    public int[] stageCouple(Character gm, String kind, Boolean sameGender) {
        soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Stage stage = switch (kind) {
            case "married" -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Stage.MARRIED;
            case "friends" -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Stage.FRIENDS;
            default -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Stage.DATING;
        };
        return soloMapling.ArtificialPlayer.BotCoupleSystem.CoupleStaging.stage(gm, stage, sameGender);
    }

    @Override
    public void unstageCouple(int[] ids) {
        soloMapling.ArtificialPlayer.BotCoupleSystem.CoupleStaging.unstage(ids);
    }

    @Override
    public void coupleStep() {
        soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.stepNow();
    }

    @Override
    public String startWedding(int botId, String by) {
        soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Couple c =
                soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.coupleOf(botId);
        return c == null ? "that bot is in no couple" : soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings.start(c, by);
    }

    @Override
    public int stopWeddings(String by) {
        int n = 0;
        for (soloMapling.worldevents.ActiveEvent e : soloMapling.worldevents.WorldEvents.getInstance().active()) {
            if (e.template.kind() == soloMapling.worldevents.EventTemplate.Kind.WEDDING
                    && soloMapling.worldevents.WorldEvents.getInstance().stop(e.id, by)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public boolean onEventChannel(Character gm) {
        try {
            return gm.getClient().getChannel() == soloMapling.server.SoloMaplingConstants.mainChannel().getId();
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public void despawnBot(int botId) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot == null) {
            return; // already gone (a showcase bot leaves by itself when its time is up)
        }
        BotSM sm = CharacterStorage.getBotById(botId);
        if (sm != null) {
            sm.setRunning(false);
            sm.stopScheduledTask();
        }
        BotGeneration.removeBotFromServer(bot);
    }

    @Override
    public String openPlayerShop(Character gm, String description, List<int[]> stock) {
        if (gm.getPlayerShop() != null || gm.getHiredMerchant() != null) {
            return "you already have a shop or merchant open";
        }
        Client c = gm.getClient();
        int permit = QaScenarios.PLAYER_SHOP_PERMIT;
        if (gm.getInventory(InventoryType.CASH).countById(permit) < 1) {
            return "no player shop permit " + permit;
        }
        PlayerShop shop = new PlayerShop(gm, description, permit);
        for (int[] s : stock) {
            InventoryType type = ItemConstants.getInventoryType(s[0]);
            Item item = gm.getInventory(type).findById(s[0]);
            if (item == null) {
                continue;
            }
            Item one = item.copy();
            one.setQuantity((short) 1);
            if (!shop.addItem(new PlayerShopItem(one, (short) 1, s[1]))) {
                break;
            }
            InventoryManipulator.removeFromSlot(c, type, item.getPosition(), (short) 1, true);
        }
        if (shop.getItems().isEmpty()) {
            return "none of the stock was in the inventory";
        }
        gm.setPlayerShop(shop);
        gm.getMap().addMapObject(shop);
        shop.sendShop(c);
        c.getWorldServer().registerPlayerShop(shop);
        gm.getMap().broadcastMessage(PacketCreator.updatePlayerShopBox(shop));
        shop.setOpen(true);
        return null;
    }

    @Override
    public boolean closePlayerShop(Character gm) {
        PlayerShop shop = gm.getPlayerShop();
        if (shop == null || !shop.isOwner(gm)) {
            return false;
        }
        gm.closePlayerShop();
        return true;
    }

    @Override
    public String closeOwnMerchant(Character gm) {
        for (Channel ch : gm.getWorldServer().getChannels()) {
            HiredMerchant merchant = ch.getHiredMerchants().get(gm.getId());
            if (merchant != null) {
                int items = merchant.getItems().size();
                ch.removeHiredMerchant(gm.getId());
                merchant.forceClose(); // exactly what Channel.closeAllMerchants does on shutdown
                return "Closed your hired merchant on channel " + ch.getId() + " (" + items
                        + " item line(s)); Fredrick holds them now.";
            }
        }
        return "You have no hired merchant open.";
    }

    @Override
    public List<String> channelLoads() {
        List<String> out = new ArrayList<>();
        out.add("CHANNEL_LOAD " + YamlConfig.config.server.CHANNEL_LOAD + ", bot weight " + System.getProperty(
                Channel.BOT_LOAD_WEIGHT_PROPERTY, String.valueOf(Channel.DEFAULT_BOT_LOAD_WEIGHT)));
        for (World w : Server.getInstance().getWorlds()) {
            for (Channel ch : w.getChannels()) {
                int total = ch.getPlayerStorage().getSize();
                int real = ch.getPlayerStorage().getConnectedPlayerCount();
                out.add("world " + w.getId() + " ch " + ch.getId() + ": " + real + " players, " + (total - real)
                        + " bots, bar " + ch.getChannelCapacity() + "/800");
            }
        }
        return out;
    }

    @Override
    public String sellerCapStatus(Character gm) {
        return BotSellerCaps.status(gm.getAccountID());
    }

    @Override
    public int guildCost() {
        return YamlConfig.config.server.CREATE_GUILD_COST;
    }

    @Override
    public Point busiestMobSpot(Character gm) {
        MapleMap map = gm.getMap();
        if (map == null) {
            return null;
        }
        List<Point> mobs = new ArrayList<>();
        for (server.life.Monster m : map.getAllMonsters()) {
            if (m.isAlive() && m.getPosition() != null && (m.getStats() == null || !m.getStats().isFriendly())) {
                mobs.add(m.getPosition());
            }
        }
        Point best = null;
        int bestCount = 0;
        for (Point p : mobs) {
            int n = 0;
            for (Point q : mobs) {
                if (Math.abs(p.x - q.x) <= 250 && Math.abs(p.y - q.y) <= 90) {
                    n++;
                }
            }
            if (n > bestCount) {
                bestCount = n;
                best = p;
            }
        }
        if (best == null) {
            return null;
        }
        Point ground = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.groundPointBelow(map, best.x, best.y - 5);
        return ground != null ? ground : new Point(best);
    }

    @Override
    public int liveMobCount(Character gm) {
        MapleMap map = gm.getMap();
        if (map == null) {
            return 0;
        }
        int n = 0;
        for (server.life.Monster m : map.getAllMonsters()) {
            if (m.isAlive() && (m.getStats() == null || !m.getStats().isFriendly())) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void holdIdle(int botId, String reason) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot != null) {
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.enable(bot);
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.setIdle(bot, reason);
        }
    }

    @Override
    public String idleStats(int botId) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot == null) {
            return null;
        }
        String stats = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.idleSafetyStats(bot);
        Point p = bot.getPosition();
        return bot.getName() + " at (" + p.x + "," + p.y + "): " + (stats == null ? "not driven" : stats);
    }

    @Override
    public boolean setIdleSafety(boolean on) {
        boolean was = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isIdleSafetyEnabled();
        soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.setIdleSafetyEnabled(on);
        return was;
    }

    @Override
    public int giveBotItem(int botId, int itemId, int quantity) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot == null || quantity <= 0) {
            return 0;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP) {
            int given = 0;
            for (int i = 0; i < quantity; i++) {
                Item equip = ii.getEquipById(itemId);
                if (equip != null && soloMapling.ArtificialPlayer.BotHoldings.grantItem(bot, equip)) {
                    given++;
                }
            }
            return given;
        }
        return soloMapling.ArtificialPlayer.BotHoldings.grantItem(bot, new Item(itemId, (short) 0, (short) quantity))
                ? quantity : 0;
    }

    @Override
    public List<String> botScrollStep(int botId, String appetite) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot == null) {
            return List.of("bot " + botId + " is gone");
        }
        soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Appetite a =
                soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Appetite.parse(appetite,
                        soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Appetite.of(botId));
        return soloMapling.ArtificialPlayer.BotScrollSystem.BotScrolling.stepNow(bot, a);
    }

    @Override
    public String gachaRoll(Character gm, String town, String tier) {
        soloMapling.Gachapon.GachaPools.Town t = soloMapling.Gachapon.GachaPools.get().find(town);
        if (t == null) {
            return "no gachapon town '" + town + "'";
        }
        soloMapling.Gachapon.GachaPools.Tier forced = soloMapling.Gachapon.GachaPools.Tier.of(tier);
        soloMapling.Gachapon.GachaMachine.Result r = soloMapling.Gachapon.GachaMachine.playForPlayer(gm, t, 0,
                "qa roll", java.util.concurrent.ThreadLocalRandom.current(), forced);
        if (!r.ok()) {
            return "no roll: " + r.refusal();
        }
        return String.format("%s at %s: %s %s (%d) x%d, %s", gm.getName(), t.name(), r.prize().tier().key(),
                itemName(r.prize().itemId()), r.prize().itemId(), r.prize().quantity(), r.announced() ? "ANNOUNCED"
                        : r.prize().announce() ? "notice held back or queued by the rate limit" : "not announced (tier)");
    }

    @Override
    public List<String> gachaOdds(String town, int simulateRolls) {
        soloMapling.Gachapon.GachaPools pools = soloMapling.Gachapon.GachaPools.get();
        soloMapling.Gachapon.GachaPools.Town t = pools.find(town);
        if (t == null) {
            return List.of("no gachapon town '" + town + "'");
        }
        List<String> out = new ArrayList<>();
        soloMapling.Gachapon.GachaPools.Simulation sim = simulateRolls > 0
                ? pools.simulate(t, simulateRolls, java.util.concurrent.ThreadLocalRandom.current()) : null;
        for (soloMapling.Gachapon.GachaPools.TierOdds o : pools.odds(t)) {
            String line = String.format("%s %s: %s per ticket, %d items", t.name(), o.tier().key(),
                    server.gachapon.Gachapon.pct(o.chance()), o.items().size());
            if (sim != null) {
                int got = sim.tiers().getOrDefault(o.tier(), 0);
                line += String.format("; %,d of %,d simulated = %s", got, sim.rolls(),
                        server.gachapon.Gachapon.pct((double) got / Math.max(1, sim.rolls())));
            }
            out.add(line);
        }
        return out;
    }

    @Override
    public int gachaMap(String town) {
        soloMapling.Gachapon.GachaPools.Town t = soloMapling.Gachapon.GachaPools.get().find(town);
        return t == null || t.maps().isEmpty() ? 0 : t.maps().get(t.maps().size() - 1);
    }

    @Override
    public int giveBotMeso(int botId, int amount) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        return bot != null && amount > 0 && soloMapling.ArtificialPlayer.BotHoldings.grantMeso(bot, amount) ? amount : 0;
    }

    @Override
    public long botCeiling(int botId) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        return bot == null ? -1 : soloMapling.ArtificialPlayer.BotWealth.ceiling(bot);
    }

    @Override
    public String botWealthLine(int botId) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        return bot == null ? "bot " + botId + " is gone" : soloMapling.ArtificialPlayer.BotWealth.line(bot);
    }

    @Override
    public List<String> botWealthStep(int botId) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot == null) {
            return List.of("bot " + botId + " is gone");
        }
        return soloMapling.ArtificialPlayer.BotWealth.step(bot, java.util.concurrent.ThreadLocalRandom.current(), true);
    }

    // ---- 1.5.0 bot guilds ----

    @Override
    public List<String> botGuildList() {
        return soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.describe();
    }

    @Override
    public String pickBotGuild(String wanted, boolean withRival) {
        var all = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.botGuilds();
        if (wanted != null) {
            var g = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.byName(wanted);
            return g == null ? null : g.name();
        }
        String best = null;
        int most = -1;
        for (var g : all) {
            if (withRival && (g.rivalId() <= 0 || soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guild(g.rivalId()) == null)) {
                continue;
            }
            int n = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membersOf(g.guildId()).size();
            if (n > most) {
                most = n;
                best = g.name();
            }
        }
        return best;
    }

    @Override
    public String rivalOf(String guild) {
        var g = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.byName(guild);
        var r = g == null ? null : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guild(g.rivalId());
        return r == null ? null : r.name();
    }

    @Override
    public int stageGuildBot(Character gm, String state, String guild, int rank) {
        var feels = soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.parse(state);
        if (feels == null) {
            feels = soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.NEUTRAL;
        }
        int gid = 0;
        if ("mine".equals(guild)) {
            gid = gm.getGuildId();
        } else if (guild != null) {
            var g = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.byName(guild);
            gid = g == null ? -1 : g.guildId();
        }
        if (gid < 0 || (guild != null && gid == 0)) {
            return -1;
        }
        return soloMapling.ArtificialPlayer.BotGuildSystem.GuildStaging.stage(gm, feels, gid, rank);
    }

    @Override
    public void unstageGuildBot(int botId) {
        soloMapling.ArtificialPlayer.BotGuildSystem.GuildStaging.unstage(botId);
    }

    @Override
    public List<String> botGuildAction(Character gm, String action, String[] args) {
        String first = args.length > 0 ? args[0] : null;
        var g = first == null ? null : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.byName(first);
        List<String> out = new ArrayList<>();
        switch (action) {
            case "recruit" -> out.addAll(g == null ? List.of("no bot guild " + first)
                    : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.recruitNow(g));
            case "jab" -> out.addAll(g == null ? List.of("no bot guild " + first)
                    : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.jabNow(g));
            case "bossrun" -> {
                if (g == null) {
                    out.add("no bot guild " + first);
                } else {
                    var boss = args.length > 1 ? soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.boss(args[1]) : null;
                    String problem = soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns.start(g, boss, true, gm.getName(),
                            gm.getClient().getChannel());
                    int entrance = soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns.entranceMap();
                    if (problem == null && entrance > 0 && gm.getMapId() != entrance) {
                        warp(gm, entrance);
                    }
                    out.add(problem == null ? "boss run started at map " + entrance + " (you were warped there)"
                            : "no boss run: " + problem);
                }
            }
            case "bossstop" -> out.add("stopped " + soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns.stop(gm.getName()) + " run(s)");
            case "kick", "kickcheck" -> {
                if (g == null) {
                    out.add("no bot guild " + first);
                } else {
                    if ("kick".equals(action)) {
                        int leader = g.leaderId();
                        boolean set = soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.stage(leader, gm.getId(),
                                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.RIVAL);
                        out.add("the leader of " + g.name() + " counts you as a rival: " + set);
                    }
                    out.addAll(soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.checkKicks(g.guildId(),
                            System.currentTimeMillis(), true));
                }
            }
            case "step" -> {
                soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.stepNow();
                out.add("guild step ran");
            }
            case "quit", "ignore", "rival" -> {
                int botId = first == null ? -1 : Integer.parseInt(first);
                var m = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membership(botId);
                if (m == null) {
                    out.add("bot " + botId + " is in no guild");
                } else if ("quit".equals(action)) {
                    out.add(soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.quit(botId,
                            soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.QuitReason.RIVALS, gm.getName()));
                } else {
                    if ("rival".equals(action)) {
                        soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.stage(botId, gm.getId(),
                                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.RIVAL);
                        out.add("bot " + botId + " now counts you as a rival");
                    } else {
                        soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.backdate(botId, 30);
                        out.add("bot " + botId + " joined and was last noticed 30 days ago");
                    }
                    List<String> quits = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.checkQuits(m.guildId(),
                            System.currentTimeMillis());
                    out.add(quits.isEmpty() ? "quit check: nobody quits" : "quit check: " + String.join("; ", quits));
                }
            }
            default -> out.add("unknown action " + action);
        }
        return out;
    }

    @Override
    public int guildIdOf(Character gm) {
        return gm.getGuildId();
    }

    @Override
    public boolean leaveGuild(Character gm) {
        if (gm.getGuildId() <= 0 || gm.getMGC() == null) {
            return false;
        }
        Server.getInstance().leaveGuild(gm.getMGC());
        gm.sendPacket(net.server.guild.GuildPackets.showGuildInfo(null));
        gm.getMGC().setGuildId(0);
        gm.getMGC().setGuildRank(5);
        gm.saveGuildStatus();
        gm.getMap().broadcastPacket(gm, net.server.guild.GuildPackets.guildNameChanged(gm.getId(), ""));
        return true;
    }

    @Override
    public boolean joinBotGuild(Character gm, String guild) {
        var g = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.byName(guild);
        return g != null && soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.joinPlayer(gm, g.guildId());
    }

    // ---- 1.5.0 rc3 (cb-1 .. cb-4, sp-1) ----

    @Override
    public List<Integer> stageGrinders(Character gm, List<String> jobs, int count, int levelOffset, boolean hold,
                                       boolean atGm) {
        List<client.Job> parsed = new ArrayList<>();
        for (String j : jobs) {
            client.Job job = soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.job(j);
            if (job != null) {
                parsed.add(job);
            }
        }
        Point at = atGm ? null : busiestMobSpot(gm);
        return soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.stage(gm, parsed, count, levelOffset, at, hold);
    }

    @Override
    public void unstageGrinders(List<Integer> ids) {
        soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.unstage(ids);
    }

    @Override
    public List<String> grinderReport(List<Integer> ids, String what) {
        return soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.report(ids, what);
    }

    @Override
    public List<String> spreadTally(Character gm) {
        return gm.getMap() == null ? List.of()
                : soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.spreadTally(gm.getMap());
    }

    @Override
    public List<String> grinderAction(List<Integer> ids, String action) {
        return soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.act(ids, action);
    }

    @Override
    public String combatTuning() {
        return soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning.describe();
    }
}
