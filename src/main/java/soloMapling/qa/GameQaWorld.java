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
import soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace;
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
            Point at = soloMapling.ArtificialPlayer.StageSpots.near(map, around); // rc6 (QA t-pb-1): not a row
            int id = BotGeneration.createBot(slotBase + i, at, map);
            if (id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    public List<Integer> spawnIdleBots(Character gm, Point around, int count, int level) {
        List<Integer> ids = new ArrayList<>();
        MapleMap map = gm.getMap();
        int slotBase = BotStore.nextDynamicSlots(count);
        for (int i = 0; i < count; i++) {
            Point at = soloMapling.ArtificialPlayer.StageSpots.near(map, around);
            int id = BotGeneration.createBot(slotBase + i, at, map, 0, level, level);
            if (id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    public String botLabel(Character gm, int botId) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot == null) {
            return "bot " + botId + " (gone)";
        }
        return label(bot.getName(), bot.getLevel(), gm == null ? -1 : gm.getMapId(), bot.getMapId(),
                gm == null || gm.getPosition() == null ? 0 : gm.getPosition().x,
                bot.getPosition() == null ? 0 : bot.getPosition().x);
    }

    /** "Aria (lv 34, 120 px to your right)". */
    static String label(String name, int level, int gmMap, int botMap, int gmX, int botX) {
        String where;
        if (gmMap != botMap) {
            where = "on map " + botMap;
        } else {
            int dx = botX - gmX;
            where = Math.abs(dx) < 30 ? "right next to you" : Math.abs(dx) + " px to your " + (dx < 0 ? "left" : "right");
        }
        return name + " (lv " + level + ", " + where + ")";
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
        // rc6 triage (t-bk-1/t-at-1): showcase bots stand still, and spawned on the GM's empty ledge half of them
        // never swung ("no targetable mobs within 700px"); spawn them where the mobs are
        return request == null ? List.of() : BotShowcaseCommand.spawn(gm, request, report, busiestMobSpot(gm));
    }

    @Override
    public int stageAffinityBot(Character gm, String state, boolean training) {
        soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState s =
                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.parse(state);
        return s == null ? -1 : soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStaging.stage(gm, s, training);
    }

    @Override
    public int stageConvoBot(Character gm, String state, boolean training, String voice) {
        int id = stageAffinityBot(gm, state, training);
        soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.Voice v =
                soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.Voice.parse(voice);
        if (id >= 0 && v != null) {
            soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.forceVoice(id - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE, v);
        }
        return id;
    }

    @Override
    public void unstageConvoBot(int botId) {
        soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.clearVoice(botId - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE);
        unstageAffinityBot(botId);
    }

    @Override
    public List<String> convoAction(Character gm, String action, List<Integer> bots, String[] args) {
        List<String> out = new java.util.ArrayList<>();
        switch (action) {
            case "persona" -> {
                for (int id : bots) {
                    int slot = id - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE;
                    soloMapling.ArtificialPlayer.BotConvo.ConvoPersona p =
                            soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.of(slot);
                    out.add("bot " + id + ": " + p.describe() + " | \"Are you going to Henesys? I'm busy right now.\" -> \""
                            + soloMapling.ArtificialPlayer.BotConvo.VoiceStyler.style(
                            "Are you going to Henesys? I'm busy right now.", p, slot, List.of()) + "\"");
                }
            }
            case "sense" -> {
                // review 2026-10-22: the Decide call is HTTP - off the command thread; the answer follows as a message
                String line = String.join(" ", args);
                soloMapling.server.ExecutorServiceManager.runAsync(() -> {
                    String said = senseLine(line);
                    gm.yellowMessage(said);
                    soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.CONVO, gm.getId(), "SENSE " + said);
                });
                out.add("reading \"" + line + "\" (the answer follows)");
            }
            case "thread" -> {
                Character a = bots.size() > 0 ? BotHelpers.getCharFromChannelStorage(bots.get(0)) : null;
                Character b = bots.size() > 1 ? BotHelpers.getCharFromChannelStorage(bots.get(1)) : null;
                if (a == null || b == null || a.getMap() == null) {
                    out.add("needs two staged bots on your map");
                    break;
                }
                soloMapling.ArtificialPlayer.BotConvo.BotThreads.Topic topic =
                        soloMapling.ArtificialPlayer.BotConvo.BotThreads.Topic.parse(args.length > 0 ? args[0] : "gossip");
                if (topic == soloMapling.ArtificialPlayer.BotConvo.BotThreads.Topic.GUILD_BEEF
                        && (a.getGuildId() <= 0 || b.getGuildId() <= 0)) {
                    topic = soloMapling.ArtificialPlayer.BotConvo.BotThreads.Topic.GOSSIP;
                }
                int channel = gm.getClient() == null ? 1 : gm.getClient().getChannel();
                out.add("thread: " + soloMapling.ArtificialPlayer.BotConvo.BotThreads.run(a.getMap(), channel, List.of(a, b),
                        topic, System.currentTimeMillis()));
                // auto-QA t-cv-5 answers the thread by its subject ("{thread}? i can come too"): a clear reply to it
                String about = soloMapling.ArtificialPlayer.BotConvo.BotThreads.aboutOf(channel, a.getMapId());
                if (about != null) {
                    out.add("VAR thread=" + about);
                }
            }
            case "last" -> out.addAll(soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.describeLast(gm));
            case "hold" -> {
                // !qa cv-9 (owner report 2026-10-24): the player's line, then the walk-away, so every run stages it
                Character a = bots.isEmpty() ? null : BotHelpers.getCharFromChannelStorage(bots.get(0));
                if (a == null || a.getMap() == null || gm.getMap() == null || a.getMapId() != gm.getMapId()) {
                    out.add("needs a staged bot on your map");
                    break;
                }
                String line = args.length == 0 ? "hey where are you going?" : String.join(" ", args);
                soloMapling.ArtificialPlayer.StageSpots.unpin(a.getId()); // the owner's bot is no pinned test prop
                soloMapling.ArtificialPlayer.BotSM sm =
                        soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(a.getId());
                boolean heard = sm != null
                        && soloMapling.ArtificialPlayer.BotConversation.say(sm, gm, line, null);
                out.add("you said \"" + line + "\"" + (heard ? "" : " (no model: its script answers)"));
                // the wander a town wanderer's ensureRoaming starts: the walk that used to carry it off mid-chat
                soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem.start(a);
                out.add(holdLine(gm, a, "the wander is on"));
            }
            case "sample" -> {
                Character a = bots.isEmpty() ? null : BotHelpers.getCharFromChannelStorage(bots.get(0));
                if (a == null || a.getMap() == null || gm.getMap() == null) {
                    out.add("needs a staged bot on your map");
                    break;
                }
                out.add(holdLine(gm, a, null));
            }
            case "ask" -> {
                // !qa cv-8: the bot says a scripted question in map chat, exactly like an ambient pack line
                Character a = bots.isEmpty() ? null : BotHelpers.getCharFromChannelStorage(bots.get(0));
                if (a == null || a.getMap() == null) {
                    out.add("needs a staged bot on your map");
                    break;
                }
                String question = args.length == 0 ? "anyone from the east coast?" : String.join(" ", args);
                soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(a, question);
                out.add(a.getName() + " asked the map: \"" + question + "\"");
            }
            case "bank" -> {
                boolean on = args.length == 0 || !"off".equalsIgnoreCase(args[0]);
                String was = System.getProperty("solomapling.convo.forceBank", "false");
                System.setProperty("solomapling.convo.forceBank", String.valueOf(on));
                out.add("bank mode " + (on ? "ON (LLM off for chat)" : "OFF (LLM on)") + ", was " + was);
            }
            default -> out.add("unknown chat step " + action);
        }
        return out;
    }

    /** How far a chatting bot may be from the player it is talking to, in px. */
    static final int HOLD_PX = 150;

    /** !qa cv-9: the distance that is logged every few seconds, and the QA log line the auto-QA check reads. */
    private static String holdLine(Character gm, Character bot, String note) {
        long left = soloMapling.ArtificialPlayer.BotConversation.holdMs(bot.getId());
        int px = (int) Math.round(Math.hypot(bot.getPosition().x - gm.getPosition().x,
                bot.getPosition().y - gm.getPosition().y));
        String line = "HOLD bot " + bot.getId() + " (" + bot.getName() + "): " + px + " px from you, "
                + Math.round(left / 1_000.0) + " s of hold left, " + (px <= HOLD_PX ? "with you" : "WANDERED OFF")
                + (note == null ? "" : " (" + note + ")");
        soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.CONVO, gm.getId(), line);
        return line;
    }

    /** How a line is read: rules, then the Decide call (blocking: call it off the command thread). */
    static String senseLine(String line) {
        soloMapling.ArtificialPlayer.BotConvo.ConvoSense.Read r = soloMapling.ArtificialPlayer.BotConvo.ConvoSense.read(
                line, soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide.get());
        return "\"" + line + "\": " + r.triage().describe() + " " + r.describe();
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
    public void holdKsWars(Character gm, boolean hold) {
        soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.holdWars(gm.getId(), hold);
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
            boolean taunt = soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.forceTaunt(
                    soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(botId), bot, gm);
            return "grudge + KS spree" + (taunt ? " + taunt" : "");
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
    public String startWedding(int botId, String by, int mapId) {
        soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Couple c =
                soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.coupleOf(botId);
        return c == null ? "that bot is in no couple"
                : soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings.start(c, by, mapId);
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
    public String pickBotGuildFor(Character applicant, String wanted) {
        if (wanted != null || applicant == null) {
            return pickBotGuild(wanted, false);
        }
        List<GuildCandidate> all = new ArrayList<>();
        for (var g : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.botGuilds()) {
            net.server.guild.Guild live = net.server.Server.getInstance().getGuild(g.guildId());
            int leader = live != null ? live.getLeaderId() : g.leaderId();
            boolean full = live != null && live.getMembers().size() >= live.getCapacity();
            all.add(new GuildCandidate(g.name(), soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membersOf(g.guildId()).size(),
                    leader > 0 && soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.hostile(leader, applicant.getId()), full));
        }
        return chooseForApplicant(all);
    }

    /** A bot guild as {@link #chooseForApplicant} weighs it. */
    record GuildCandidate(String name, int members, boolean leaderHostile, boolean full) {
    }

    /** The biggest guild whose leader would say yes (not hostile, not full); else the biggest; null when none. */
    static String chooseForApplicant(List<GuildCandidate> all) {
        GuildCandidate best = null;
        GuildCandidate biggest = null;
        for (GuildCandidate c : all) {
            if (biggest == null || c.members() > biggest.members()) {
                biggest = c;
            }
            if (!c.leaderHostile() && !c.full() && (best == null || c.members() > best.members())) {
                best = c;
            }
        }
        return best != null ? best.name() : biggest == null ? null : biggest.name();
    }

    @Override
    public String rivalOf(String guild) {
        var g = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.byName(guild);
        var r = g == null ? null : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guild(g.rivalId());
        return r == null ? null : r.name();
    }

    @Override
    public int stageGuildBot(Character gm, String state, String guild, int rank) {
        return stageGuildBot(gm, state, guild, rank, false);
    }

    @Override
    public int stageGuildGrinder(Character gm, String state, String guild, int rank) {
        return stageGuildBot(gm, state, guild, rank, true);
    }

    private int stageGuildBot(Character gm, String state, String guild, int rank, boolean grinder) {
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
        return soloMapling.ArtificialPlayer.BotGuildSystem.GuildStaging.stage(gm, feels, gid, rank, grinder);
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
            case "sure" -> { // !qa gu-2: these staged friends take an invite under the soft limit for sure
                for (String a : args) {
                    if (a.matches("-?\\d+")) {
                        soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.qaSureJoin(Integer.parseInt(a), true);
                    }
                }
                out.add("sure yes under the soft limit: " + String.join(" ", args));
            }
            case "unsure" -> {
                for (String a : args) {
                    if (a.matches("-?\\d+")) {
                        soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.qaSureJoin(Integer.parseInt(a), false);
                    }
                }
                out.add("real dice again: " + String.join(" ", args));
            }
            case "unkick" -> { // !qa gu-8 stop: the leader made a rival by "kick" is neutral to the GM again
                if (g == null) {
                    out.add("no bot guild " + first);
                } else {
                    boolean set = soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.stage(g.leaderId(), gm.getId(),
                            soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.NEUTRAL);
                    out.add("the leader of " + g.name() + " is neutral to you again: " + set);
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
        List<Integer> levels = new ArrayList<>();
        for (String j : jobs) {
            String[] w = j.split(":", 2); // "JOB" or "JOB:level" (cb-2 stages near the map's mob level)
            client.Job job = soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.job(w[0]);
            if (job != null) {
                parsed.add(job);
                levels.add(w.length > 1 && w[1].matches("\\d{1,3}") ? Integer.parseInt(w[1]) : 0);
            }
        }
        Point at = atGm ? null : busiestMobSpot(gm);
        return soloMapling.ArtificialPlayer.BotCombatSystem.CombatStaging.stage(gm, parsed, levels, count, levelOffset,
                at, hold);
    }

    @Override
    public int mobLevel(Character gm) {
        return soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.level(gm.getMapId());
    }

    @Override
    public int playerHitsMobs(Character gm, int rangeX, int rangeY, int max) {
        MapleMap map = gm.getMap();
        Point p = gm.getPosition();
        if (map == null || p == null) {
            return 0;
        }
        // auto-QA rv-2: a "fight" the pretend player stands far away for is no fight - the mobs it damages sit on
        // another platform, the bots it stages for the drama land elsewhere, and nobody can ever take a mob from it.
        // Walk the player onto the nearest mob first (a GM teleport, same map), whenever it is not already there.
        server.life.Monster nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (server.life.Monster m : map.getAllMonsters()) {
            Point mp = m.getPosition();
            if (m.isAlive() && mp != null && m.getHp() > 1) {
                double d = p.distanceSq(mp);
                if (d < bestSq) {
                    bestSq = d;
                    nearest = m;
                }
            }
        }
        if (nearest == null) {
            return 0;
        }
        Point mp = nearest.getPosition();
        if (Math.abs(mp.x - p.x) > 100 || Math.abs(mp.y - p.y) > 60) {
            gm.changeMap(map, new Point(mp.x, mp.y)); // a GM teleport onto the mobs, same map
            p = gm.getPosition();
        }
        List<server.life.Monster> near = within(map, p, rangeX, rangeY);
        Point at = p;
        near.sort(java.util.Comparator.comparingDouble(m -> at.distanceSq(m.getPosition())));
        List<String> hit = new ArrayList<>();
        int n = 0;
        for (server.life.Monster m : near) {
            if (n >= max) {
                break;
            }
            m.applyFakeDamage(gm, 1, true); // the game's own attacker record: Monster.isAttackedBy(gm) from now on
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.AFFINITY)) {
                hit.add(m.getObjectId() + "@" + m.getPosition().x + "," + m.getPosition().y);
            }
            n++;
        }
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.AFFINITY)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.AFFINITY, gm.getMapId(), "FIGHT "
                    + gm.getName() + " at " + p.x + "," + p.y + " hit " + hit);
        }
        return n;
    }

    /** The living mobs around {@code p} that a fake hit can land on (they keep at least 1 HP). */
    private static List<server.life.Monster> within(MapleMap map, Point p, int rangeX, int rangeY) {
        List<server.life.Monster> near = new ArrayList<>();
        for (server.life.Monster m : map.getAllMonsters()) {
            Point mp = m.getPosition();
            if (m.isAlive() && mp != null && m.getHp() > 1 && Math.abs(mp.x - p.x) <= rangeX
                    && Math.abs(mp.y - p.y) <= rangeY) {
                near.add(m);
            }
        }
        return near;
    }

    @Override
    public String stepOntoBot(Character gm, List<Integer> botIds, int minAwayPx) {
        MapleMap map = gm.getMap();
        Point p = gm.getPosition();
        if (map == null || p == null) {
            return null;
        }
        for (int id : botIds) {
            Character bot = map.getCharacterById(id);
            Point bp = bot == null ? null : bot.getPosition();
            if (bp == null || bp.distance(p) < minAwayPx) {
                continue;
            }
            gm.changeMap(map, new Point(bp)); // a GM teleport onto the bot's spot, same map
            return bot.getName() + " at (" + bp.x + "," + bp.y + ")";
        }
        return null;
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
    public boolean personalSpaceApplies(Character gm) {
        return soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.appliesOn(gm.getMap());
    }

    @Override
    public List<Integer> stageStackedTownBots(Character gm, int count) {
        MapleMap map = gm.getMap();
        Point at = new Point(gm.getPosition());
        List<Integer> ids = new ArrayList<>();
        int slotBase = BotStore.nextDynamicSlots(count);
        for (int i = 0; i < count; i++) {
            int id = BotGeneration.createBot(slotBase + i, at, map); // on purpose: all on the GM's pixel
            if (id > 0) {
                ids.add(id);
            }
        }
        if (!ids.isEmpty()) {
            soloMapling.ArtificialPlayer.BotTypeManager.setAndStartBots(ids,
                    soloMapling.ArtificialPlayer.BotTypeManager.BotType.SOCIAL_BOT);
        }
        return ids;
    }

    @Override
    public List<Integer> stageNpcTownBot(Character gm) {
        MapleMap map = gm.getMap();
        for (server.maps.MapObject object : map.getMapObjects()) {
            if (object.getType() != server.maps.MapObjectType.NPC) {
                continue;
            }
            Point at = object.getPosition();
            Point ground = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.groundPointBelow(map, at.x, at.y);
            if (ground == null || Math.abs(ground.y - at.y) > PersonalSpace.SAME_FH_DY) {
                continue;
            }
            int id = BotGeneration.createBot(BotStore.nextDynamicSlots(1), ground, map);
            if (id > 0) {
                // No FSM or fidget: only the ordinary personal-space check may move this fixture.
                Character bot = map.getCharacterById(id);
                soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.enable(bot);
                return List.of(id);
            }
        }
        return List.of();
    }

    @Override
    public List<String> npcClearance(Character gm, List<Integer> ids) {
        List<String> lines = new ArrayList<>();
        MapleMap map = gm.getMap();
        for (int id : ids) {
            Character bot = map.getCharacterById(id);
            if (bot == null) {
                lines.add("NPC CLEARANCE bot " + id + " missing");
                continue;
            }
            soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.maybeKeep(bot);
            int nearest = Integer.MAX_VALUE;
            for (server.maps.MapObject object : map.getMapObjects()) {
                if (object.getType() == server.maps.MapObjectType.NPC
                        && Math.abs(object.getPosition().y - bot.getPosition().y) <= PersonalSpace.SAME_FH_DY) {
                    nearest = Math.min(nearest, Math.abs(object.getPosition().x - bot.getPosition().x));
                }
            }
            lines.add("NPC CLEARANCE bot " + id + " dx=" + nearest + " clear="
                    + (nearest != Integer.MAX_VALUE && nearest >= PersonalSpace.CROWD_PX));
        }
        return lines;
    }

    @Override
    public List<String> overlapTally(Character gm) {
        MapleMap map = gm.getMap();
        List<Character> chars = new ArrayList<>();
        for (Character c : map.getAllPlayers()) {
            if (c != null && !c.isHidden() && c.getPosition() != null) {
                chars.add(c);
            }
        }
        int overlap = 0, close = 0;
        List<String> worst = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            for (int j = i + 1; j < chars.size(); j++) {
                Point a = chars.get(i).getPosition(), b = chars.get(j).getPosition();
                int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
                if (dy > soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.SAME_FH_DY) {
                    continue;
                }
                boolean pair = soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.deliberate(chars.get(i),
                        chars.get(j));
                if (dx < 12 && dy < 5 && !pair) {
                    overlap++;
                    if (worst.size() < 5) {
                        worst.add(chars.get(i).getName() + " / " + chars.get(j).getName() + " dx " + dx);
                    }
                } else if (dx < soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.CROWD_PX && !pair) {
                    close++;
                }
            }
        }
        return List.of(chars.size() + " characters on map " + map.getId() + ": " + overlap
                + " overlapping pair(s) (|dx| < 12, |dy| < 5), " + close + " more closer than "
                + soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.CROWD_PX + " px"
                + (worst.isEmpty() ? "" : "; " + String.join(", ", worst)));
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
    public List<String> stagePets(List<Integer> ids, boolean loots) {
        return soloMapling.ArtificialPlayer.BotPets.stageAll(ids, loots);
    }

    @Override
    public List<String> petReport(List<Integer> ids) {
        return soloMapling.ArtificialPlayer.BotPets.report(ids);
    }

    @Override
    public List<String> petDrops(Character gm, List<Integer> ids) {
        return soloMapling.ArtificialPlayer.BotPets.dropsFor(gm, ids);
    }

    @Override
    public String combatTuning() {
        return soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning.describe();
    }

    @Override
    public List<String> dramaAction(Character gm, String area, String... words) {
        return soloMapling.ArtificialPlayer.BotDramaSystem.DramaGm.run(gm, area, words);
    }

    @Override
    public int dramaUnstage(int gmId) {
        return soloMapling.ArtificialPlayer.BotDramaSystem.DramaGm.unstageAll(gmId);
    }

    @Override
    public boolean romanceOn(Character gm) {
        return soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.romanceOn(gm.getId());
    }

    @Override
    public String dropGodly(Character gm, int itemId) {
        return server.GodlyDrops.stageDrop(gm, itemId);
    }
}
