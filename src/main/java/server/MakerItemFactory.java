/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server;

import client.inventory.Equip;
import client.inventory.Item;
import config.YamlConfig;
import constants.inventory.EquipType;
import tools.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * @author Jay Estrella, Ronan
 */
public class MakerItemFactory {
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    public static MakerItemCreateEntry getItemCreateEntry(int toCreate, int stimulantid, Map<Integer, Short> reagentids) {
        MakerItemCreateEntry makerEntry = ii.getMakerItemEntry(toCreate);
        if (makerEntry.isInvalid()) {
            return makerEntry;
        }

        int recipeMeso = ii.getMakerRecipeMeso(toCreate);
        if (recipeMeso >= 0) {
            // Ticket 112 follow-up: the v83 client works the fee out itself and shows it, so charge exactly that.
            List<Integer> gems = new ArrayList<>();
            for (Entry<Integer, Short> r : reagentids.entrySet()) {
                for (int i = 0; i < r.getValue(); i++) {
                    gems.add(r.getKey());
                }
            }
            makerEntry.setCost(clientCraftFee(recipeMeso, stimulantid != -1, gems));
        } else {
            // No recipe in ItemMake.img (the client could not show this one anyway): Cosmic's estimate.
            // THEY DECIDED FOR SOME BIZARRE PATTERN ON THE FEE THING, ALMOST RANDOMIZED.
            if (stimulantid != -1) {
                makerEntry.addCost(getMakerStimulantFee(toCreate));
            }

            if (!reagentids.isEmpty()) {
                for (Entry<Integer, Short> r : reagentids.entrySet()) {
                    makerEntry.addCost((getMakerReagentFee(toCreate, ((r.getKey() % 10) + 1))) * r.getValue());
                }
            }
        }

        makerEntry.trimCost();  // "commit" the real cost of the recipe.
        return makerEntry;
    }

    /**
     * The Maker fee the v83 client shows for a craft (CUIItemMaker, MapleStory.exe 0x824e8d): the recipe's
     * ItemMake.img meso plus a tenth of it for each "part" (1 for the craft, 5 for a stimulator, 3 per grade of each
     * applied gem: x0 basic 1, x1 intermediate 2, x2 advanced 3), cut down to a whole 1,000.
     */
    public static int clientCraftFee(int recipeMeso, boolean stimulant, Collection<Integer> gemIds) {
        int parts = 1 + (stimulant ? 5 : 0);
        for (int gem : gemIds) {
            parts += 3 * ((gem % 10) + 1);
        }
        long extra = Math.max(0L, (long) (recipeMeso / 10) * parts);
        return trimToThousand(recipeMeso + extra);
    }

    /**
     * The disassembly fee the v83 client shows (MapleStory.exe 0x5ca659): a tenth of the recipe's ItemMake.img meso,
     * times 100% for an item below its base stats (grade -1), 150% at grade 0 (and for anything it cannot grade),
     * 200% / 250% / 300% at grades 1 / 2 / 3, cut down to a whole 1,000.
     */
    public static int clientDisassemblyFee(int recipeMeso, int grade) {
        int percent = switch (grade) {
            case -1 -> 100;
            case 1 -> 200;
            case 2 -> 250;
            case 3 -> 300;
            default -> 150;
        };
        return trimToThousand((long) (recipeMeso / 10) * percent / 100);
    }

    /**
     * The client's quality grade of an equip (MapleStory.exe 0x5ca1b8): its stats above the item's base stats (STR,
     * DEX, INT, LUK, W/M.ATT, W/M.DEF, accuracy, avoid, speed, jump, and a tenth of max HP and MP; not hands).
     * Below 0: -1, under 6: 0, under 23: 1, under 40: 2, else 3. Cash items, mounts (19xxxxx) and items without base
     * stats grade 0.
     */
    public static int qualityGrade(Equip eq) {
        int itemId = eq.getItemId();
        if (itemId / 100000 == 19 || ii.isCash(itemId)) {
            return 0;
        }
        Map<String, Integer> base = ii.getEquipStats(itemId);
        if (base == null) {
            return 0;
        }
        int diff = (eq.getMp() - base.getOrDefault("MMP", 0)) / 10 + (eq.getHp() - base.getOrDefault("MHP", 0)) / 10;
        diff += eq.getStr() - base.getOrDefault("STR", 0);
        diff += eq.getDex() - base.getOrDefault("DEX", 0);
        diff += eq.getInt() - base.getOrDefault("INT", 0);
        diff += eq.getLuk() - base.getOrDefault("LUK", 0);
        diff += eq.getWatk() - base.getOrDefault("PAD", 0);
        diff += eq.getMatk() - base.getOrDefault("MAD", 0);
        diff += eq.getWdef() - base.getOrDefault("PDD", 0);
        diff += eq.getMdef() - base.getOrDefault("MDD", 0);
        diff += eq.getAcc() - base.getOrDefault("ACC", 0);
        diff += eq.getAvoid() - base.getOrDefault("EVA", 0);
        diff += eq.getSpeed() - base.getOrDefault("Speed", 0);
        diff += eq.getJump() - base.getOrDefault("Jump", 0);
        if (diff < 0) {
            return -1;
        } else if (diff < 6) {
            return 0;
        } else if (diff < 23) {
            return 1;
        } else if (diff < 40) {
            return 2;
        }
        return 3;
    }

    /** What disassembling this item costs: the fee the client shows for it (-1 when it has no Maker recipe). */
    public static int getDisassemblyFee(Item item) {
        int recipeMeso = ii.getMakerRecipeMeso(item.getItemId());
        if (recipeMeso < 0) {
            return ii.getMakerDisassembledFee(item.getItemId());   // not in ItemMake.img: Cosmic's estimate
        }
        return clientDisassemblyFee(recipeMeso, item instanceof Equip eq ? qualityGrade(eq) : 0);
    }

