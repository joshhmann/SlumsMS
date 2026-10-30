package client.inventory;

import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.MakerItemFactory;
import tools.PacketCreator;
import tools.Pair;
import tools.Randomizer;

import net.packet.Packet;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class MakerService {
    private static final Logger log = LoggerFactory.getLogger(MakerService.class);
    /** Looked up on use, not at class load: the skill-id and gate helpers here must work without item data (tests). */
    private static ItemInformationProvider ii() {
        return ItemInformationProvider.getInstance();
    }

    // checks and prevents hackers from PE'ing Maker operations with invalid operations
    public static boolean removeOddMakerReagents(int toCreate, Map<Integer, Short> reagentids) {
        Map<Integer, Integer> reagentType = new LinkedHashMap<>();
        List<Integer> toRemove = new LinkedList<>();

        boolean isWeapon = ItemConstants.isWeapon(toCreate) || YamlConfig.config.server.USE_MAKER_PERMISSIVE_ATKUP;  // thanks Vcoc for finding a case where a weapon wouldn't be counted as such due to a bounding on isWeapon

        for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
            int curRid = r.getKey();
            int type = r.getKey() / 100;

            if (type < 42502 && !isWeapon) {     // only weapons should gain w.att/m.att from these.
                return false;   //toRemove.add(curRid);
            } else {
                Integer tableRid = reagentType.get(type);

                if (tableRid == null) {
                    reagentType.put(type, curRid);
                    continue;
                }

                if (tableRid < curRid) {
                    toRemove.add(tableRid);
                    reagentType.put(type, curRid);
                    continue;
                }

                toRemove.add(curRid);
            }
        }

        // removing less effective gems of repeated type
        for (Integer i : toRemove) {
            reagentids.remove(i);
        }

        // the Maker skill will use only one of each gem
        for (Integer i : reagentids.keySet()) {
            reagentids.put(i, (short) 1);
        }

        return true;
    }

    public static int getMakerReagentSlots(int itemId) {
        try {
            int eqpLevel = ii().getEquipLevelReq(itemId);

            if (eqpLevel < 78) {
                return 1;
            } else if (eqpLevel >= 78 && eqpLevel < 108) {
                return 2;
            } else {
                return 3;
            }
        } catch (NullPointerException npe) {
            return 0;
        }
    }

    public static Pair<Integer, List<Pair<Integer, Integer>>> generateDisassemblyInfo(int itemId) {
        int recvFee = ii().getMakerDisassembledFee(itemId);
        if (recvFee > -1) {
            List<Pair<Integer, Integer>> gains = ii().getMakerDisassembledItems(itemId);
            if (!gains.isEmpty()) {
                return new Pair<>(recvFee, gains);
            }
        }

        return null;
    }

    /** The stimulant roll (stock Cosmic): a craft with a stimulant succeeds 90% of the time; a failure keeps the
     * materials spent. Ticket 112 (owner 2026-10-30): stock rates. */
    public static final double STIMULANT_SUCCESS_PERCENT = 90.0;
    /** Leftover items per monster crystal (stock). */
    public static final int LEFTOVERS_PER_CRYSTAL = 100;
    /** Quest 6033 (Maker 2): crafting this crystal after starting the test marks the quest's progress. */
    public static final int MAKER_2_TEST_CRYSTAL = 4260003;

    /**
     * The Maker skill of the job's family: 0001007 Explorers (and GMs), 10001007 Cygnus Knights, 20001007 Aran,
     * 20011007 Evan (2001, 2200-2218 and this fork's 9900-9918). Ticket 112: the old {@code job / 1000} formula
     * gave Evan Aran's skill (and 90001007 for 99xx).
     */
    public static int makerSkillId(int jobId) {
        return GameConstants.getBeginnerJobId(jobId) * 10000 + 1007;
    }

    public static int getMakerSkillLevel(Character chr) {
        return chr.getSkillLevel(makerSkillId(chr.getJob().getId()));
    }

    /**
     * Where a Maker operation takes its materials and puts what it makes (ticket 112). A player, or an owned alt
     * (its own headless client with the alt attached), works through its {@link Client}: {@link #of(Client)}. A
     * population bot shares one client with no player on it, so its inventory changes go through the bot ledger
     * instead (soloMapling's BotMakerHands). Everything else (the recipe, the checks, the rolls, the result) is the
     * same code for both.
     */
    public interface Hands {
        Character chr();

        /** How many of the item the character holds, across stacks. */
        int count(int itemId);

        boolean canHold(List<Integer> addIds, List<Integer> addQty, List<Integer> removeIds, List<Integer> removeQty);

        void take(int itemId, int quantity);

        /** A plain item (or a plain equip) by id. */
        void give(int itemId, int quantity);

        /** A crafted equip, stats already rolled. */
        void giveEquip(Item item);

        /** Removes the equip at this EQUIP slot (disassembly). */
        void removeEquipAt(short pos);

        void payMeso(int amount);

        void send(Packet packet);
    }

    public static Hands of(Client c) {
        return new ClientHands(c);
    }

    /** The player's path, exactly as the Maker window always did it. */
    private record ClientHands(Client c) implements Hands {
        @Override
        public Character chr() {
            return c.getPlayer();
        }

        @Override
        public int count(int itemId) {
            return c.getPlayer().getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId);
        }

        @Override
        public boolean canHold(List<Integer> addIds, List<Integer> addQty, List<Integer> removeIds, List<Integer> removeQty) {
            return c.getAbstractPlayerInteraction().canHoldAllAfterRemoving(addIds, addQty, removeIds, removeQty);
        }

        @Override
        public void take(int itemId, int quantity) {
            c.getAbstractPlayerInteraction().gainItem(itemId, (short) -quantity, false);
        }

        @Override
        public void give(int itemId, int quantity) {
            c.getPlayer().setCS(true);
            c.getAbstractPlayerInteraction().gainItem(itemId, (short) quantity, false);
            c.getPlayer().setCS(false);
        }

        @Override
        public void giveEquip(Item item) {
            InventoryManipulator.addFromDrop(c, item, false, -1);
        }

        @Override
        public void removeEquipAt(short pos) {
            InventoryManipulator.removeFromSlot(c, InventoryType.EQUIP, pos, (short) 1, false);
        }

        @Override
        public void payMeso(int amount) {
            c.getPlayer().gainMeso(-amount, false);
        }

        @Override
        public void send(Packet packet) {
            c.sendPacket(packet);
        }
    }

    /**
     * One monster crystal from 100 leftovers (the Maker window's type 3). The window and the bot batch both come
     * here. 0 when made, -1 when the item makes no crystal, else the create status (1 materials, 2 mesos, 3 level,
     * 4 Maker level, 5 inventory full).
     */
    public static short makeLeftoverCrystal(Hands h, int fromLeftover) {
        int crystal = ii().getMakerCrystalFromLeftover(fromLeftover);
        if (crystal == -1) {
            h.send(PacketCreator.serverNotice(1, ii().getName(fromLeftover) + " is unavailable for Monster Crystal conversion."));
            h.send(PacketCreator.makerEnableActions());
            return -1;
        }
        MakerItemFactory.MakerItemCreateEntry recipe = MakerItemFactory.generateLeftoverCrystalEntry(fromLeftover, crystal);
        short status = getCreateStatus(h, recipe);
        applyMakerResult(h, status, crystal, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 3);
        return status;
    }

    /**
     * Breaks the equip at this EQUIP slot into monster crystals (the Maker window's type 4); {@code expectedId} is
     * the item the window shows there (-1: whatever is there). Same results as {@link #makeLeftoverCrystal}.
     */
    public static short disassemble(Hands h, short pos, int expectedId) {
        Item it = h.chr().getInventory(InventoryType.EQUIP).getItem(pos);
        if (it == null || (expectedId != -1 && it.getItemId() != expectedId)) {
            h.send(PacketCreator.serverNotice(1, "An unknown error occurred when trying to apply that item for disassembly."));
            h.send(PacketCreator.makerEnableActions());
            return -1;
        }
        int toDisassemble = it.getItemId();
        Pair<Integer, List<Pair<Integer, Integer>>> pair = generateDisassemblyInfo(toDisassemble);
        if (pair == null) {
            h.send(PacketCreator.serverNotice(1, ii().getName(toDisassemble) + " is unavailable for Monster Crystal disassembly."));
            h.send(PacketCreator.makerEnableActions());
            return -1;
        }
        MakerItemFactory.MakerItemCreateEntry recipe = MakerItemFactory.generateDisassemblyCrystalEntry(toDisassemble, pair.getLeft(), pair.getRight());
        short status = getCreateStatus(h, recipe);
        applyMakerResult(h, status, toDisassemble, recipe, toDisassemble, pos, -1, new LinkedHashMap<>(), true, 4);
        return status;
    }

    /** True when this equip has a disassembly recipe (it is a Maker-made item). */
    public static boolean canDisassemble(int itemId) {
        return generateDisassemblyInfo(itemId) != null;
    }

    public static short getCreateStatus(Client c, MakerItemFactory.MakerItemCreateEntry recipe) {
        return getCreateStatus(of(c), recipe);
    }

    public static short getCreateStatus(Hands h, MakerItemFactory.MakerItemCreateEntry recipe) {
        if (recipe.isInvalid()) {
            return -1;
        }

        if (!hasItems(h, recipe)) {
            return 1;
        }

        Character chr = h.chr();
        if (chr.getMeso() < recipe.getCost()) {
            return 2;
        }

        if (chr.getLevel() < recipe.getReqLevel()) {
            return 3;
        }

        if (getMakerSkillLevel(chr) < recipe.getReqSkillLevel()) {
            return 4;
        }

        List<Integer> addItemids = new LinkedList<>();
        List<Integer> addQuantity = new LinkedList<>();
        List<Integer> rmvItemids = new LinkedList<>();
        List<Integer> rmvQuantity = new LinkedList<>();

        for (Pair<Integer, Integer> p : recipe.getReqItems()) {
            rmvItemids.add(p.getLeft());
            rmvQuantity.add(p.getRight());
        }

        for (Pair<Integer, Integer> p : recipe.getGainItems()) {
            addItemids.add(p.getLeft());
            addQuantity.add(p.getRight());
        }

        if (!h.canHold(addItemids, addQuantity, rmvItemids, rmvQuantity)) {
            return 5;
        }

        return 0;
    }

    public static boolean hasItems(Client c, MakerItemFactory.MakerItemCreateEntry recipe) {
        return hasItems(of(c), recipe);
    }

    public static boolean hasItems(Hands h, MakerItemFactory.MakerItemCreateEntry recipe) {
        for (Pair<Integer, Integer> p : recipe.getReqItems()) {
            if (h.count(p.getLeft()) < p.getRight()) {
                return false;
            }
        }
        return true;
    }

    public static void applyMakerResult(Client c, short createStatus, int toCreate, MakerItemFactory.MakerItemCreateEntry recipe, int toDisassemble, short pos, int stimulantid, Map<Integer, Short> reagentids, boolean makerSucceeded, int type) {
        applyMakerResult(of(c), createStatus, toCreate, recipe, toDisassemble, pos, stimulantid, reagentids, makerSucceeded, type);
    }

    public static void applyMakerResult(Hands h, short createStatus, int toCreate, MakerItemFactory.MakerItemCreateEntry recipe, int toDisassemble, short pos, int stimulantid, Map<Integer, Short> reagentids, boolean makerSucceeded, int type) {
        Character chr = h.chr();
        switch (createStatus) {
            case -1:// non-available for Maker itemid has been tried to forge
                log.warn("Chr {} tried to craft itemid {} using the Maker skill.", chr.getName(), toCreate);
                h.send(PacketCreator.serverNotice(1, "The requested item could not be crafted on this operation."));
                h.send(PacketCreator.makerEnableActions());
                break;

            case 1: // no items
                h.send(PacketCreator.serverNotice(1, "You don't have all required items in your inventory to make " + ii().getName(toCreate) + "."));
                h.send(PacketCreator.makerEnableActions());
                break;

            case 2: // no meso
                h.send(PacketCreator.serverNotice(1, "You don't have enough mesos (" + GameConstants.numberWithCommas(recipe.getCost()) + ") to complete this operation."));
                h.send(PacketCreator.makerEnableActions());
                break;

            case 3: // no req level
                h.send(PacketCreator.serverNotice(1, "You don't have enough level to complete this operation."));
                h.send(PacketCreator.makerEnableActions());
                break;

            case 4: // no req skill level
                h.send(PacketCreator.serverNotice(1, "You don't have enough Maker level to complete this operation."));
                h.send(PacketCreator.makerEnableActions());
                break;

            case 5: // inventory full
                h.send(PacketCreator.serverNotice(1, "Your inventory is full."));
                h.send(PacketCreator.makerEnableActions());
                break;

            default:
                if (toDisassemble != -1) {
                    h.removeEquipAt(pos);
                } else {
                    for (Pair<Integer, Integer> pair : recipe.getReqItems()) {
                        h.take(pair.getLeft(), pair.getRight());
                    }
                }

                int cost = recipe.getCost();
                if (stimulantid == -1 && reagentids.isEmpty()) {
                    // Ticket 112: MapleRoot handed the made items out only inside "cost > 0", so every free recipe
                    // (a monster crystal from leftovers, the Maker 2 test) ate the materials and gave nothing. Stock
                    // Cosmic pays the fee when there is one and always gives the items.
                    if (cost > 0) {
                        h.payMeso(cost);
                    }
                    for (Pair<Integer, Integer> pair : recipe.getGainItems()) {
                        h.give(pair.getLeft() + gemLuck(pair.getLeft()), pair.getRight());
                    }
                } else {
                    toCreate = recipe.getGainItems().get(0).getLeft();

                    if (stimulantid != -1) {
                        h.take(stimulantid, 1);
                    }
                    if (!reagentids.isEmpty()) {
                        for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
                            h.take(r.getKey(), r.getValue());
                        }
                    }

                    if (cost > 0) {
                        h.payMeso(cost);
                    }
                    makerSucceeded = MakerService.addBoostedMakerItem(h, toCreate, stimulantid, reagentids);
                }

                // thanks inhyuk for noticing missing MAKER_RESULT packets
                if (type == 3) {
                    h.send(PacketCreator.makerResultCrystal(recipe.getGainItems().get(0).getLeft(), recipe.getReqItems().get(0).getLeft()));
                } else if (type == 4) {
                    h.send(PacketCreator.makerResultDesynth(recipe.getReqItems().get(0).getLeft(), recipe.getCost(), recipe.getGainItems()));
                } else {
                    h.send(PacketCreator.makerResult(makerSucceeded, recipe.getGainItems().get(0).getLeft(), recipe.getGainItems().get(0).getRight(), recipe.getCost(), recipe.getReqItems(), stimulantid, new LinkedList<>(reagentids.keySet())));
                }

                h.send(PacketCreator.showMakerEffect(makerSucceeded));
                if (chr.getMap() != null) {
                    chr.getMap().broadcastMessage(chr, PacketCreator.showForeignMakerEffect(chr.getId(), makerSucceeded), false);
                }

                if (toCreate == MAKER_2_TEST_CRYSTAL && type == 3 && chr.getQuestStatus(6033) == 1) {
                    chr.setQuestProgress(6033, 0, "1");
                }
        }
    }

    /**
     * MapleRoot's gem luck, kept as it was (ticket 112 left it for the owner): crafting a basic gem (4250xx0) gives
     * the next grade 10% of the time and the one after 1%; a 4250xx1 gem gives the next grade 5% of the time.
     * Monster crystals (4260xxx) and everything else: no luck. The bonus to add to the made item's id.
     */
    static int gemLuck(int itemId) {
        if (itemId - 4250000 < 2000 && itemId > 4250000) {
            int rand = Randomizer.rand(1, 100);
            if (itemId % 10 == 0) {
                if (rand == 1) {
                    return 2;
                } else if (rand < 12) {
                    return 1;
                }
            } else if (itemId % 10 == 1) {
                if (rand < 6) {
                    return 1;
                }
            }
        }
        return 0;
    }

    public static boolean addBoostedMakerItem(Client c, int itemid, int stimulantid, Map<Integer, Short> reagentids) {
        return addBoostedMakerItem(of(c), itemid, stimulantid, reagentids);
    }

    public static boolean addBoostedMakerItem(Hands h, int itemid, int stimulantid, Map<Integer, Short> reagentids) {
        if (stimulantid != -1 && !ItemInformationProvider.rollSuccessChance(STIMULANT_SUCCESS_PERCENT)) {
            return false;
        }

        Item item = ii().getEquipById(itemid);
        if (item == null) {
            return false;
        }

        Equip eqp = (Equip) item;
        if (ItemConstants.isAccessory(item.getItemId()) && eqp.getUpgradeSlots() <= 0) {
            eqp.setUpgradeSlots(3);
        }

        if (!reagentids.isEmpty()) {
            Map<String, Integer> stats = new LinkedHashMap<>();
            List<Short> randOption = new LinkedList<>();
            List<Short> randStat = new LinkedList<>();

            for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
                Pair<String, Integer> reagentBuff = ii().getMakerReagentStatUpgrade(r.getKey());

                if (reagentBuff != null) {
                    String s = reagentBuff.getLeft();

                    if (s.substring(0, 4).contains("rand")) {
                        if (s.substring(4).equals("Stat")) {
                            randStat.add((short) (reagentBuff.getRight() * r.getValue()));
                        } else {
                            randOption.add((short) (reagentBuff.getRight() * r.getValue()));
                        }
                    } else {
                        String stat = s.substring(3);

                        if (!stat.equals("ReqLevel")) {    // improve req level... really?
                            switch (stat) {
                                case "MaxHP":
                                    stat = "MHP";
                                    break;

                                case "MaxMP":
                                    stat = "MMP";
                                    break;
                            }

                            Integer d = stats.get(stat);
                            if (d == null) {
                                stats.put(stat, reagentBuff.getRight() * r.getValue());
                            } else {
                                stats.put(stat, d + (reagentBuff.getRight() * r.getValue()));
                            }
                        }
                    }
                }
            }

            ItemInformationProvider.improveEquipStats(eqp, stats);

            for (Short sh : randStat) {
                ii().scrollOptionEquipWithChaos(eqp, sh, false);
            }

            for (Short sh : randOption) {
                ii().scrollOptionEquipWithChaos(eqp, sh, true);
            }
        }

        if (stimulantid != -1) {
            eqp = ii().randomizeUpgradeStats(eqp);
        }

        h.giveEquip(item);
        return true;
    }

    public static boolean addBoostedMakerItemSimple(Client c, int itemid, int stimulantid, Map<Integer, Short> reagentids) {
        if (stimulantid != -1 && !ItemInformationProvider.rollSuccessChance(STIMULANT_SUCCESS_PERCENT)) {
            return false;
        }

        Item item = ii().getEquipById(itemid);
        if (item == null) {
            return false;
        }

        Equip eqp = (Equip) item;

        if (!reagentids.isEmpty()) {
            Map<String, Integer> stats = new LinkedHashMap<>();
            List<Short> randOption = new LinkedList<>();
            List<Short> randStat = new LinkedList<>();

            for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
                Pair<String, Integer> reagentBuff = ii().getMakerReagentStatUpgrade(r.getKey());

                if (reagentBuff != null) {
                    String s = reagentBuff.getLeft();

                    if (s.substring(0, 4).contains("rand")) {
                        if (s.substring(4).equals("Stat")) {
                            randStat.add((short) (reagentBuff.getRight() * r.getValue()));
                        } else {
                            randOption.add((short) (reagentBuff.getRight() * r.getValue()));
                        }
                    } else {
                        String stat = s.substring(3);

                        if (!stat.equals("ReqLevel")) {    // improve req level... really?
                            switch (stat) {
                                case "MaxHP":
                                    stat = "MHP";
                                    break;

                                case "MaxMP":
                                    stat = "MMP";
                                    break;
                            }

                            Integer d = stats.get(stat);
                            if (d == null) {
                                stats.put(stat, reagentBuff.getRight() * r.getValue());
                            } else {
                                stats.put(stat, d + (reagentBuff.getRight() * r.getValue()));
                            }
                        }
                    }
                }
            }

            ItemInformationProvider.improveEquipStats(eqp, false);

            for (Short sh : randStat) {
                ii().scrollOptionEquipWithChaos(eqp, sh, false);
            }

            for (Short sh : randOption) {
                ii().scrollOptionEquipWithChaos(eqp, sh, true);
            }
        }

        if (stimulantid != -1) {
            eqp = ii().randomizeUpgradeStats(eqp);
        }

        c.getAbstractPlayerInteraction().gainItem(itemid, (short)-1, false);
        InventoryManipulator.addFromDrop(c, item, false, -1);
        return true;
    }
}
