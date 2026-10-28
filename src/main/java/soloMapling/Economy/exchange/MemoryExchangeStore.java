package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.Ledger.Kind;
import soloMapling.Economy.exchange.Ledger.Posting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ticket 113: an in-memory {@link ExchangeStore} with the same rules as {@link JdbcExchangeStore}, for tests and dry
 * runs. Each write works on a copy and swaps it in only when every step passed, so a failure (or a simulated crash via
 * {@link #crashAfterSteps}) changes nothing, like a rolled-back transaction. It also models the game's side of a
 * wallet: {@link #login} loads an account's wallets into a session, the session spends, and {@link #logout} saves the
 * session's absolute values back, which is how the real server saves (and why the exchange only touches wallets of
 * offline accounts). Synchronized: tests call it from many threads.
 */
public final class MemoryExchangeStore implements ExchangeStore {

    private static final class State {
        final Map<Long, Long> nx = new HashMap<>();              // account -> nxCredit
        final Map<Long, Long> meso = new HashMap<>();            // character -> meso
        final Map<Long, Long> charAccount = new HashMap<>();     // character -> account
        final Map<Bucket, Long> balances = new HashMap<>();
        final List<Ledger.Row> ledger = new ArrayList<>();
        final Map<Long, OrderRow> orders = new LinkedHashMap<>();
        final Map<String, Long> requests = new HashMap<>();
        final List<FillRow> fills = new ArrayList<>();
        final Set<Long> fillIds = new HashSet<>();

        State copy() {
            State s = new State();
            s.nx.putAll(nx);
            s.meso.putAll(meso);
            s.charAccount.putAll(charAccount);
            s.balances.putAll(balances);
            s.ledger.addAll(ledger);
            s.orders.putAll(orders);
            s.requests.putAll(requests);
            s.fills.addAll(fills);
            s.fillIds.addAll(fillIds);
            return s;
        }
    }

    /** A logged-in account: the wallet values the game loaded, which it changes in memory and saves back whole. */
    public final class Session {
        public final long account;
        public long nx;
        public final Map<Long, Long> meso = new HashMap<>();

        Session(long account) {
            this.account = account;
        }

        /** Spends mesos like the game does: only what the session holds. */
        public boolean spendMeso(long character, long amount) {
            long have = meso.getOrDefault(character, 0L);
            if (amount > have) {
                return false;
            }
            meso.put(character, have - amount);
            return true;
        }
    }

    private State state = new State();
    private final Set<Long> online = new HashSet<>();
    private final Map<Long, Session> sessions = new HashMap<>();
    private int crashAfter = -1;
    private long ledgerSeq;

    public synchronized void addAccount(long account, long nxCredit) {
        state.nx.put(account, nxCredit);
    }

    public synchronized void addCharacter(long character, long account, long meso) {
        state.charAccount.put(character, account);
        state.meso.put(character, meso);
    }

    public synchronized long nx(long account) {
        return state.nx.getOrDefault(account, 0L);
    }

    public synchronized long meso(long character) {
        return state.meso.getOrDefault(character, 0L);
    }

    public synchronized long balance(Bucket b) {
        return state.balances.getOrDefault(b, 0L);
    }

    public synchronized Map<Bucket, Long> balances() {
        return new HashMap<>(state.balances);
    }

    public synchronized List<Ledger.Row> ledger() {
        return new ArrayList<>(state.ledger);
    }

    public synchronized List<FillRow> fills() {
        return new ArrayList<>(state.fills);
    }

    public synchronized OrderRow order(long id) {
        return state.orders.get(id);
    }

    /** Every NX in wallets and exchange buckets (players' and the house's). */
    public synchronized long totalNx() {
        return total(Asset.NX);
    }

    public synchronized long totalMeso() {
        return total(Asset.MESO);
    }

    private long total(Asset a) {
        long t = 0;
        for (long v : (a == Asset.NX ? state.nx : state.meso).values()) {
            t += v;
        }
        for (Session s : sessions.values()) {
            // a session's copy replaces the stored value while logged in
            if (a == Asset.NX) {
                t += s.nx - state.nx.getOrDefault(s.account, 0L);
            } else {
                for (var e : s.meso.entrySet()) {
                    t += e.getValue() - state.meso.getOrDefault(e.getKey(), 0L);
                }
            }
        }
        for (var e : state.balances.entrySet()) {
            if (e.getKey().asset() == a) {
                t += e.getValue();
            }
        }
        return t;
    }

    public synchronized boolean online(long account) {
        return online.contains(account);
    }

    public synchronized Session login(long account) {
        online.add(account);
        Session s = new Session(account);
        s.nx = state.nx.getOrDefault(account, 0L);
        for (var e : state.charAccount.entrySet()) {
            if (e.getValue() == account) {
                s.meso.put(e.getKey(), state.meso.getOrDefault(e.getKey(), 0L));
            }
        }
        sessions.put(account, s);
        return s;
    }

    /** Saves the session's values back (absolute, as the game's character save does), then marks the account offline. */
    public synchronized void logout(long account) {
        Session s = sessions.remove(account);
        if (s != null) {
            state.nx.put(account, s.nx);
            state.meso.putAll(s.meso);
        }
        online.remove(account);
    }

    /** The next write fails (as a crash would) after this many steps; -1 = never. One-shot. */
    public synchronized void crashAfterSteps(int steps) {
        crashAfter = steps;
    }

    private int steps;

    private void step() throws StoreException {
        if (crashAfter >= 0 && steps++ >= crashAfter) {
            crashAfter = -1;
            throw new StoreException(Failure.ERROR, "simulated crash");
        }
    }

    @Override
    public synchronized Loaded load(int recentFills) {
        List<OrderRow> open = new ArrayList<>();
        long maxOrder = 0, maxSeq = 0;
        for (OrderRow o : state.orders.values()) {
            maxOrder = Math.max(maxOrder, o.id());
            maxSeq = Math.max(maxSeq, o.seq());
            if (o.open()) {
                open.add(o);
            }
        }
        long maxFill = 0;
        for (FillRow f : state.fills) {
            maxFill = Math.max(maxFill, f.id());
        }
        long maxBatch = 0;
        List<Posting> ps = new ArrayList<>();
        for (Ledger.Row r : state.ledger) {
            maxBatch = Math.max(maxBatch, r.batch());
            ps.add(r.posting());
        }
        List<FillRow> recent = new ArrayList<>(state.fills);
        recent.sort(Comparator.comparingLong(FillRow::id));
        if (recent.size() > recentFills) {
            recent = recent.subList(recent.size() - recentFills, recent.size());
        }
        Map<Bucket, Long> bal = new HashMap<>(state.balances);
        bal.values().removeIf(v -> v == 0);
        return new Loaded(open, bal, Ledger.balances(ps), new ArrayList<>(recent), maxOrder, maxFill, maxSeq, maxBatch);
    }

    @Override
    public synchronized void apply(Batch batch) throws StoreException {
        steps = 0;
        State s = state.copy();
        for (Posting p : batch.postings()) {
            post(s, p);
            s.ledger.add(new Ledger.Row(++ledgerSeq, batch.at(), batch.id(), p));
            step();
        }
        for (OrderRow o : batch.orders()) {
            OrderRow old = s.orders.get(o.id());
            if (old == null && o.requestId() != null) {
                Long other = s.requests.get(o.requestId());
                if (other != null && other != o.id()) {
                    throw new StoreException(Failure.DUPLICATE, "request " + o.requestId());
                }
                s.requests.put(o.requestId(), o.id());
            }
            s.orders.put(o.id(), o);
            step();
        }
        for (FillRow f : batch.fills()) {
            if (!s.fillIds.add(f.id())) {
                throw new StoreException(Failure.ERROR, "duplicate fill " + f.id());
            }
            s.fills.add(f);
            step();
        }
        state = s;
    }

    private void post(State s, Posting p) throws StoreException {
        debit(s, p.from(), p.amount(), p.account());
        credit(s, p.to(), p.amount(), p.account());
    }

    private void walletGuard(State s, Bucket b, long account) throws StoreException {
        if (online.contains(account)) {
            throw new StoreException(Failure.ONLINE, "account " + account);
        }
        if (b.asset() == Asset.NX ? b.id() != account || !s.nx.containsKey(account)
                : !Long.valueOf(account).equals(s.charAccount.get(b.id()))) {
            throw new StoreException(Failure.NOT_OWNER, b + " is not account " + account);
        }
    }

    private void debit(State s, Bucket b, long amount, long account) throws StoreException {
        if (b.kind() == Kind.WALLET) {
            walletGuard(s, b, account);
            Map<Long, Long> w = b.asset() == Asset.NX ? s.nx : s.meso;
            long have = w.getOrDefault(b.id(), 0L);
            if (have < amount) {
                throw new StoreException(Failure.INSUFFICIENT, b + " has " + have + ", needs " + amount);
            }
            w.put(b.id(), have - amount);
            return;
        }
        long have = s.balances.getOrDefault(b, 0L);
        if (have < amount) {
            throw new StoreException(Failure.INSUFFICIENT, b + " has " + have + ", needs " + amount);
        }
        if (have == amount) {
            s.balances.remove(b);
        } else {
            s.balances.put(b, have - amount);
        }
    }

    private void credit(State s, Bucket b, long amount, long account) throws StoreException {
        if (b.kind() == Kind.WALLET) {
            walletGuard(s, b, account);
            Map<Long, Long> w = b.asset() == Asset.NX ? s.nx : s.meso;
            long have = w.getOrDefault(b.id(), 0L);
            if (have + amount > Integer.MAX_VALUE) {
                throw new StoreException(Failure.ERROR, b + " would pass the wallet cap");
            }
            w.put(b.id(), have + amount);
            return;
        }
        s.balances.merge(b, amount, Long::sum);
    }

    @Override
    public synchronized List<Posting> collect(long batchId, long at, long account) throws StoreException {
        if (online.contains(account)) {
            throw new StoreException(Failure.ONLINE, "account " + account);
        }
        List<Posting> out = new ArrayList<>();
        State s = state.copy();
        Bucket nxPay = Bucket.payout(Asset.NX, account);
        long nxWaiting = s.balances.getOrDefault(nxPay, 0L);
        if (nxWaiting > 0 && s.nx.containsKey(account)) {
            long room = Integer.MAX_VALUE - s.nx.get(account);
            long move = Math.min(nxWaiting, room);
            if (move > 0) {
                out.add(new Posting(Ledger.Why.COLLECT, nxPay, Bucket.wallet(Asset.NX, account), move, account, 0, 0));
            }
        }
        for (long ch : new TreeSet<>(s.charAccount.keySet())) {
            if (s.charAccount.get(ch) != account) {
                continue;
            }
            Bucket pay = Bucket.payout(Asset.MESO, ch);
            long waiting = s.balances.getOrDefault(pay, 0L);
            long move = Math.min(waiting, Integer.MAX_VALUE - s.meso.getOrDefault(ch, 0L));
            if (move > 0) {
                out.add(new Posting(Ledger.Why.COLLECT, pay, Bucket.wallet(Asset.MESO, ch), move, account, 0, 0));
            }
        }
        steps = 0;
        for (Posting p : out) {
            post(s, p);
            s.ledger.add(new Ledger.Row(++ledgerSeq, at, batchId, p));
            step();
        }
        state = s;
        return out;
    }

    @Override
    public synchronized List<Long> accountsWithPayout() {
        Set<Long> out = new TreeSet<>();
        for (var e : state.balances.entrySet()) {
            Bucket b = e.getKey();
            if (b.kind() != Kind.PAYOUT || e.getValue() <= 0) {
                continue;
            }
            Long acc = b.asset() == Asset.NX ? Long.valueOf(b.id()) : state.charAccount.get(b.id());
            if (acc != null) {
                out.add(acc);
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public synchronized OrderRow byRequest(String requestId) {
        Long id = state.requests.get(requestId);
        return id == null ? null : state.orders.get(id);
    }

    @Override
    public synchronized List<OrderRow> ordersOf(long account, int limit) {
        List<OrderRow> out = new ArrayList<>();
        for (OrderRow o : state.orders.values()) {
            if (o.account() == account && o.regular() == 0) {
                out.add(o);
            }
        }
        out.sort(Comparator.comparingLong(OrderRow::id).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Test hook: corrupts a stored balance without a ledger row, as a bad manual edit would. */
    public synchronized void tamper(Bucket b, long amount) {
        state.balances.put(b, amount);
    }
}
