package soloMapling.qa;

import client.Character;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A QaWorld that records every call and keeps a simple inventory and meso count per GM. */
class FakeQaWorld implements QaWorld {
    final List<String> calls = new ArrayList<>();
    final Map<Integer, Integer> inventory = new HashMap<>();
    final Map<Integer, Integer> values = new HashMap<>();
    final List<Integer> despawned = new ArrayList<>();
    final List<String> messages = new ArrayList<>();
    Character online;
    int meso;
    int nextBot = 1_000_000_100;
    boolean shopOpen;
    boolean merchantOpen;
    boolean petOut;
    List<int[]> shopStock = List.of();

    @Override public Character online(int id) { return online; }
    @Override public void message(Character gm, String line) { messages.add(line); }
    @Override public boolean warp(Character gm, int mapId) { calls.add("warp " + mapId); return true; }
    @Override public boolean warpToPortal(Character gm, int mapId, String portal) { calls.add("warp " + mapId + " " + portal); return true; }
    @Override public String whereIs(Character gm) { return "map " + gm.getMapId() + " at (0, 0)"; }
    @Override public int meso(Character gm) { return meso; }
    @Override public void gainMeso(Character gm, int delta) { meso += delta; calls.add("meso " + delta); }

    @Override public int giveItem(Character gm, int itemId, int quantity) {
        inventory.merge(itemId, quantity, Integer::sum);
        calls.add("give " + itemId + " x" + quantity);
        return quantity;
    }

    @Override public boolean givePet(Character gm, int petItemId, int days) {
        inventory.merge(petItemId, 1, Integer::sum);
        calls.add("pet " + petItemId);
        return true;
    }

    @Override public int countItem(Character gm, int itemId) { return inventory.getOrDefault(itemId, 0); }

    @Override public int takeItem(Character gm, int itemId, int quantity) {
        int take = Math.min(quantity, countItem(gm, itemId));
        inventory.merge(itemId, -take, Integer::sum);
        calls.add("take " + itemId + " x" + take);
        return take;
    }

    @Override public boolean petSummoned(Character gm, int petItemId) { return petOut; }
    @Override public String itemName(int itemId) { return "item" + itemId; }
    @Override public Integer marketValue(int itemId) { return values.get(itemId); }
    @Override public Point npcPosition(Character gm, int npcId) { return new Point(100, 200); }

    @Override public List<Integer> spawnIdleBots(Character gm, Point around, int count) {
        return bots("idle", count);
    }
    @Override public List<Integer> spawnIdleBots(Character gm, Point around, int count, int level) {
        calls.add("idle bots lv " + level);
        return bots("idle", count);
    }
    @Override public String botLabel(Character gm, int botId) { return "Botty" + botId + " (lv 30, 90 px to your right)"; }

    @Override public List<Integer> spawnFmShoppers(Character gm, int count) {
        return bots("fm", count);
    }

    @Override public List<Integer> spawnShowcase(Character gm, String[] args, List<String> report) {
        report.add("HERO Botty (1) [1121008]");
        return bots("showcase", 2);
    }

