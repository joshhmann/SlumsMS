package soloMapling.autoqa;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.MakerService;
import server.ItemInformationProvider;
import server.MakerItemFactory;
import soloMapling.ArtificialPlayer.BotMakerSystem.BotMaker;
import soloMapling.ArtificialPlayer.BotMakerSystem.MakerGearScore;
import soloMapling.ArtificialPlayer.BotConvo.TalkQa;
import soloMapling.Economy.EconomyMeters;

import java.util.ArrayList;
import java.util.List;

/**
 * Ticket 112, decision 3 (auto-QA t-mk-2): the bots' Maker batch, run once for real on a fresh throwaway bot. The bot
 * holds 100 of a monster leftover (one crystal), two cheap junk equips and, first in its bag, one junk equip whose
 * client fee is more than its wallet; its wallet is exactly the two cheap fees. {@link BotMaker#townBatch} then must
 * make the crystal and disassemble the two cheap equips through MakerService (the real fee off the wallet, to the
 * meso), keep the bad equip (out of mesos, skipped) and carry on past it, and count three operations - not the
 * crystals they gave - on the bot's cap and the world's. The bot is despawned, its day forgotten and the world's
 * units put back. SKIPs when maker.botBatch is off. The verdict rules are static, for tests.
 */
final class ProbesMaker {

    static final List<String> NAMES = List.of("makerBatch");

    private static final int LEVEL = 60;

    private ProbesMaker() {
    }

    static List<String> probe(String what, SyntheticPlayer p, String... a) {
        try {
            return "makerBatch".equals(what) ? makerBatch(p) : null;
        } catch (Exception e) {
            return List.of("FAIL probe " + what + " threw " + e);
        }
    }

    /** What the staged batch did, in numbers. */
    record Outcome(int made, int crystalsExpected, int goodEquips, long mesoBefore, long mesoAfter, long feeExpected,
                   int leftoversLeft, int crystalsGained, int goodGone, boolean badKept, int botOps, long worldOps) {
    }

    /** Everything that went wrong; empty is a PASS. */
    static List<String> problems(Outcome o) {
        List<String> bad = new ArrayList<>();
        int ops = o.crystalsExpected() + o.goodEquips();
        if (o.made() != ops) {
            bad.add("the batch made " + o.made() + " operations, want " + ops);
        }
        if (o.leftoversLeft() != 0 || o.crystalsGained() < o.crystalsExpected()) {
            bad.add("crystals from leftovers: " + o.leftoversLeft() + " leftovers left, " + o.crystalsGained()
                    + " crystals gained, want 0 left and at least " + o.crystalsExpected());
        }
        if (o.goodGone() != o.goodEquips()) {
            bad.add("disassembled " + o.goodGone() + " of " + o.goodEquips() + " junk equips");
        }
        if (o.feeExpected() <= 0) {
            bad.add("the staged fee is 0, so the check proves nothing about paying it");
        }
        if (o.mesoBefore() - o.mesoAfter() != o.feeExpected()) {
            bad.add("mesos went from " + o.mesoBefore() + " to " + o.mesoAfter() + " (down " + (o.mesoBefore() - o.mesoAfter())
                    + "), the client's fee is " + o.feeExpected());
        }
        if (!o.badKept()) {
            bad.add("the bad equip was disassembled or lost instead of skipped");
        }
        if (o.botOps() != ops) {
            bad.add("the bot's cap counted " + o.botOps() + ", want " + ops + " operations (not " + o.crystalsGained()
                    + " crystals)");
        }
        if (o.worldOps() != ops) {
            bad.add("the world's cap counted " + o.worldOps() + ", want " + ops + " operations");
        }
        return bad;
    }

    private static List<Item> carried(Inventory inv) {
        List<Item> out = new ArrayList<>();
        inv.lockInventory();
        try {
            for (Item it : inv.list()) {
                out.add(it);
            }
        } finally {
            inv.unlockInventory();
        }
        return out;
    }

    private static int firstLeftover(ItemInformationProvider ii) {
        for (int id = 4000000; id < 4001000; id++) {
            if (ii.getMakerCrystalFromLeftover(id) != -1 && !ii.isQuestItem(id)) {
                return id;
            }
        }
        return -1;
    }

    /** Junk equips by client fee, cheapest first: has a disassembly recipe, would not be worn by the bot. */
    private static List<Equip> candidates(Character bot, MakerGearScore score) {
        List<Equip> out = new ArrayList<>();
        int[][] ranges = {{1002000, 1002200}, {1040000, 1040150}, {1050000, 1050050}, {1060000, 1060150},
                {1072000, 1072150}, {1302000, 1302030}, {1322000, 1322030}, {1432000, 1432020}};
        for (int[] r : ranges) {
            for (int id = r[0]; id < r[1]; id++) {
                if (!MakerService.canDisassemble(id)) {
                    continue;
                }
                Equip e = new Equip(id, (short) 0);
                if (score.upgradeValue(bot, e) <= 0 && MakerItemFactory.getDisassemblyFee(e) > 0) {
                    out.add(e);
                }
            }
        }
        out.sort((x, y) -> Integer.compare(MakerItemFactory.getDisassemblyFee(x), MakerItemFactory.getDisassemblyFee(y)));
        return out;
    }

