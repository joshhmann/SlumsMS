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
    public List<Integer> spawnOpqLobbyBots(Character gm, Point around, int count) {
        List<Integer> ids = new ArrayList<>();
        MapleMap map = gm.getMap();
        int slotBase = BotStore.nextDynamicSlots(count);
        for (int i = 0; i < count; i++) {
            Point at = soloMapling.ArtificialPlayer.StageSpots.near(map, around);
            int id = BotGeneration.createBot(slotBase + i, at, map, 0, 55, 65);
            if (id > 0) {
                ids.add(id);
            }
        }
        BotTypeManager.setAndStartBots(ids, BotTypeManager.BotType.OPQ_BOT);
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
        List<GuildCandidate> candidates = new ArrayList<>();
        for (var g : all) {
            if (withRival && (g.rivalId() <= 0 || soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guild(g.rivalId()) == null)) {
                continue;
            }
            candidates.add(new GuildCandidate(g.name(), soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membersOf(g.guildId()).size(),
                    false, noRoomForScene(g.guildId())));
        }
        return chooseForApplicant(candidates);
    }

    /**
     * Whether a bot guild is too full for a QA scene: the scene stages one bot in it and then has it take one more
     * (a player's application, a recruit, a poach), and a poach keeps {@link
     * soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules#PLAYER_ROOM} slots free. Auto-QA 1.5.2: the biggest guild
     * was picked whatever its room, so once Legendz reached 49 of 50 the staged officer filled it and gu-1 heard FULL,
     * gu-3 "Legendz is full", and gu-8, gw-1 and gw-3 could stage nobody.
     */
    static boolean noRoomForScene(int members, int capacity) {
        return !soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.roomToPoach(members + 1, capacity);
    }

    private static boolean noRoomForScene(int guildId) {
        net.server.guild.Guild live = net.server.Server.getInstance().getGuild(guildId);
        return live != null && noRoomForScene(live.getMembers().size(), live.getCapacity());
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
            boolean full = live != null && noRoomForScene(live.getMembers().size(), live.getCapacity());
            all.add(new GuildCandidate(g.name(), soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membersOf(g.guildId()).size(),
                    leader > 0 && soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.hostile(leader, applicant.getId()), full));
        }
        return chooseForApplicant(all);
    }

    /** A bot guild as {@link #chooseForApplicant} weighs it. */
    record GuildCandidate(String name, int members, boolean leaderHostile, boolean full) {
    }

    /** The biggest guild whose leader would say yes (not hostile, room for the scene); else the biggest; null when none. */
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
        List<Point> mobPoints = new ArrayList<>();
        for (server.life.Monster m : map.getAllMonsters()) {
            Point mp = m.getPosition();
            if (m.isAlive() && mp != null && m.getHp() > 1) {
                mobPoints.add(mp);
            }
        }
        Point spot = fightSpot(p, mobPoints);
        if (mobPoints.isEmpty()) {
            return 0;
        }
        if (spot != null) {
            gm.changeMap(map, new Point(spot.x, spot.y)); // a GM teleport onto the mobs, same map
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

    /**
     * Where the GM's "fight" stands: null when it already stands among mobs of the lowest ledge, else the nearest
     * mob of that ledge. Auto-QA t-rv-2: the GM spawns anywhere, and on the map's top ledge the called bots (they enter
     * at a ground portal and walk up, at most 15 s) never got near enough to take a mob in the check's window.
     */
    static Point fightSpot(Point p, List<Point> mobs) {
        if (mobs.isEmpty()) {
            return null;
        }
        int lowest = Integer.MIN_VALUE;
        for (Point m : mobs) {
            lowest = Math.max(lowest, m.y);
        }
        Point best = null;
        for (Point m : mobs) {
            if (m.y < lowest - 150) {
                continue;
            }
            if (Math.abs(m.x - p.x) <= 100 && Math.abs(m.y - p.y) <= 60) {
                return null;
            }
            if (best == null || p.distanceSq(m) < p.distanceSq(best)) {
                best = m;
            }
        }
        return best;
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

    // ---- ticket 69: v83 basics (vb-1..vb-7) ----

    private void pickupMapItems(Character gm) {
        if (gm.getMap() == null) {
            return;
        }
        for (server.maps.MapObject obj : gm.getMap().getMapObjectsInRange(gm.getPosition(), Double.POSITIVE_INFINITY, List.of(server.maps.MapObjectType.ITEM))) {
            if (obj instanceof server.maps.MapItem mi) {
                mi.setDropTime(System.currentTimeMillis() - 1000);
                gm.pickupItem(mi);
            }
        }
    }

    /**
     * How much of an item to remove to empty its slot: exactly what is there. An equip's quantity must stay 0..1, so
     * asking for "everything" with a big number (the rework used 32767) throws on the first equip and leaves the
     * reset half done, which silently broke vb-1, vb-3, vb-6 and vb-7 (no coupons, no party, no job, no items).
     */
    static short wipeQuantity(Item item) {
        return (short) Math.max(1, item.getQuantity());
    }

    private void resetPretendPlayer(Character gm, int job, int level, int meso) {
        // Wipes job, skills, items, mesos and quest rows: only ever for auto-QA's pretend players, never a real
        // character, even if a future caller forgets V83Scenarios' own SyntheticClient check.
        if (gm == null || !(gm.getClient() instanceof client.SyntheticClient)) {
            return;
        }
        gm.changeJob(client.Job.getById(job));
        gm.setLevel(level);
        for (int i = 0; i < gm.getRemainingSps().length; i++) {
            int cur = gm.getRemainingSps()[i];
            if (cur > 0) {
                gm.gainSp(-cur, i, false);
            }
        }
        gm.gainMeso(meso - gm.getMeso(), false);
        for (client.Skill s : new ArrayList<>(gm.getSkills().keySet())) {
            gm.changeSkillLevel(s, (byte) -1, 0, -1);
        }
        for (int qid : new int[]{6002, 6003, 6007, 100003, 100005}) {
            server.quest.Quest q = server.quest.Quest.getInstance(qid);
            if (q != null) {
                gm.getQuestRemove(q);
            }
        }
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection()) {
            try (java.sql.PreparedStatement ps = con.prepareStatement("DELETE FROM queststatus WHERE characterid = ?")) {
                ps.setInt(1, gm.getId());
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = con.prepareStatement("DELETE FROM questprogress WHERE characterid = ?")) {
                ps.setInt(1, gm.getId());
                ps.executeUpdate();
            }
        } catch (java.sql.SQLException | RuntimeException ignored) {
        }
        for (InventoryType t : BAGS) {
            Inventory inv = gm.getInventory(t);
            for (Item it : new ArrayList<>(inv.list())) {
                InventoryManipulator.removeFromSlot(gm.getClient(), t, it.getPosition(), wipeQuantity(it), false);
            }
        }
    }

    @Override
    public List<String> v83Step(Character gm, String feature, String step) {
        try {
            return switch (feature) {
                case "vb-1" -> salonStep(gm, step);
                case "vb-2" -> deleteStep(gm, step);
                case "vb-3" -> kpqStep(gm, step);
                case "vb-4" -> mountStep(gm, step);
                case "vb-5" -> shopStep(gm, step);
                case "vb-6" -> jobStep(gm, step);
                case "vb-7" -> basicsStep(gm, step);
                default -> List.of("no v83 feature " + feature);
            };
        } catch (RuntimeException e) {
            return List.of("STEP_FAILED " + feature + " " + step + ": " + e);
        }
    }

    // ── vb-1: the Henesys salons ──

    private List<String> salonStep(Character gm, String step) {
        if ("stage".equals(step)) {
            resetPretendPlayer(gm, 0, 10, 1_000_000);
            List<String> out = new ArrayList<>();
            for (int coupon : new int[]{5150001, 5151001, 5152001, 5152013, 5153000}) {
                InventoryManipulator.addById(gm.getClient(), coupon, (short) 1, "qa", -1);
            }
            out.add("VB SALON coupons 5150001 5151001 5152001 5152013 5153000 given");
            return out;
        }
        int npc, menu, style, coupon;
        String kind;
        switch (step) {
            case "hair" -> { npc = 1012103; menu = 1; style = 3; coupon = 5150001; kind = "hair"; }
            case "haircolor" -> { npc = 1012103; menu = 2; style = 3; coupon = 5151001; kind = "haircolor"; }
            case "face" -> { npc = 1052004; menu = 2; style = 2; coupon = 5152001; kind = "face"; }
            case "lens" -> { npc = 9200100; menu = 2; style = 1; coupon = 5152013; kind = "face"; }
            case "skin" -> { npc = 1012105; menu = 1; style = 3; coupon = 5153000; kind = "skin"; }
            default -> { return List.of("no salon step " + step); }
        }
        int map = switch (npc) {
            case 1012103 -> 100000104;
            case 1012105 -> 100000105;
            default -> 100000103;
        };
        warp(gm, map);
        int before = "hair".equals(kind) || "haircolor".equals(kind) ? gm.getHair()
                : "skin".equals(kind) ? gm.getSkinColor().getId() : gm.getFace();
        int couponBefore = countItem(gm, coupon);
        long since = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        boolean opened = npcScript(gm, npc, out, menu, style);
        if (!opened) {
            out.add("VB STYLE " + step + " UNCHANGED (the NPC script did not start)");
            return out;
        }
        int after = "hair".equals(kind) || "haircolor".equals(kind) ? gm.getHair()
                : "skin".equals(kind) ? gm.getSkinColor().getId() : gm.getFace();
        int couponAfter = countItem(gm, coupon);
        out.add("VB STYLE " + step + " " + before + " -> " + after + (after != before ? " changed" : " UNCHANGED"));
        out.add("VB COUPON " + coupon + " " + couponBefore + " -> " + couponAfter);
        refuseCheck(out, npcDialogs(gm, since));
        return out;
    }

    /** Starts an NPC script on the pretend player and answers it once, the way a player clicking through does. */
    private boolean npcScript(Character gm, int npc, List<String> out, int... selections) {
        gm.getClient().removeClickedNPC(); // a real player also waits 500ms between clicks; the surety is not what we test
        boolean started = scripting.npc.NPCScriptManager.getInstance().start(gm.getClient(), npc, gm);
        if (!started) {
            return false;
        }
        for (int selection : selections) {
            scripting.npc.NPCScriptManager.getInstance().action(gm.getClient(), (byte) 1, (byte) 0, selection);
            gm.getClient().removeClickedNPC();
        }
        return true;
    }

    private static final java.util.regex.Pattern REFUSAL = java.util.regex.Pattern.compile(
            "(?i)(no options|cannot|can not|can't|is closed|closed for now|not available|error|"
                    + "i'm sorry|i am sorry|don't have|do not have|not enough|you need(?! to choose)|must have|unavailable)");

    /** Turns a dialog that refuses into an NPC_REFUSAL line; a step that expects a refusal does not call this. */
    private void refuseCheck(List<String> out, List<String> dialogs) {
        for (String d : dialogs) {
            if (REFUSAL.matcher(d).find()) {
                out.add("NPC_REFUSAL " + d);
            }
        }
    }

    /** The NPC dialog lines this pretend player received since a time, as "{@code <npc>: <text>}". */
    private List<String> npcDialogs(Character gm, long since) {
        if (!(gm.getClient() instanceof client.SyntheticClient sc)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (soloMapling.autoqa.AutoQaLine line : sc.heardSince(since)) {
            if ("NPC_TALK".equals(line.kind())) {
                out.add(line.message());
            }
        }
        return out;
    }

    // ── vb-2: delete a character with PIC ──

    private List<String> deleteStep(Character gm, String step) {
        if ("stage".equals(step)) {
            List<String> out = new ArrayList<>();
            gm.getClient().setPic("1234");
            try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
                 java.sql.PreparedStatement ps = con.prepareStatement(
                         "SELECT id FROM characters WHERE accountid = ? AND name LIKE 'VbDel%'")) {
                ps.setInt(1, gm.getClient().getAccID());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        gm.getClient().deleteCharacter(rs.getInt(1), gm.getClient().getAccID());
                    }
                }
            } catch (java.sql.SQLException | RuntimeException ignored) {}
            out.add("VB DELETE pic set on account " + gm.getClient().getAccID());
            return out;
        }
        String name = "VbDel" + (System.currentTimeMillis() % 1_000_000);
        if (name.length() > 12) {
            name = name.substring(0, 12);
        }
        int made = client.creator.novice.BeginnerCreator.createCharacter(gm.getClient(), name, 20000, 30000, 0,
                0, 0, 0, 0, 0);
        List<String> out = new ArrayList<>();
        if (made != 0) {
            out.add("VB DELETE could not create a character to delete (status " + made + ")");
            return out;
        }
        int cid = Character.getIdByName(name);
        out.add("VB DELETE made " + name + " (" + cid + ")");
        String pic = "wrongpic".equals(step) ? "9999" : "1234";
        long since = System.currentTimeMillis();
        boolean handled = loginPacket(gm, net.opcodes.RecvOpcode.DELETE_CHAR, body -> {
            body.writeString(pic);
            body.writeInt(cid);
        });
        String answer = lastHeard(gm, since, "DELETE_CHAR_RESPONSE");
        boolean gone = Character.getIdByName(name) < 0;
        if (!handled) {
            out.add("VB DELETE the delete packet found no login handler");
        } else if ("wrongpic".equals(step)) {
            out.add("VB DELETE wrongpic answer=" + answer + " stillThere=" + !gone);
            gm.getClient().deleteCharacter(cid, gm.getClient().getAccID());
        } else {
            out.add("VB DELETE state=" + answer + " gone=" + gone);
            refuseCheck(out, List.of()); // no NPC involved; the response code is the assertion
        }
        return out;
    }

    private boolean loginPacket(Character gm, net.opcodes.RecvOpcode op,
                                java.util.function.Consumer<net.packet.ByteBufOutPacket> body) {
        net.packet.ByteBufOutPacket b = new net.packet.ByteBufOutPacket();
        body.accept(b);
        net.PacketHandler h = net.PacketProcessor.getLoginServerProcessor().getHandler((short) op.getValue());
        if (h == null) {
            return false;
        }
        h.handlePacket(new net.packet.ByteBufInPacket(io.netty.buffer.Unpooled.wrappedBuffer(b.getBytes())),
                gm.getClient());
        return true;
    }

    private String lastHeard(Character gm, long since, String kind) {
        if (!(gm.getClient() instanceof client.SyntheticClient sc)) {
            return "(no synthetic client)";
        }
        String answer = "(nothing)";
        for (soloMapling.autoqa.AutoQaLine line : sc.heardSince(since)) {
            if (kind.equals(line.kind())) {
                answer = line.message();
            }
        }
        return answer;
    }

    // ── vb-3: Kerning PQ clear, reward, exit ──

    private List<String> kpqStep(Character gm, String step) {
        if ("stage".equals(step)) {
            resetPretendPlayer(gm, 100, 25, 1_000_000);
            List<String> out = new ArrayList<>();
            if (gm.getParty() == null) {
                net.server.world.Party.createParty(gm, false);
            }
            scripting.event.EventManager em = gm.getClient().getEventManager("KerningPQ");
            out.add("VB KPQ event manager " + (em == null ? "MISSING" : "found"));
            if (em == null) {
                return out;
            }
            boolean started = em.startInstance(-1, gm.getParty(), gm.getMap(), gm);
            scripting.event.EventInstanceManager eim = gm.getEventInstance();
            out.add("VB KPQ instance " + (started && eim != null ? "started " + eim.getName() : "DID NOT START"));
            if (eim != null) {
                MapleMap stg5 = eim.getMapInstance(103000804);
                if (stg5 != null) {
                    gm.changeMap(stg5, stg5.getPortal(0));
                }
                InventoryManipulator.addById(gm.getClient(), 4001008, (short) 10, "qa", -1);
            }
            return out;
        }
        scripting.event.EventInstanceManager eim = gm.getEventInstance();
        if (eim == null) {
            return List.of("VB KPQ no instance on the player");
        }
        List<String> out = new ArrayList<>();
        if ("clear".equals(step)) {
            npcScript(gm, 9020001, out, 0);
            out.add("VB KPQ cleared=" + eim.isEventCleared());
        } else if ("reward".equals(step)) {
            int before = totalItems(gm);
            npcScript(gm, 9020001, out, 0);
            int after = totalItems(gm);
            boolean given = after > before;
            boolean warped = gm.getMapId() == 103000805;
            out.add("VB KPQ reward given=" + given + " items " + before + " -> " + after
                    + " warped=" + warped + " map=" + gm.getMapId());
        }
        return out;
    }

    private int inventoryCount(Character gm) {
        return totalItems(gm);
    }

    private int totalItems(Character gm) {
        int n = 0;
        for (InventoryType t : BAGS) {
            client.inventory.Inventory inv = gm.getInventory(t);
            for (client.inventory.Item it : inv.list()) {
                n += it.getQuantity();
            }
        }
        return n;
    }

    // ── vb-4: the mount chain ──

    private List<String> mountStep(Character gm, String step) {
        List<String> out = new ArrayList<>();
        switch (step) {
            case "stage" -> {
                resetPretendPlayer(gm, 111, 70, 25_000_000);
                warp(gm, 923010000);
                out.add("VB MOUNT staged as job " + gm.getJob().getId() + " on " + gm.getMapId()
                        + " meso=" + gm.getMeso());
            }
            case "room" -> {
                server.quest.Quest q = server.quest.Quest.getInstance(6002);
                if (gm.getQuestStatus(6002) != 1) {
                    q.start(gm, 2060005);
                }
                scripting.event.EventManager em = gm.getClient().getEventManager("3rdJob_mount");
                out.add("VB MOUNT room manager=" + (em == null ? "MISSING" : "found"));
                if (em == null) {
                    return out;
                }
                boolean started = em.startInstance(gm);
                out.add("VB MOUNT room started=" + started + " map=" + gm.getMapId()
                        + " instance=" + (gm.getEventInstance() != null));
                MapleMap roomMap = gm.getMap();
                server.life.Monster hog = roomMap.getMonsterById(constants.id.MobId.WATCH_HOG);
                List<server.maps.Reactor> chests = roomMap.getAllReactors();
                for (int i = 0; i < 5; i++) {
                    for (server.maps.Reactor r : chests) {
                        if (countItem(gm, 4031508) < 5) {
                            scripting.reactor.ReactorScriptManager.getInstance().act(gm.getClient(), r);
                            pickupMapItems(gm);
                        }
                    }
                    if (hog != null && countItem(gm, 4031507) < 5) {
                        roomMap.dropFromFriendlyMonster(gm, hog);
                        pickupMapItems(gm);
                    }
                }
                if (gm.getEventInstance() != null) {
                    npcScript(gm, 9060000, out, 0, 0);
                    if (gm.getEventInstance() != null) {
                        gm.getEventInstance().clearPQ();
                    }
                    out.add("VB MOUNT room exit map=" + gm.getMapId());
                }
                boolean can = q.canComplete(gm, 2060005);
                q.complete(gm, 2060005);
                int rider = countItem(gm, 1912000);
                out.add("VB MOUNT quest6002 canComplete=" + can + " state=" + gm.getQuestStatus(6002)
                        + " item1912000=" + rider + " skill1004=" + gm.getSkillLevel(1004));
                gm.changeJob(client.Job.getById(100)); // the rest of the chain does not need the 3rd job
            }
            case "bottle-broke" -> {
                server.quest.Quest q = server.quest.Quest.getInstance(6007);
                if (gm.getQuestStatus(6007) != 1) {
                    q.start(gm, 2081000);
                }
                int meso = gm.getMeso();
                if (meso >= 20_000_000) {
                    gm.gainMeso(-(meso - 1_000_000), false);
                }
                int mesoBefore = gm.getMeso();
                int held = countItem(gm, 4031509);
                boolean can = q.canComplete(gm, 2081000);
                q.complete(gm, 2081000);
                int mesoAfter = gm.getMeso();
                out.add("VB MOUNT bottleRefused canComplete=" + can + " meso " + mesoBefore + " -> " + mesoAfter
                        + " stillBottle=" + (countItem(gm, 4031509) == held));
                gm.gainMeso(20_000_000 - gm.getMeso() + 1_000_000, false);
            }
            case "bottle" -> {
                server.quest.Quest q = server.quest.Quest.getInstance(6007);
                if (gm.getQuestStatus(6007) != 1) {
                    q.start(gm, 2081000);
                }
                int before = gm.getMeso();
                int bottles = countItem(gm, 4031509);
                boolean can = q.canComplete(gm, 2081000);
                q.complete(gm, 2081000);
                int after = gm.getMeso();
                out.add("VB MOUNT bottle canComplete=" + can + " meso " + before + " -> " + after
                        + " fee=" + (before - after) + " bottle " + bottles + " -> "
                        + countItem(gm, 4031509));
            }
            case "perfume" -> {
                server.quest.Quest q = server.quest.Quest.getInstance(6003);
                if (gm.getQuestStatus(6003) != 1) {
                    q.start(gm, 2060005);
                }
                int before = countItem(gm, 2270000);
                boolean can = q.canComplete(gm, 2060005);
                q.complete(gm, 2060005);
                out.add("VB MOUNT perfume canComplete=" + can + " perfume " + before + " -> "
                        + countItem(gm, 2270000)
                        + " bottleLeft=" + countItem(gm, 4031509));
            }
            case "hog" -> hog(gm, out);
            default -> out.add("VB MOUNT no step " + step);
        }
        return out;
    }

    /**
     * The last link of the mount chain: the client's phenomone perfume (2270000) on a tamable hog below 40% HP.
     * The real path is the USE_CATCH_ITEM packet — {@code net.server.channel.handlers.UseCatchItemHandler} reads the
     * item and the mob oid, checks the mob's HP and swaps the perfume for a hog saddle (1902000). Nothing on the
     * server checks level or a worn saddle at catch time, so the preconditions are the item in USE and the HP.
     */
    private void hog(Character gm, List<String> out) {
        MapleMap map = gm.getClient().getChannelServer().getMapFactory().getMap(922200000);
        if (map == null) {
            out.add("VB MOUNT hog map=922200000 missing");
            return;
        }
        gm.changeMap(map, map.getRandomPlayerSpawnpoint());
        if (gm.getMapId() != 922200000) {
            out.add("VB MOUNT hog could not warp to 922200000, still on " + gm.getMapId());
            return;
        }
        boolean spawned = false;
        server.life.Monster hog = map.getMonsterById(constants.id.MobId.TAMABLE_HOG);
        if (hog == null) {
            // the map spawns the hog for 1200s every 3600s; outside that window QA brings its own
            map.spawnMonsterOnGroundBelow(constants.id.MobId.TAMABLE_HOG, gm.getPosition().x, gm.getPosition().y);
            spawned = true;
            hog = map.getMonsterById(constants.id.MobId.TAMABLE_HOG);
        }
        if (hog == null) {
            out.add("VB MOUNT hog map=922200000 no mob " + constants.id.MobId.TAMABLE_HOG);
            return;
        }
        int oid = hog.getObjectId();
        long maxHp = hog.getMaxHp();
        long target = maxHp / 10 * 3;
        if (hog.getHp() > target) {
            hog.applyAndGetHpDamage(hog.getHp() - target, false);
        }
        long hp = hog.getHp();
        int perfume = constants.id.ItemId.PHEROMONE_PERFUME;
        if (countItem(gm, perfume) <= 0) {
            InventoryManipulator.addById(gm.getClient(), perfume, (short) 1, "qa", -1);
        }
        int perfumeBefore = countItem(gm, perfume);
        int mountBefore = countItem(gm, constants.id.ItemId.HOG);
        boolean handled = body(gm, net.opcodes.RecvOpcode.USE_CATCH_ITEM, b -> {
            b.writeInt(0);
            b.writeShort(0);
            b.writeInt(perfume);
            b.writeInt(oid);
        });
        int perfumeAfter = countItem(gm, perfume);
        int mountAfter = countItem(gm, constants.id.ItemId.HOG);
        out.add("VB MOUNT hog map=" + gm.getMapId() + " spawnedByQa=" + spawned + " hp=" + hp + "/" + maxHp
                + " handler=" + handled + " perfume " + perfumeBefore + " -> " + perfumeAfter
                + " perfumeUsed=" + (perfumeBefore - perfumeAfter)
                + " mountGained=" + (mountAfter - mountBefore)
                + " mobGone=" + (map.getMonsterByOid(oid) == null));
    }

    // ── vb-5: NPC shop buy/sell and the Cash Shop EXP card ──

    private List<String> shopStep(Character gm, String step) {
        List<String> out = new ArrayList<>();
        if ("stage".equals(step)) {
            resetPretendPlayer(gm, 100, 30, 1_000_000);
            warp(gm, 100000100);
            server.life.NPC npc = gm.getMap().getNPCById(1012004);
            if (npc == null) {
                out.add("VB SHOP no shop NPC 1012004 on the market map");
                return out;
            }
            npc.sendShop(gm.getClient());
            out.add("VB SHOP opened NPC 1012004 on map " + gm.getMapId());
            return out;
        }
        server.Shop shop = server.ShopFactory.getInstance().getShopForNPC(1012004);
        if (shop == null) {
            return List.of("VB SHOP no shop 1012004");
        }
        if ("buy".equals(step)) {
            server.ShopItem chosen = null;
            short index = 0;
            for (short i = 0; i < shop.getItems().size(); i++) {
                server.ShopItem si = shop.getItems().get(i);
                if (si.getPrice() > 0 && si.getPrice() <= gm.getMeso()) {
                    chosen = si;
                    index = i;
                    break;
                }
            }
            if (chosen == null) {
                return List.of("VB SHOP nothing affordable in shop 1012004");
            }
            int mesoBefore = gm.getMeso();
            int before = countItem(gm, chosen.getItemId());
            long since = System.currentTimeMillis();
            shop.buy(gm.getClient(), index, chosen.getItemId(), (short) 1);
            int after = countItem(gm, chosen.getItemId());
            int spent = mesoBefore - gm.getMeso();
            out.add("VB SHOP buy item=" + chosen.getItemId() + " price=" + chosen.getPrice()
                    + " meso " + mesoBefore + " -> " + gm.getMeso() + " spent=" + spent + " owned " + before + " -> " + after);
            out.add("VB SHOP answer=" + lastHeard(gm, since, "SHOP_TRANSACTION"));
        } else if ("sell".equals(step)) {
            server.ShopItem chosen = shop.getItems().get(0);
            int itemId = chosen.getItemId();
            client.inventory.Inventory inv = gm.getInventory(InventoryType.USE);
            client.inventory.Item item = inv.findById(itemId);
            if (item == null) {
                for (InventoryType t : BAGS) {
                    item = gm.getInventory(t).findById(itemId);
                    if (item != null) {
                        inv = gm.getInventory(t);
                        break;
                    }
                }
            }
            if (item == null) {
                return List.of("VB SHOP sell item=" + itemId + " not in the bag (buy first)");
            }
            int mesoBefore = gm.getMeso();
            int before = inv.countById(itemId);
            long since = System.currentTimeMillis();
            shop.sell(gm.getClient(), item.getInventoryType(), item.getPosition(), (short) 1);
            int gained = gm.getMeso() - mesoBefore;
            out.add("VB SHOP sell item=" + itemId + " meso " + mesoBefore + " -> " + gm.getMeso()
                    + " gained=" + gained + " owned " + before + " -> " + inv.countById(itemId)
                    + " answer=" + lastHeard(gm, since, "SHOP_TRANSACTION"));
        } else if ("expcard".equals(step)) {
            expCard(gm, out);
        }
        return out;
    }

    private void expCard(Character gm, List<String> out) {
        final int coupon = 5211048; // 2x EXP Card 7 days
        int before = gm.getCouponExpRate();
        boolean cardGiven = InventoryManipulator.addById(gm.getClient(), coupon, (short) 1, "qa", -1);
        gm.updateCouponRates();
        int after = gm.getCouponExpRate();
        out.add("VB CASH card=" + coupon + " given=" + cardGiven + " rate " + before + " -> " + after);
        if (cardGiven) {
            for (InventoryType t : BAGS) {
                client.inventory.Item it = gm.getInventory(t).findById(coupon);
                if (it != null) {
                    InventoryManipulator.removeFromSlot(gm.getClient(), t, it.getPosition(), (short) 1, false);
                }
            }
            gm.updateCouponRates();
        }
    }

    // ── vb-6: job advancement and a skill point ──

    private List<String> jobStep(Character gm, String step) {
        List<String> out = new ArrayList<>();
        if ("stage".equals(step)) {
            resetPretendPlayer(gm, 100, 30, 1_000_000);
            warp(gm, 102000000);
            InventoryManipulator.addById(gm.getClient(), 4031012, (short) 1, "qa", -1);
            out.add("VB JOB job=" + gm.getJob().getId() + " sp=" + gm.getRemainingSps()[0]);
            return out;
        }
        if ("advance".equals(step)) {
            int before = gm.getJob().getId();
            long since = System.currentTimeMillis();
            boolean opened = npcScript(gm, 1022000, out, 3, 0, 1);
            out.add("VB JOB advance " + before + " -> " + gm.getJob().getId()
                    + (opened ? "" : " (script did not start)"));
            refuseCheck(out, npcDialogs(gm, since));
        } else if ("spend".equals(step)) {
            int job = gm.getJob().getId();
            int skillId = job >= 110 && job < 200 ? 1100000 : 1000000;
            gm.gainSp(1, constants.game.GameConstants.getSkillBook(skillId / 10000), false);
            int spBefore = gm.getRemainingSps()[constants.game.GameConstants.getSkillBook(skillId / 10000)];
            int lvlBefore = gm.getSkillLevel(skillId);
            body(gm, net.opcodes.RecvOpcode.DISTRIBUTE_SP, b -> {
                b.writeInt(0);
                b.writeInt(skillId);
            });
            out.add("VB JOB sp spend skill=" + skillId + " level " + lvlBefore + " -> " + gm.getSkillLevel(skillId)
                    + " sp " + spBefore + " -> " + gm.getRemainingSps()[constants.game.GameConstants.getSkillBook(skillId / 10000)]);
        }
        return out;
    }

    private boolean body(Character gm, net.opcodes.RecvOpcode op,
                         java.util.function.Consumer<net.packet.ByteBufOutPacket> writer) {
        net.packet.ByteBufOutPacket b = new net.packet.ByteBufOutPacket();
        writer.accept(b);
        net.PacketHandler h = net.PacketProcessor.getChannelServerProcessor(gm.getWorld(), gm.getClient().getChannel())
                .getHandler((short) op.getValue());
        if (h == null || !h.validateState(gm.getClient())) {
            return false;
        }
        h.handlePacket(new net.packet.ByteBufInPacket(io.netty.buffer.Unpooled.wrappedBuffer(b.getBytes())),
                gm.getClient());
        return true;
    }

    // ── vb-7: storage, Duey and a trade between two pretend players ──

    private List<String> basicsStep(Character gm, String step) {
        List<String> out = new ArrayList<>();
        if ("stage".equals(step)) {
            resetPretendPlayer(gm, 100, 30, 1_000_000);
            warp(gm, 100000000);
            InventoryManipulator.addById(gm.getClient(), 4000000, (short) 5, "qa", -1);
            InventoryManipulator.addById(gm.getClient(), 4000001, (short) 3, "qa", -1);
            soloMapling.autoqa.SyntheticPlayer p2 = soloMapling.autoqa.SyntheticPlayers.login(2);
            resetPretendPlayer(p2.character(), 100, 30, 1_000_000);
            warp(p2.character(), 100000000);
            try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
                 java.sql.PreparedStatement ps = con.prepareStatement(
                         "DELETE FROM dueypackages WHERE ReceiverId IN (?, ?) OR SenderId IN (?, ?)")) {
                ps.setInt(1, gm.getId());
                ps.setInt(2, p2.id());
                ps.setInt(3, gm.getId());
                ps.setInt(4, p2.id());
                ps.executeUpdate();
            } catch (java.sql.SQLException | RuntimeException ignored) {}
            for (client.inventory.Item it : new ArrayList<>(gm.getStorage().getItems())) {
                gm.getStorage().takeOut(it);
            }
            out.add("VB BASICS staged on " + gm.getMapId() + " with " + p2.name());
            return out;
        }
        if ("storage".equals(step)) {
            asPlainPlayer(gm, () -> storage(gm, out));
        } else if ("duey".equals(step)) {
            asPlainPlayer(gm, () -> duey(gm, out));
        } else if ("trade".equals(step)) {
            trade(gm, out);
        }
        return out;
    }

    /**
     * Runs a vb-7 step with the pretend player at GM level 0, as the player the feature is for. AutoQaOne is a GM (level
     * 3), and since 1.5.2 MINIMUM_GM_LEVEL_TO_USE_STORAGE / _TO_USE_DUEY / _TO_TRADE are Cosmic's 4: storage refuses
     * GM levels 2-3 ("You cannot use the storage as a GM of this level") and so does Duey, so vb-7 stored and sent
     * nothing. The trade step already did this for the ticket 43 GM-giving rule.
     */
    static void asPlainPlayer(Character chr, Runnable step) {
        int gmLevel = chr.gmLevel();
        chr.setGMLevel(0);
        try {
            step.run();
        } finally {
            chr.setGMLevel(gmLevel);
        }
    }

    private void storage(Character gm, List<String> out) {
        int itemId = 4000000;
        client.inventory.Item item = gm.getInventory(InventoryType.ETC).findById(itemId);
        if (item == null) {
            out.add("VB STORAGE no item " + itemId + " to store");
            return;
        }
        gm.getStorage().sendStorage(gm.getClient(), 1012009);
        int invBefore = gm.getInventory(InventoryType.ETC).countById(itemId);
        int storeBefore = gm.getStorage().getItems().size();
        net.packet.ByteBufOutPacket b = new net.packet.ByteBufOutPacket();
        b.writeByte(5);
        b.writeShort(item.getPosition());
        b.writeInt(itemId);
        b.writeShort((short) 1);
        client.processor.npc.StorageProcessor.storageAction(
                new net.packet.ByteBufInPacket(io.netty.buffer.Unpooled.wrappedBuffer(b.getBytes())), gm.getClient());
        int storeMid = gm.getStorage().getItems().size();
        out.add("VB STORAGE stored " + itemId + " bag " + invBefore + " -> "
                + gm.getInventory(InventoryType.ETC).countById(itemId) + " store " + storeBefore + " -> " + storeMid);
        List<client.inventory.Item> etcItems = new ArrayList<>();
        for (client.inventory.Item it : gm.getStorage().getItems()) {
            if (it.getInventoryType() == InventoryType.ETC) {
                etcItems.add(it);
            }
        }
        byte storedSlot = 0;
        for (byte i = 0; i < etcItems.size(); i++) {
            if (etcItems.get(i).getItemId() == itemId) {
                storedSlot = i;
                break;
            }
        }
        net.packet.ByteBufOutPacket t = new net.packet.ByteBufOutPacket();
        t.writeByte(4);
        t.writeByte((byte) InventoryType.ETC.getType());
        t.writeByte(storedSlot);
        client.processor.npc.StorageProcessor.storageAction(
                new net.packet.ByteBufInPacket(io.netty.buffer.Unpooled.wrappedBuffer(t.getBytes())), gm.getClient());
        out.add("VB STORAGE tookOut bagDelta="
                + (gm.getInventory(InventoryType.ETC).countById(itemId) - invBefore)
                + " storeSize=" + gm.getStorage().getItems().size());
    }

    private void duey(Character gm, List<String> out) {
        int itemId = 4000001;
        client.inventory.Item item = gm.getInventory(InventoryType.ETC).findById(itemId);
        soloMapling.autoqa.SyntheticPlayer p2 = soloMapling.autoqa.SyntheticPlayers.login(2);
        if (item == null) {
            out.add("VB DUEY no item " + itemId + " to send");
            return;
        }
        int before = p2.character().getInventory(InventoryType.ETC).countById(itemId);
        boolean handled = body(gm, net.opcodes.RecvOpcode.DUEY_ACTION, b -> {
            b.writeByte(client.processor.npc.DueyProcessor.Actions.TOSERVER_SEND_ITEM.getCode());
            b.writeByte((byte) 4); // ETC
            b.writeShort(item.getPosition());
            b.writeShort((short) 1);
            b.writeInt(0);
            b.writeString(p2.name());
            b.writeByte(0);
        });
        if (!handled) {
            out.add("VB DUEY send handler missing or rejected");
            return;
        }
        int packageId = -1;
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement(
                     "SELECT PackageId FROM dueypackages WHERE ReceiverId = ? ORDER BY PackageId DESC LIMIT 1")) {
            ps.setInt(1, p2.id());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    packageId = rs.getInt(1);
                }
            }
        } catch (java.sql.SQLException e) {
            out.add("VB DUEY package lookup failed: " + e.getMessage());
            return;
        }
        out.add("VB DUEY sent " + itemId + " to " + p2.name() + " package=" + packageId);
        if (packageId > 0) {
            final int pkg = packageId;
            body(p2.character(), net.opcodes.RecvOpcode.DUEY_ACTION, b -> {
                b.writeByte(client.processor.npc.DueyProcessor.Actions.TOSERVER_CLAIM_PACKAGE.getCode());
                b.writeInt(pkg);
            });
        }
        int after = p2.character().getInventory(InventoryType.ETC).countById(itemId);
        out.add("VB DUEY claimed recipientDelta=" + (after - before));
    }

    private void trade(Character gm, List<String> out) {
        soloMapling.autoqa.SyntheticPlayer p2 = soloMapling.autoqa.SyntheticPlayers.login(2);
        warp(p2.character(), gm.getMapId());
        int itemId = 4000000;
        client.inventory.Item item = gm.getInventory(InventoryType.ETC).findById(itemId);
        if (item == null) {
            out.add("VB TRADE no item " + itemId + " to offer");
            return;
        }
        // ticket 43 blocks GM<->player windows (and GM levels under 4 cannot trade); the feature under test is the window
        asPlainPlayer(gm, () -> {
            server.Trade.startTrade(gm);
            server.Trade.inviteTrade(gm, p2.character());
            server.Trade.visitTrade(p2.character(), gm);
            client.inventory.Item offer = item.copy();
            offer.setQuantity((short) 1);
            offer.setPosition((short) 0);
            boolean added = gm.getTrade() != null && gm.getTrade().addItem(offer);
            if (added) {
                InventoryManipulator.removeFromSlot(gm.getClient(), InventoryType.ETC, item.getPosition(), (short) 1, true);
            }
            int p2Before = p2.character().getInventory(InventoryType.ETC).countById(itemId);
            int p2Meso = p2.character().getMeso();
            gm.getTrade().setMeso(5000);
            server.Trade.completeTrade(gm);
            server.Trade.completeTrade(p2.character());
            int p2After = p2.character().getInventory(InventoryType.ETC).countById(itemId);
            out.add("VB TRADE offered=" + added + " itemDelta=" + (p2After - p2Before)
                    + " mesoDelta=" + (p2.character().getMeso() - p2Meso));
        });
    }
}
