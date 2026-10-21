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
}