    private static int trimToThousand(long fee) {
        long rest = fee % 1000;
        if (rest > 0) {
            fee -= rest;
        }
        return (int) Math.min(Integer.MAX_VALUE, fee);
    }

    public static MakerItemCreateEntry generateLeftoverCrystalEntry(int fromLeftoverid, int crystalId) {
        MakerItemCreateEntry ret = new MakerItemCreateEntry(0, 0, 1);
        ret.addReqItem(fromLeftoverid, 100);
        ret.addGainItem(crystalId, 1);
        return ret;
    }

    public static MakerItemCreateEntry generateDisassemblyCrystalEntry(int fromEquipid, int cost, List<Pair<Integer, Integer>> gains) {     // equipment at specific position already taken
        MakerItemCreateEntry ret = new MakerItemCreateEntry(cost, 0, 1);
        ret.addReqItem(fromEquipid, 1);
        for (Pair<Integer, Integer> p : gains) {
            ret.addGainItem(p.getLeft(), p.getRight());
        }
        ret.trimCost();     // ticket 112 follow-up: uncommitted, the fee read 0 and disassembly was free
        return ret;
    }

    public static double getMakerStimulantFee(int itemId) {
        if (YamlConfig.config.server.USE_MAKER_FEE_HEURISTICS) {
            EquipType et = EquipType.getEquipTypeById(itemId);
            int eqpLevel = ii.getEquipLevelReq(itemId);

            switch (et) {
                case CAP:
                    return 1145.736246 * Math.exp(0.03336832546 * eqpLevel);

                case LONGCOAT:
                    return 2117.469118 * Math.exp(0.03355349137 * eqpLevel);

                case SHOES:
                    return 1218.624674 * Math.exp(0.0342266043 * eqpLevel);

                case GLOVES:
                    return 2129.531152 * Math.exp(0.03421778102 * eqpLevel);

                case COAT:
                    return 1770.630579 * Math.exp(0.03359768677 * eqpLevel);

                case PANTS:
                    return 1442.98837 * Math.exp(0.03444783295 * eqpLevel);

                case SHIELD:
                    return 6312.40136 * Math.exp(0.0237929527 * eqpLevel);

                default:    // weapons
                    return 4313.581428 * Math.exp(0.03147837094 * eqpLevel);
            }
        } else {
            return 14000;
        }
    }

    public static double getMakerReagentFee(int itemId, int reagentLevel) {
        if (YamlConfig.config.server.USE_MAKER_FEE_HEURISTICS) {
            EquipType et = EquipType.getEquipTypeById(itemId);
            int eqpLevel = ii.getEquipLevelReq(itemId);

            switch (et) {
                case CAP:
                    return 5592.01613 * Math.exp(0.02914576018 * eqpLevel) * reagentLevel;

                case LONGCOAT:
                    return 3405.23441 * Math.exp(0.03413001038 * eqpLevel) * reagentLevel;

                case SHOES:
                    return 2115.697484 * Math.exp(0.0354881705 * eqpLevel) * reagentLevel;

                case GLOVES:
                    return 4684.040894 * Math.exp(0.03166500585 * eqpLevel) * reagentLevel;

                case COAT:
                    return 2955.89017 * Math.exp(0.0339948456 * eqpLevel) * reagentLevel;

                case PANTS:
                    return 1774.722181 * Math.exp(0.03854321409 * eqpLevel) * reagentLevel;

                case SHIELD:
                    return 12014.11296 * Math.exp(0.02185471162 * eqpLevel) * reagentLevel;

                default:    // weapons
                    return 4538.650247 * Math.exp(0.0371980303 * eqpLevel) * reagentLevel;
            }
        } else {
            return 8000 * reagentLevel;
        }
    }

    public static class MakerItemCreateEntry {
        private final int reqLevel;
        private final int reqMakerLevel;
        private double cost;
        private int reqCost;
        private final List<Pair<Integer, Integer>> reqItems = new ArrayList<>(); // itemId / amount
        private final List<Pair<Integer, Integer>> gainItems = new ArrayList<>(); // itemId / amount

        public MakerItemCreateEntry(int cost, int reqLevel, int reqMakerLevel) {
            this.cost = cost;
            this.reqLevel = reqLevel;
            this.reqMakerLevel = reqMakerLevel;
        }

        public MakerItemCreateEntry(MakerItemCreateEntry mi) {
            this.cost = mi.cost;
            this.reqLevel = mi.reqLevel;
            this.reqMakerLevel = mi.reqMakerLevel;

            reqItems.addAll(mi.reqItems);

            gainItems.addAll(mi.gainItems);
        }

        public List<Pair<Integer, Integer>> getReqItems() {
            return reqItems;
        }

        public List<Pair<Integer, Integer>> getGainItems() {
            return gainItems;
        }

        public int getReqLevel() {
            return reqLevel;
        }

        public int getReqSkillLevel() {
            return reqMakerLevel;
        }

        public int getCost() {
            return reqCost;
        }

        public void setCost(double amount) {
            cost = amount;
        }

        public void addCost(double amount) {
            cost += amount;
        }

        protected void addReqItem(int itemId, int amount) {
            reqItems.add(new Pair<>(itemId, amount));
        }

        protected void addGainItem(int itemId, int amount) {
            gainItems.add(new Pair<>(itemId, amount));
        }

        public void trimCost() {
            reqCost = (int) (cost / 1000);
            reqCost *= 1000;
        }

        public boolean isInvalid() {    // thanks Rohenn, Wh1SK3Y for noticing some items not getting checked properly
            return reqLevel < 0;
        }
    }
}
