package soloMapling.site.auth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An in-memory {@link ResetStore} with the same rules as the JDBC one (account names compare ignoring case). */
public class MemoryResetStore implements ResetStore {

    public final Map<String, Request> rows = new LinkedHashMap<>();
    /** Account id to the password hash {@link #complete} set. */
    public final Map<Integer, String> passwords = new LinkedHashMap<>();

    @Override
    public synchronized void insertReplacing(Request r) {
        rows.replaceAll((id, old) -> old.accountId() == r.accountId() && old.accountName().equals(r.accountName()) && (PENDING.equals(old.state()) || APPROVED.equals(old.state()))
                ? with(old, REPLACED, old.decidedAt(), old.decidedBy(), old.usedAt()) : old);
        rows.put(r.id(), r);
    }

    @Override
    public synchronized Request find(String id) {
        return rows.get(id);
    }

    @Override
    public synchronized Request newestFor(String accountName, long since, String... states) {
        List<String> want = Arrays.asList(states);
        return rows.values().stream()
                .filter(r -> r.accountId() > 0 && r.accountName().equalsIgnoreCase(accountName) && r.createdAt() > since && want.contains(r.state()))
                .max(Comparator.comparingLong(Request::createdAt)).orElse(null);
    }

    @Override
    public synchronized List<Request> withState(String state, long since) {
        return rows.values().stream().filter(r -> r.accountId() > 0 && r.state().equals(state) && r.createdAt() > since)
                .sorted(Comparator.comparingLong(Request::createdAt)).toList();
    }

    @Override
    public synchronized List<Request> recent(int limit) {
        List<Request> all = new ArrayList<>(rows.values().stream().filter(r -> r.accountId() > 0).toList());
        all.sort(Comparator.comparingLong(Request::createdAt).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public synchronized boolean decide(String id, String from, String to, String by, long now) {
        Request r = rows.get(id);
        if (r == null || r.accountId() <= 0 || !r.state().equals(from)) {
            return false;
        }
        rows.put(id, with(r, to, now, by, 0));
        return true;
    }

    @Override
    public synchronized boolean complete(String id, int accountId, String passhash, String ipHash, long now,
                                         long createdAfter, long decidedAfter) {
        Request r = rows.get(id);
        if (r == null || r.accountId() != accountId || !APPROVED.equals(r.state()) || r.createdAt() <= createdAfter
                || r.decidedAt() <= decidedAfter) {
            return false;
        }
        rows.put(id, with(r, USED, r.decidedAt(), r.decidedBy(), now));
        passwords.put(accountId, passhash);
        return true;
    }

    private static Request with(Request r, String state, long decidedAt, String decidedBy, long usedAt) {
        return new Request(r.id(), r.accountId(), r.accountName(), r.secretHash(), r.contact(), r.ipHash(), r.createdAt(),
                state, decidedAt, decidedBy, usedAt);
    }
}
