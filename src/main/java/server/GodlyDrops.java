package server;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import config.YamlConfig;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/**
 * Godly drops (owner 2026-10-25, 1.5.2: "rare and modest"). A small share of the equips mobs drop
 * ({@code GODLY_DROP_PERCENT}, default 1.5%) comes with a light, pre-applied chaos scroll: {@code GODLY_DROP_STATS}
 * (3) of the stats the item already has, picked at random, each go up by {@code GODLY_DROP_MIN_BONUS}..
 * {@code GODLY_DROP_MAX_BONUS} (+1..+5) on top of the normal drop roll. Stats the item lacks stay 0, as with a chaos
 * scroll. Idea from nutnnut/Cosmic's GODLY_STATS_* (ae8933d19 and earlier), with a much smaller bonus and rate.
 * <p>
 * Sources: monster drops only (a mob's own table and the global drops), whoever killed it, player or bot. Not shops,
 * quest or NPC rewards, reactors, gachapon or Maker.
 * <p>
 * A godly drop is marked so the economy rules ({@code soloMapling.Economy.GearGrade}, and through it SaleRules, bot
 * shops, consignment and bot scrolling) treat it as godly, although a +1..+5 bump alone stays under their stat-gain
 * line. The mark is the item's {@code giftFrom} field: persisted with every item row, copied by {@link Equip#copy()},
 * and only ever shown by the client for Cash Shop items, which never drop from mobs. No migration needed.
 */
public final class GodlyDrops {

    /** The giftFrom mark of a godly drop; '~' cannot appear in a character name, so no gift sender collides. */
    public static final String MARK = "~godly";

    private GodlyDrops() {
    }

    public static boolean isGodlyDrop(Item item) {
        return item != null && MARK.equals(item.getGiftFrom());
    }

    /**
     * Gives a freshly rolled mob-drop equip its godly chance ({@code GODLY_DROP_PERCENT}). Call it right after the
     * normal {@link ItemInformationProvider#randomizeStats} roll at a monster drop site.
     *
     * @return the same equip, for chaining
     */
    public static Equip rollMobDrop(Equip equip) {
        if (equip != null && Randomizer.nextDouble() * 100.0 < YamlConfig.config.server.GODLY_DROP_PERCENT) {
            makeGodly(equip, Randomizer::rand);
        }
        return equip;
    }

    /** A monster drop of {@code itemId}: the equip from the item data, the normal stat roll, then the godly chance. */
    public static Equip mobDropEquip(ItemInformationProvider ii, int itemId) {
        return rollMobDrop(ii.randomizeStats((Equip) ii.getEquipById(itemId)));
    }

    /**
     * Applies the godly bump and marks the item. {@code rand.applyAsInt(lo, hi)} returns lo..hi inclusive.
     *
     * @return what changed, e.g. {@code ["STR +3", "W.ATT +1"]}; empty (and no mark) when the item has no stats
     */
    public static List<String> makeGodly(Equip equip, IntBinaryOperator rand) {
        List<Stat> present = new ArrayList<>();
        for (Stat s : Stat.values()) {
            if (s.get(equip) > 0) {
                present.add(s);
            }
        }
        List<String> bumped = new ArrayList<>();
        if (present.isEmpty()) {
            return bumped;
        }
        int lo = Math.max(1, YamlConfig.config.server.GODLY_DROP_MIN_BONUS);
        int hi = Math.max(lo, YamlConfig.config.server.GODLY_DROP_MAX_BONUS);
        int picks = Math.min(present.size(), Math.max(1, YamlConfig.config.server.GODLY_DROP_STATS));
        for (int i = 0; i < picks; i++) {
            Stat s = present.remove(rand.applyAsInt(0, present.size() - 1));
            int bonus = rand.applyAsInt(lo, hi);
            s.set(equip, (short) Math.min(Short.MAX_VALUE, s.get(equip) + bonus));
            bumped.add(s.label + " +" + bonus);
        }
        equip.setGiftFrom(MARK);
        return bumped;
    }

    /** Item id {@link #stageDrop} uses when none is given: a Sword (one-handed, level 0, W.ATT 17). */
    public static final int STAGE_DEFAULT_ITEM = 1302000;

    /**
     * QA staging ({@code !godlydrop}, {@code !qa run gl-1}): drops a godly-rolled equip at the GM's feet as a monster
     * would (not a player drop, so looting it shows the [Godly] note).
     *
     * @return what dropped, or null when the id is not an equip with stats
     */
    public static String stageDrop(Character gm, int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (!constants.inventory.ItemConstants.isEquipment(itemId) || ii.getEquipById(itemId) == null) {
            return null;
        }
        Equip equip = ii.randomizeStats((Equip) ii.getEquipById(itemId));
        List<String> bumped = makeGodly(equip, Randomizer::rand);
        if (bumped.isEmpty()) {
            return null;
        }
        gm.getMap().spawnItemDrop(gm, gm, equip, gm.getPosition(), true, false);
        String name = ii.getName(itemId);
        return (name != null ? name : String.valueOf(itemId)) + " (" + itemId + "): " + String.join(", ", bumped);
    }

    /** A small note to whoever loots a godly drop from the ground (players only; not shown for a re-dropped item). */
    public static void tellLooter(Character looter, Item item, boolean playerDrop) {
        if (playerDrop || !isGodlyDrop(item) || looter == null
                || soloMapling.ArtificialPlayer.BotHelpers.isArtificial(looter.getId())) {
            return;
        }
        String name = ItemInformationProvider.getInstance().getName(item.getItemId());
        looter.dropMessage(6, "[Godly] Your " + (name != null ? name : "item") + " dropped with bonus stats!");
    }

    private enum Stat {
        STR("STR"), DEX("DEX"), INT("INT"), LUK("LUK"), WATK("W.ATT"), MATK("M.ATT"), WDEF("W.DEF"),
        MDEF("M.DEF"), ACC("Accuracy"), AVOID("Avoid"), SPEED("Speed"), JUMP("Jump"), HP("HP"), MP("MP");

        final String label;

        Stat(String label) {
            this.label = label;
        }

        short get(Equip e) {
            return switch (this) {
                case STR -> e.getStr();
                case DEX -> e.getDex();
                case INT -> e.getInt();
                case LUK -> e.getLuk();
                case WATK -> e.getWatk();
                case MATK -> e.getMatk();
                case WDEF -> e.getWdef();
                case MDEF -> e.getMdef();
                case ACC -> e.getAcc();
                case AVOID -> e.getAvoid();
                case SPEED -> e.getSpeed();
                case JUMP -> e.getJump();
                case HP -> e.getHp();
                case MP -> e.getMp();
            };
        }

        void set(Equip e, short v) {
            switch (this) {
                case STR -> e.setStr(v);
                case DEX -> e.setDex(v);
                case INT -> e.setInt(v);
                case LUK -> e.setLuk(v);
                case WATK -> e.setWatk(v);
                case MATK -> e.setMatk(v);
                case WDEF -> e.setWdef(v);
                case MDEF -> e.setMdef(v);
                case ACC -> e.setAcc(v);
                case AVOID -> e.setAvoid(v);
                case SPEED -> e.setSpeed(v);
                case JUMP -> e.setJump(v);
                case HP -> e.setHp(v);
                case MP -> e.setMp(v);
            }
        }
    }
}
