package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Trash talk with bite (1.5.0 bot drama): a rival's roast built from real facts about the player's game - their level
 * against the map they grind, their job, today's deaths, a weapon far below their level, their KS habit. Only the game:
 * the lines and the model prompt never touch looks, family, real life or slurs ({@code ContentGuard} checks the result).
 */
public final class RivalRoasts {

    /** What a roast may use about a player. Unknown numbers are -1, unknown names null. */
    public record Facts(String player, int level, String job, String map, int mapMobLevel, int deathsToday,
                        String weapon, int weaponLevel, int ksCount) {

        /** One line for the model prompt and the QA log. */
        public String describe() {
            List<String> out = new ArrayList<>();
            out.add("level " + level);
            if (job != null) {
                out.add("job " + job);
            }
            if (map != null) {
                out.add("grinding " + map + (mapMobLevel >= 0 ? " (mobs lv " + mapMobLevel + ")" : ""));
            }
            out.add(deathsToday + " deaths today");
            if (weapon != null) {
                out.add("weapon " + weapon + (weaponLevel >= 0 ? " (lv " + weaponLevel + ")" : ""));
            }
            if (ksCount > 0) {
                out.add("KS'd this bot " + ksCount + " times");
            }
            return String.join(", ", out);
        }
    }

    /** A roast to say: the dialogue node and its tokens. */
    public record Roast(String node, Map<String, String> tokens) {
    }

    private RivalRoasts() {
    }

    static Facts facts(Character player, int ksCount, int deathsToday) {
        String job;
        try {
            job = player.getJob().name().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[0-9]", "").trim();
        } catch (RuntimeException e) {
            job = null;
        }
        String weapon = null;
        int weaponLevel = -1;
        try {
            Item w = player.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
            if (w != null) {
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                weapon = ii.getName(w.getItemId());
                Integer req = ii.getEquipLevelReq(w.getItemId());
                weaponLevel = req == null ? -1 : req;
            }
        } catch (RuntimeException e) {
            weapon = null;
        }
        int mobLevel;
        try {
            mobLevel = MapMobIndex.level(player.getMapId());
        } catch (RuntimeException | LinkageError e) {
            mobLevel = -1;
        }
        return new Facts(player.getName(), player.getLevel(), job, DramaKit.mapName(player), mobLevel, deathsToday,
                weapon == null ? null : weapon.toLowerCase(Locale.ROOT), weaponLevel, ksCount);
    }

    /**
     * The roasts these facts allow, juiciest first: over-levelled for the map, deaths, a weapon far below the level,
     * KSing; else their job or level.
     */
    static List<Roast> options(Facts f) {
        List<Roast> out = new ArrayList<>();
        Map<String, String> t = new HashMap<>();
        t.put("PLAYER", f.player());
        t.put("LEVEL", Integer.toString(f.level()));
        if (f.job() != null && !f.job().isBlank()) {
            t.put("JOB", f.job());
        }
        if (f.map() != null) {
            t.put("MAP", f.map());
        }
        t.put("DEATHS", Integer.toString(f.deathsToday()));
        if (f.weapon() != null) {
            t.put("WEAPON", f.weapon());
        }
        if (f.weaponLevel() >= 0) {
            t.put("WLEVEL", Integer.toString(f.weaponLevel()));
        }
        if (f.mapMobLevel() >= 0 && f.level() - f.mapMobLevel() >= 15 && f.map() != null) {
            out.add(new Roast("RoastOverleveled", t));
        }
        if (f.deathsToday() >= 2) {
            out.add(new Roast("RoastDeaths", t));
        }
        if (f.weaponLevel() >= 0 && f.level() - f.weaponLevel() >= 25 && f.weapon() != null) {
            out.add(new Roast("RoastGear", t));
        }
        if (f.ksCount() >= 3) {
            out.add(new Roast("RoastKs", t));
        }
        if (t.containsKey("JOB")) {
            out.add(new Roast("RoastJob", t));
        }
        out.add(new Roast("RoastLevel", t));
        return out;
    }

    /** One roast: one of the two juiciest options (so the same fact is not always the one used). */
    static Roast pick(Facts f, Random rng) {
        List<Roast> all = options(f);
        int top = Math.min(2, all.size());
        return all.get(rng.nextInt(top));
    }

    /** The model prompt for a roast: the facts, and the rules (game only, short, no emojis). */
    static String prompt(String botName, Facts f, boolean nemesis) {
        return "You are " + botName + ", a MapleStory player in 2008, trash-talking " + f.player() + ", your "
                + (nemesis ? "nemesis" : "rival") + " in the game (they KS'd you). Facts: " + f.describe() + ". Write"
                + " ONE short roast line, under 60 characters, lowercase, old-school game chat, about their gameplay"
                + " only: their level, job, map, deaths, gear or KS habit. Never: real-life insults, slurs, looks,"
                + " family, the real world, threats, anything sexual. No emojis, no quotes, no hashtags.";
    }
}