    static List<String> makerBatch(SyntheticPlayer p) {
        if (!BotMaker.enabled()) {
            return List.of("SKIP the bots' Maker batch is off (-Dsolomapling.economy.maker.botBatch=true)");
        }
        List<String> ev = new ArrayList<>();
        List<Character> spawned = new ArrayList<>();
        Character bot = null;
        long worldDone = 0;
        try {
            spawned.addAll(TalkQa.spawn(p.character(), 1, true, LEVEL));
            if (spawned.isEmpty()) {
                return List.of("FAIL no fresh bot could be spawned next to the pretend player");
            }
            bot = spawned.get(0);
            Probes162.freeze(bot);
            BotMaker.forget(bot.getId());
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int leftover = firstLeftover(ii);
            List<Equip> pool = candidates(bot, MakerGearScore.live());
            if (leftover < 0 || pool.size() < 2) {
                return List.of("FAIL could not find Maker data to stage with (leftover " + leftover + ", " + pool.size()
                        + " junk equip candidates)");
            }
            Equip cheap = pool.get(0);
            Equip pricey = pool.get(pool.size() - 1);
            long cheapFee = MakerItemFactory.getDisassemblyFee(cheap);
            long priceyFee = MakerItemFactory.getDisassemblyFee(pricey);
            if (priceyFee <= 2 * cheapFee) {
                return List.of("FAIL could not stage a bad equip: fees " + cheapFee + " and " + priceyFee);
            }
            long room = BotMaker.worldCapLeft();
            if (room < 3) {
                return List.of("SKIP the world's Maker cap for today is used up (" + room + " operations left)");
            }
            // an empty bag: the fresh bot's own gear and leftovers must not join the batch
            Inventory eq = bot.getInventory(InventoryType.EQUIP);
            for (Item it : carried(eq)) {
                if (it.getPosition() > 0) {
                    eq.removeSlot(it.getPosition());
                }
            }
            Inventory etc = bot.getInventory(InventoryType.ETC);
            for (Item it : carried(etc)) {
                if (it.getItemId() / 10000 == 400) {
                    etc.removeSlot(it.getPosition());
                }
            }
            Equip bad = new Equip(pricey.getItemId(), (short) 0);
            Equip good1 = new Equip(cheap.getItemId(), (short) 0);
            Equip good2 = new Equip(cheap.getItemId(), (short) 0);
            eq.addItem(bad);                                     // first in the bag: the batch meets it first
            eq.addItem(good1);
            eq.addItem(good2);
            if (!(bad.getPosition() < good1.getPosition() && good1.getPosition() < good2.getPosition())) {
                return List.of("FAIL the staged bag is out of order, the bad equip is not met first");
            }
            etc.addItem(new Item(leftover, (short) 0, (short) MakerService.LEFTOVERS_PER_CRYSTAL));
            long fee = 2 * cheapFee;
            bot.gainMeso((int) (fee - bot.getMeso()), false, false, false);
            int crystal = ii.getMakerCrystalFromLeftover(leftover);
            int crystalsBefore = etc.countById(crystal);
            long worldBefore = EconomyMeters.get(BotMaker.WORLD_METER);
            ev.add("bot " + bot.getName() + " lv " + bot.getLevel() + ": 100 x " + leftover + ", 2 x " + cheap.getItemId()
                    + " (fee " + cheapFee + " each), bad " + pricey.getItemId() + " (fee " + priceyFee + "), wallet "
                    + bot.getMeso());

            BotMaker.Result r = BotMaker.townBatch(bot);
            worldDone = EconomyMeters.get(BotMaker.WORLD_METER) - worldBefore;
            ev.add("batch: made " + r.made() + ", stopped by " + r.stoppedBy() + "; Maker level "
                    + MakerService.getMakerSkillLevel(bot));

            boolean badKept = eq.getItem(bad.getPosition()) != null && eq.getItem(bad.getPosition()).getItemId() == pricey.getItemId();
            int goodGone = (eq.getItem(good1.getPosition()) == null ? 1 : 0) + (eq.getItem(good2.getPosition()) == null ? 1 : 0);
            int crystalsAfter = etc.countById(crystal);
            Outcome o = new Outcome(r.made(), 1, 2, fee, bot.getMeso(), fee, etc.countById(leftover),
                    Math.max(0, crystalsAfter - crystalsBefore), goodGone, badKept, BotMaker.opsToday(bot.getId()), worldDone);
            ev.add(o.toString());
            return Probes160.verdict(problems(o), "the batch made a crystal and disassembled 2 equips through the Maker path, "
                    + "paid the client's fee to the meso, skipped the bad equip and carried on, and the caps counted 3 operations", ev);
        } finally {
            if (worldDone != 0) {
                EconomyMeters.add(BotMaker.WORLD_METER, -worldDone);
            }
            if (bot != null) {
                BotMaker.forget(bot.getId());
            }
            ev.add(TalkQa.teardown(spawned));
        }
    }
}
