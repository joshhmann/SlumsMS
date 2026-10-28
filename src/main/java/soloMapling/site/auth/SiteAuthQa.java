package soloMapling.site.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.DatabaseConnection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Auto-QA t-au-1 (1.6.0, tickets 105 and 107): the site auth API's routes called in-process on the running instance,
 * after its secret check, so the check never reads, holds or sends the shared secret. The HTTP side is only probed
 * without the header (it must answer 401). Every call refuses unless auto-QA is on (a TEST-only switch), and the check
 * removes the throwaway invite, account and reset rows it made ({@link #cleanup}).
 */
public final class SiteAuthQa {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One answer: the HTTP status the route gives and its JSON body. */
    public record Answer(int status, Map<String, Object> body) {
        public boolean ok() {
            return Boolean.TRUE.equals(body.get("ok"));
        }

        public String error() {
            Object e = body.get("error");
            return e == null ? null : e.toString();
        }
    }

    private SiteAuthQa() {
    }

    private static void requireAutoQa() {
        if (!soloMapling.autoqa.AutoQa.isEnabled()) {
            throw new IllegalStateException("the in-process auth API calls are for auto-QA only");
        }
    }

    /** Null when the API is listening on loopback; else why it is not. */
    public static String notListening() {
        if (SiteAuthApi.runningApi() == null) {
            return "the site auth API is not listening (-Dslums.auth.port unset, or it did not start: see the boot log)";
        }
        return null;
    }

    public static int port() {
        return SiteAuthApi.runningPort();
    }

    /** POST {@code path} with {@code body} through the running API's routes (no secret involved). */
    public static Answer call(String path, Map<String, Object> body) throws Exception {
        requireAutoQa();
        SiteAuthApi api = SiteAuthApi.runningApi();
        if (api == null) {
            throw new IllegalStateException(notListening());
        }
        SiteAuthApi.Response r = api.route("POST", path, MAPPER.writeValueAsBytes(body));
        return new Answer(r.status(), r.body());
    }

    /** The HTTP status of a POST to {@code /verify} on loopback without the secret header (want 401); -1 on error. */
    public static int statusWithoutSecret() {
        int port = port();
        if (port <= 0) {
            return -1;
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/verify"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Waits (up to {@code maxMs}) until the reset request is stored (the API records it on a background thread). */
    public static boolean awaitReset(String requestId, long maxMs) throws Exception {
        requireAutoQa();
        ResetStore store = new ResetStore.Jdbc();
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            if (store.find(requestId) != null) {
                return true;
            }
            Thread.sleep(250);
        }
        return store.find(requestId) != null;
    }

    /** t-iv-1: removes the codes a throwaway account id made (and nothing else). Returns how many rows went. */
    public static int cleanupInvitesMadeBy(int accountId) throws SQLException {
        requireAutoQa();
        if (accountId >= 0) {
            throw new IllegalArgumentException("only a throwaway (negative) account id");
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM invite_codes WHERE created_by_account = ?")) {
            ps.setInt(1, accountId);
            return ps.executeUpdate();
        }
    }

    /**
     * Removes what the check made: its reset requests, the invite and the account (only one with no characters, by id
     * and name). Returns how many rows went.
     */
    public static int cleanup(int accountId, String accountName, String inviteCode) throws SQLException {
        requireAutoQa();
        int n = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            if (accountName != null) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM password_resets WHERE account_name = ?")) {
                    ps.setString(1, accountName);
                    n += ps.executeUpdate();
                }
            }
            String code = InviteCodes.normalize(inviteCode);
            if (code != null) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM invite_codes WHERE code = ?")) {
                    ps.setString(1, code);
                    n += ps.executeUpdate();
                }
            }
            if (accountId > 0 && accountName != null) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM accounts WHERE id = ? AND name = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM characters c WHERE c.accountid = ?)")) {
                    ps.setInt(1, accountId);
                    ps.setString(2, accountName);
                    ps.setInt(3, accountId);
                    n += ps.executeUpdate();
                }
            }
        }
        return n;
    }

    // ── t-iv-2: the site invite routes (ticket 105, "Site invite page") ─────────────────────────

    /** t-iv-2: a made-up account (negative id) the invite routes see as real: its name, GM level, best level, ban. */
    public record FakeAccount(int id, String name, int gmLevel, int level, boolean banned) {
    }

    /**
     * t-iv-2: POST {@code path} through the invite routes as the running server builds them (route parsing,
     * {@link SiteInvites}, the shared {@link InviteRules}, the real {@code invite_codes} table), except that accounts
     * with a negative id are looked up in {@code fakes} instead of the database; any other account id is unknown, so
     * the check can never act as a real account. No secret involved.
     */
    public static Answer callInvitesAs(List<FakeAccount> fakes, String path, Map<String, Object> body) throws Exception {
        requireAutoQa();
        for (FakeAccount f : fakes) {
            if (f.id() >= 0) {
                throw new IllegalArgumentException("only throwaway (negative) account ids");
            }
        }
        AuthStore store = new FakeAccountStore(new AuthStore.Jdbc(), fakes);
        SiteAccounts accounts = new SiteAccounts(store, System::currentTimeMillis, n -> false, id -> false);
        SiteAuthApi api = new SiteAuthApi(new byte[16], accounts, null,
                new SiteInvites(store, System::currentTimeMillis, accounts, true));
        SiteAuthApi.Response r = api.route("POST", path, MAPPER.writeValueAsBytes(body));
        return new Answer(r.status(), r.body());
    }

    /** The database for invite codes; made-up accounts for everything about an account. */
    static final class FakeAccountStore implements AuthStore {
        private final AuthStore db;
        private final List<FakeAccount> fakes;

        FakeAccountStore(AuthStore db, List<FakeAccount> fakes) {
            this.db = db;
            this.fakes = List.copyOf(fakes);
        }

        private FakeAccount fake(int id) {
            return fakes.stream().filter(f -> f.id() == id).findFirst().orElse(null);
        }

        @Override
        public Account findAccountById(int accountId) {
            FakeAccount f = fake(accountId);
            return f == null ? null : new Account(f.id(), f.name(), "-", f.banned(), 0);
        }

        @Override
        public Profile profile(int accountId) {
            FakeAccount f = fake(accountId);
            return new Profile(f == null ? 0 : f.gmLevel(), List.of());
        }

        @Override
        public int highestCharacterLevel(int accountId) {
            FakeAccount f = fake(accountId);
            return f == null ? 0 : f.level();
        }

        @Override
        public Account findAccount(String name) {
            return null;
        }

        @Override
        public void upgradeHash(int accountId, String passhash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean accountNameTaken(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int createAccountWithInvite(String code, String name, String passhash, String ipHash, long now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertInvite(InviteCodes.Invite invite) throws SQLException {
            db.insertInvite(invite);
        }

        @Override
        public InviteCodes.Invite findInvite(String code) throws SQLException {
            return db.findInvite(code);
        }

        @Override
        public int countSelfServeInvitesSince(int accountId, long since) throws SQLException {
            return db.countSelfServeInvitesSince(accountId, since);
        }

        @Override
        public List<InviteCodes.Invite> invitesMadeBy(int accountId, int limit) throws SQLException {
            return db.invitesMadeBy(accountId, limit);
        }

        @Override
        public List<InviteCodes.Invite> recentInvites(int limit) throws SQLException {
            return db.recentInvites(limit);
        }

        @Override
        public List<InviteCodes.Invite> invitePage(Integer madeByAccount, int limit, int offset) throws SQLException {
            return db.invitePage(madeByAccount, limit, offset);
        }

        @Override
        public int countInvites(Integer madeByAccount) throws SQLException {
            return db.countInvites(madeByAccount);
        }

        @Override
        public boolean revokeInvite(String code, String by, long now) throws SQLException {
            return db.revokeInvite(code, by, now);
        }
    }
}
