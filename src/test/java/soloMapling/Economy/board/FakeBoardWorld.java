package soloMapling.Economy.board;

import soloMapling.Economy.MegaBuyDesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A board world for tests: accounts, characters, items, bots' stock and what was sent, all in maps. */
public class FakeBoardWorld implements BoardWorld {
    long now = 1_793_000_000_000L;
    double roll = 0.0;
    final Map<String, Board.Owner> owners = new HashMap<>();
    final Set<Integer> accounts = new HashSet<>();
    final Map<Integer, String> items = new HashMap<>();
    final Set<Integer> untradeable = new HashSet<>();
    public final Map<Integer, Long> listings = new HashMap<>();
    final List<MegaBuyDesk.Stock> stock = new ArrayList<>();
    final List<BoardOffers.Buyer> buyers = new ArrayList<>();
    final Map<Integer, Recipient> online = new HashMap<>();
    final Map<Integer, String> lastPlayed = new HashMap<>();
    final List<BotBoardPosts.Candidate> candidates = new ArrayList<>();
    final Set<Integer> unbacked = new HashSet<>();
    public final List<String> whispers = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public final List<String> yellows = new ArrayList<>();
    boolean whisperOk = true;
    int ownerLookups;

    public FakeBoardWorld account(int account, String... characters) {
        accounts.add(account);
        int id = account * 10;
        for (String c : characters) {
            owners.put(c.toLowerCase(Locale.ROOT), new Board.Owner(id++, account, c));
        }
        return this;
    }

    public FakeBoardWorld item(int id, String name) {
        items.put(id, name);
        return this;
    }

    public MegaBuyDesk.Stock bag(int botId, String bot, int itemId, int qty, long fair) {
        MegaBuyDesk.Stock s = new MegaBuyDesk.Stock(MegaBuyDesk.Source.BAG, botId, bot, 1, itemId, items.get(itemId), qty,
                fair, 0, "im at henesys ch1", null);
        stock.add(s);
        return s;
    }

    public void goOnline(int account, String name) {
        Board.Owner o = owners.get(name.toLowerCase(Locale.ROOT));
        online.put(account, new Recipient(o.characterId(), o.name(), null));
    }

    @Override public long now() { return now; }
    @Override public double roll() { return roll; }
    @Override public int between(int lo, int hi) { return lo; }
    @Override public void later(long delayMs, Runnable task) { task.run(); }
    @Override public String itemName(int itemId) { return items.getOrDefault(itemId, ""); }
    @Override public Map<Integer, Long> lowestListings() { return listings; }

    @Override
    public List<MegaBuyDesk.Stock> sellers(int itemId, int posterCharacterId) {
        return stock.stream().filter(s -> s.itemId() == itemId).toList();
    }

    @Override
    public List<BoardOffers.Buyer> buyers(int itemId, int qty, int posterCharacterId) {
        return buyers;
    }

    @Override public boolean stillHas(MegaBuyDesk.Stock s) { return stock.contains(s); }

    @Override
    public long spendable(int botId) {
        return buyers.stream().filter(b -> b.botId() == botId).mapToLong(BoardOffers.Buyer::spendable).findFirst()
                .orElse(0);
    }

    @Override public Recipient online(int account, String preferred) { return online.get(account); }
    @Override public String lastPlayed(int account) { return lastPlayed.get(account); }

    @Override
    public boolean whisper(Recipient to, String botName, int channel, String line) {
        if (whisperOk) {
            whispers.add(botName + " -> " + to.name() + ": " + line);
        }
        return whisperOk;
    }

    @Override
    public boolean note(String from, String toCharacter, String text) {
        notes.add(from + " -> " + toCharacter + ": " + text);
        return true;
    }

    @Override public void yellow(Recipient to, String text) { yellows.add(to.name() + ": " + text); }
    @Override public void quote(MegaBuyDesk.Stock s, Recipient to, long price, int rounds) { }
    @Override public List<BotBoardPosts.Candidate> botPostCandidates() { return candidates; }
    @Override public boolean stillBacks(Board.Post p) { return !unbacked.contains(p.characterId()); }

    @Override
    public Board.Owner owner(String character) {
        ownerLookups++;
        return owners.get(character.toLowerCase(Locale.ROOT));
    }

    @Override public boolean accountExists(int account) { return accounts.contains(account); }
    @Override public boolean itemExists(int itemId) { return items.containsKey(itemId); }
    @Override public boolean tradeable(int itemId) { return !untradeable.contains(itemId); }
    @Override public boolean equip(int itemId) { return itemId / 1_000_000 == 1; }
}