    private List<Integer> bots(String what, int count) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(nextBot++);
        }
        calls.add("spawn " + what + " " + ids);
        return ids;
    }

    @Override public void despawnBot(int botId) { despawned.add(botId); calls.add("despawn " + botId); }

    @Override public String openPlayerShop(Character gm, String description, List<int[]> stock) {
        for (int[] s : stock) {
            inventory.merge(s[0], -1, Integer::sum);
        }
        shopStock = stock;
        shopOpen = true;
        calls.add("open shop " + stock.size());
        return null;
    }

    @Override public boolean closePlayerShop(Character gm) {
        calls.add("close shop");
        if (!shopOpen) {
            return false;
        }
        for (int[] s : shopStock) {
            inventory.merge(s[0], 1, Integer::sum); // nothing sold in the fake
        }
        shopOpen = false;
        return true;
    }

    @Override public String closeOwnMerchant(Character gm) {
        calls.add("close merchant");
        boolean was = merchantOpen;
        merchantOpen = false;
        return was ? "Closed your hired merchant" : "You have no hired merchant open.";
    }

    @Override public List<String> channelLoads() { return List.of("ch 1: 1 players, 300 bots, bar 800/800", "ch 2: 0 players, 0 bots, bar 0/800"); }
    @Override public String sellerCapStatus(Character gm) { return "account 1: 0 of 5000000 paid today"; }
    @Override public int guildCost() { return 1_500_000; }

    Point mobSpot = new Point(500, 300);
    int mobs = 12;
    boolean idleSafety = true;
    final Map<Integer, String> idle = new HashMap<>();

    @Override public Point busiestMobSpot(Character gm) { return mobSpot; }
    @Override public int liveMobCount(Character gm) { return mobs; }
    @Override public void holdIdle(int botId, String reason) { idle.put(botId, reason); calls.add("idle " + botId + " " + reason); }
    @Override public String idleStats(int botId) { return despawned.contains(botId) ? null : "bot" + botId + ": idle hits 0, safe-spot moves 0"; }

    @Override public boolean setIdleSafety(boolean on) {
        boolean was = idleSafety;
        idleSafety = on;
        calls.add("idle safety " + on);
        return was;
    }

    final List<String> scrollSteps = new ArrayList<>();

    @Override public int giveBotItem(int botId, int itemId, int quantity) {
        calls.add("bot " + botId + " give " + itemId + " x" + quantity);
        return quantity;
    }

    @Override public List<String> botScrollStep(int botId, String appetite) {
        calls.add("bot " + botId + " scroll " + appetite);
        return scrollSteps.isEmpty() ? List.of("bot has nothing it would scroll") : List.of(scrollSteps.remove(0));
    }

    final List<String> wealthSteps = new ArrayList<>();
    long botMeso;
    long ceiling = 5_000_000;

    @Override public String gachaRoll(Character gm, String town, String tier) {
        calls.add("gacha " + town + " " + tier);
        return "rolled at " + town + (tier == null ? "" : " " + tier);
    }

    @Override public List<String> gachaOdds(String town, int n) {
        calls.add("odds " + town + " " + n);
        return List.of(town + " rare: 5% per ticket");
    }

    @Override public int gachaMap(String town) { return "henesys".equals(town) ? 100000100 : 0; }

    @Override public int giveBotMeso(int botId, int amount) {
        botMeso += amount;
        calls.add("bot " + botId + " meso " + amount);
        return amount;
    }

    @Override public long botCeiling(int botId) { return ceiling; }

    @Override public String botWealthLine(int botId) { return "bot " + botId + " holds " + botMeso; }

    @Override public List<String> botWealthStep(int botId) {
        calls.add("bot " + botId + " wealth step");
        if (wealthSteps.isEmpty()) {
            return List.of();
        }
        botMeso = Math.max(0, botMeso - 1_000_000);
        return List.of(wealthSteps.remove(0));
    }

    // ticket 58
    final List<Integer> unstaged = new ArrayList<>();
    boolean dramaOff;

    @Override public int stageAffinityBot(Character gm, String state, boolean training) {
        int id = nextBot++;
        calls.add("stage " + state + (training ? " training" : " social") + " " + id);
        return id;
    }

    @Override public void unstageAffinityBot(int botId) { unstaged.add(botId); calls.add("unstage " + botId); }

    @Override public boolean setBotDramaOff(Character gm, boolean off) {
        boolean was = dramaOff;
        dramaOff = off;
        calls.add("botdrama " + (off ? "off" : "on"));
        return was;
    }

    @Override public String affinityOf(int botId, Character gm) { return "rival: score -40"; }

    @Override public void holdKsWars(Character gm, boolean hold) { calls.add("hold ks wars " + hold); }
    @Override public String forceGrudge(int botId, Character gm) { calls.add("grudge " + botId); return "grudge + KS spree"; }

    final List<int[]> couplesUnstaged = new ArrayList<>();
    int coupleSteps;
    String weddingProblem;
    boolean eventChannel = true;

    @Override public int[] stageCouple(Character gm, String kind, Boolean sameGender) {
        int[] ids = {nextBot++, nextBot++};
        calls.add("couple " + ids[0] + " " + ids[1] + " " + kind + (sameGender == null ? "" : sameGender ? " same" : " mixed"));
        return ids;
    }

    @Override public void unstageCouple(int[] ids) { couplesUnstaged.add(ids); calls.add("uncouple " + ids[0]); }

    @Override public void coupleStep() { coupleSteps++; }

    @Override public String startWedding(int botId, String by) { calls.add("wedding " + botId); return weddingProblem; }
    @Override public String startWedding(int botId, String by, int mapId) {
        calls.add("wedding " + botId + " on " + mapId);
        return weddingProblem;
    }

    @Override public int stopWeddings(String by) { calls.add("stop weddings"); return 1; }

    @Override public boolean onEventChannel(Character gm) { return eventChannel; }

    // ---- 1.5.0 bot guilds ----
    String botGuild = "Legendz";
    String rival = "ZakumSlayerz";
    int gmGuild;
    final List<Integer> guildBotsUnstaged = new ArrayList<>();
    List<String> bossRunAnswer = List.of("boss run started at map 211042400 (you were warped there)");

    @Override public List<String> botGuildList() { return List.of("#1 Legendz (hardcore): 38 bots"); }

    @Override public String pickBotGuild(String wanted, boolean withRival) {
        if (botGuild == null) {
            return null;
        }
        return wanted == null || wanted.equalsIgnoreCase(botGuild) ? botGuild : null;
    }

    @Override public String rivalOf(String guild) { return rival; }

    @Override public int stageGuildBot(Character gm, String state, String guild, int rank) {
        int id = nextBot++;
        calls.add("guildbot " + state + " " + guild + " r" + rank + " " + id);
        return id;
    }

    @Override public int stageGuildGrinder(Character gm, String state, String guild, int rank) {
        int id = nextBot++;
        calls.add("guildgrinder " + state + " " + guild + " r" + rank + " " + id);
        return id;
    }

    /** What botGuildAction answers per action (default "<action> done"). */
    public final java.util.Map<String, List<String>> guildAnswers = new java.util.HashMap<>();

    @Override public void unstageGuildBot(int botId) { guildBotsUnstaged.add(botId); calls.add("unguildbot " + botId); }

    @Override public List<String> botGuildAction(Character gm, String action, String[] args) {
        calls.add("guild " + action + (args.length > 0 ? " " + String.join(" ", args) : ""));
        if (guildAnswers.containsKey(action)) {
            List<String> answer = guildAnswers.get(action);
            guildAnswers.remove(action); // once
            return answer;
        }
        return "bossrun".equals(action) ? bossRunAnswer : List.of(action + " done");
    }

    @Override public int guildIdOf(Character gm) { return gmGuild; }

    @Override public boolean leaveGuild(Character gm) {
        calls.add("leave guild");
        boolean was = gmGuild > 0;
        gmGuild = 0;
        return was;
    }

    boolean joinWorks = true;

    @Override public boolean joinBotGuild(Character gm, String guild) {
        calls.add("join guild " + guild);
        if (joinWorks) {
            gmGuild = 99;
        }
        return joinWorks;
    }

    // ---- 1.5.0 rc3 (cb-1 .. cb-4, sp-1) ----
    final List<Integer> grindersUnstaged = new ArrayList<>();
    int nextGrinder = 900;

    @Override public List<Integer> stageGrinders(Character gm, List<String> jobs, int count, int levelOffset,
                                                 boolean hold, boolean atGm) {
        int n = jobs.isEmpty() ? count : jobs.size();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(nextGrinder++);
        }
        calls.add("grinders " + (jobs.isEmpty() ? "random" : String.join(",", jobs)) + " x" + n + " offset "
                + levelOffset + (hold ? " held" : " free") + (atGm ? " at-gm" : " at-pack"));
        return ids;
    }

    @Override public void unstageGrinders(List<Integer> ids) { grindersUnstaged.addAll(ids); calls.add("ungrinders " + ids); }

    @Override public List<String> grinderReport(List<Integer> ids, String what) {
        calls.add("report " + what);
        return List.of(what + " of " + ids);
    }

    @Override public List<String> spreadTally(Character gm) { calls.add("spread"); return List.of("SPREAD 8 bots"); }

    @Override public List<String> grinderAction(List<Integer> ids, String action) {
        calls.add("act " + action);
        return List.of(action + " done");
    }

    @Override public List<String> stagePets(List<Integer> ids, boolean loots) {
        calls.add("pets " + ids + (loots ? " loot" : " noloot"));
        return List.of(ids.size() + " pets");
    }

    @Override public List<String> petReport(List<Integer> ids) { calls.add("pet report"); return List.of("pets of " + ids); }

    @Override public List<String> petDrops(Character gm, List<Integer> ids) { calls.add("pet drops " + ids); return List.of("dropped"); }

    @Override public String combatTuning() { return "human true"; }

    final List<String> drama = new ArrayList<>();
    boolean romance;
    int dramaUnstaged;

    @Override public List<String> dramaAction(Character gm, String area, String... words) {
        String line = area + " " + String.join(" ", words);
        drama.add(line.trim());
        calls.add("drama " + line.trim());
        if (words.length > 0 && ("on".equals(words[0]) || "off".equals(words[0])) && "romance".equals(area)) {
            romance = "on".equals(words[0]);
        }
        return List.of(line.trim() + ": ok");
    }

    @Override public int dramaUnstage(int gmId) { dramaUnstaged++; calls.add("drama unstage"); return 1; }

    @Override public boolean romanceOn(Character gm) { return romance; }

    // ticket 59
    @Override public int stageConvoBot(Character gm, String state, boolean training, String voice) {
        int id = nextBot++;
        calls.add("convobot " + state + (training ? " training" : " social") + (voice == null ? "" : " " + voice) + " " + id);
        return id;
    }

    @Override public void unstageConvoBot(int botId) { calls.add("unconvobot " + botId); }

    @Override public List<String> convoAction(Character gm, String action, List<Integer> bots, String[] args) {
        calls.add("convo " + action + (args.length == 0 ? "" : " " + String.join(" ", args)));
        return List.of("ok " + action);
    }
}
