package soloMapling.site.auth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An in-memory {@link AuthStore} with the same rules as the JDBC one (names compare ignoring case, as MySQL does). */
public class MemoryAuthStore implements AuthStore {

    public final Map<String, InviteCodes.Invite> invites = new HashMap<>();
    final Map<String, Account> accounts = new HashMap<>();
    final Map<Integer, Profile> profiles = new HashMap<>();
    /** account id to its highest character level. */
    public final Map<Integer, Integer> levels = new HashMap<>();
    /** new account id to {inviting account, inviting character}. */
    public final Map<Integer, int[]> invitedBy = new HashMap<>();
    int nextId = 100;
    int upgrades;

    @Override
    public synchronized void insertInvite(InviteCodes.Invite invite) {
        invites.put(invite.code(), invite);
    }

    @Override
    public synchronized int countSelfServeInvitesSince(int accountId, long since) {
        return (int) invites.values().stream()
                .filter(i -> i.createdByAccount() == accountId && i.selfServe() && i.createdAt() > since).count();
    }

    @Override
    public synchronized int highestCharacterLevel(int accountId) {
        return levels.getOrDefault(accountId, 0);
    }

    @Override
    public synchronized List<InviteCodes.Invite> invitesMadeBy(int accountId, int limit) {
        List<InviteCodes.Invite> all = new ArrayList<>(invites.values().stream()
                .filter(i -> i.createdByAccount() == accountId).toList());
        all.sort(Comparator.comparingLong(InviteCodes.Invite::createdAt).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public synchronized InviteCodes.Invite findInvite(String code) {
        return invites.get(code);
    }

    @Override
    public synchronized List<InviteCodes.Invite> recentInvites(int limit) {
        List<InviteCodes.Invite> all = new ArrayList<>(invites.values());
        all.sort(Comparator.comparingLong(InviteCodes.Invite::createdAt).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public synchronized boolean revokeInvite(String code, String by, long now) {
        InviteCodes.Invite i = invites.get(code);
        if (i == null || i.usedAt() > 0 || i.revokedAt() > 0) {
            return false;
        }
        invites.put(code, new InviteCodes.Invite(i.code(), i.createdAt(), i.createdByAccount(), i.createdByName(),
                i.note(), i.expiresAt(), 0, "", 0, now, by, i.createdByChar(), i.selfServe()));
        return true;
    }

    @Override
    public synchronized boolean accountNameTaken(String name) {
        return accounts.containsKey(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public synchronized int createAccountWithInvite(String code, String name, String passhash, String ipHash, long now) {
        InviteCodes.Invite i = invites.get(code);
        if (i == null || i.status(now) != InviteCodes.Status.OPEN) {
            return CODE_GONE;
        }
        if (accountNameTaken(name)) {
            return NAME_TAKEN;
        }
        int id = nextId++;
        accounts.put(name.toLowerCase(Locale.ROOT), new Account(id, name, passhash, false, 0));
        invites.put(code, new InviteCodes.Invite(i.code(), i.createdAt(), i.createdByAccount(), i.createdByName(),
                i.note(), i.expiresAt(), id, name, now, 0, "", i.createdByChar(), i.selfServe()));
        invitedBy.put(id, new int[]{i.createdByAccount(), i.createdByChar()});
        return id;
    }

    @Override
    public synchronized Account findAccount(String name) {
        return accounts.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public synchronized void upgradeHash(int accountId, String passhash) {
        upgrades++;
        accounts.replaceAll((k, a) -> a.id() == accountId
                ? new Account(a.id(), a.name(), passhash, a.banned(), a.tempbanUntil()) : a);
    }

    @Override
    public synchronized Profile profile(int accountId) {
        return profiles.getOrDefault(accountId, new Profile(0, List.of()));
    }

    synchronized void put(Account a) {
        accounts.put(a.name().toLowerCase(Locale.ROOT), a);
    }
}
