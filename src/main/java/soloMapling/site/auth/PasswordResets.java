package soloMapling.site.auth;

import client.AccountPasswords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Ticket 107: forgotten passwords, reset after a GM approves the request (owner 2026-10-26: no email, no codes passed
 * around). The site sends {@code /reset/request}; online GMs (level 3+) get a notice and approve or deny it with
 * {@code !resetpw}; the browser that asked (it holds the request's random secret) then polls {@code /reset/status} and
 * sets a new password with {@code /reset/complete}.
 * <p>
 * Names can't be probed: every name gets the same answers over time. For an unknown, banned, bot, template or revoked
 * account {@code /reset/request} stores a decoy (account id 0, keyed by a salted HMAC of the name, never the name): it
 * reads pending until it expires after 24 hours, {@code /reset/complete} says NOT_APPROVED and then EXPIRED, and a new
 * request for the same name replaces it, all as for a real account nobody has decided on yet. Decoys send no GM notice,
 * never show in {@code !resetpw}, can't be approved or denied and aren't counted on the dashboard. Recording the request
 * and telling the GMs happen off the caller's thread, so both answers take about as long. The secret is stored only as its SHA-256;
 * a wrong secret looks exactly like an expired request. A request lives 24 hours; an approval 2 hours (and never past
 * the 24). One open request per account: a new one replaces the old. Never logs a password, a secret or a typed name
 * that is not an account.
 */
public final class PasswordResets {

    private static final Logger log = LoggerFactory.getLogger(PasswordResets.class);

    /** {@code /reset/complete}: the request is still pending, or a GM denied it. */
    public static final String NOT_APPROVED = "NOT_APPROVED";
    /** {@code /reset/complete}: the request already set a password. */
    public static final String USED = "USED";
    /** {@code /reset/complete}: too old, replaced, the approval ran out, a wrong secret, or the account is banned since. */
    public static final String EXPIRED = "EXPIRED";

    static final long REQUEST_TTL_MS = TimeUnit.HOURS.toMillis(24);
    static final long APPROVAL_TTL_MS = TimeUnit.HOURS.toMillis(2);
    static final int CONTACT_MAX = 64;
    static final int SECRET_BYTES = 32;
    static final int MIN_GM_LEVEL = 3;
    static final int LIST_LIMIT = 15;

    static final int REQUESTS_PER_IP_PER_HOUR = 5;
    static final int REQUESTS_PER_HOUR = 30;
    static final int STATUS_PER_IP_PER_MINUTE = 30;
    static final int STATUS_PER_MINUTE = 300;
    static final int COMPLETES_PER_IP_PER_HOUR = 10;
    static final int COMPLETES_PER_HOUR = 60;
    private static final int MAX_TRACKED = 20_000;

    private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What {@code /reset/status} answers (lower case on the wire). */
    public enum State {
        PENDING, APPROVED, DENIED, EXPIRED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code /reset/request}'s answer: on ok, a request id and the secret the browser keeps; else error (and rule). */
    public record Ticket(boolean ok, String requestId, String secret, String error, String rule) {
        static Ticket fail(String error, String rule) {
            return new Ticket(false, null, null, error, rule);
        }
    }

    private final ResetStore resets;
    private final AuthStore accounts;
    private final SiteAccounts site;
    private final LongSupplier clock;
    private final Executor recorder;
    private final Consumer<String> gmNotice;
    private final byte[] decoySalt;
    private final SiteAccounts.Window requests = new SiteAccounts.Window();
    private final SiteAccounts.Window statuses = new SiteAccounts.Window();
    private final SiteAccounts.Window completes = new SiteAccounts.Window();
    private final Map<String, SiteAccounts.Window> requestsByIp = new ConcurrentHashMap<>();
    private final Map<String, SiteAccounts.Window> statusesByIp = new ConcurrentHashMap<>();
    private final Map<String, SiteAccounts.Window> completesByIp = new ConcurrentHashMap<>();

    /**
     * @param site     its {@link SiteAccounts#refused} decides which accounts can be reset (the same as sign-in)
     * @param recorder runs the store write and the GM notice for a real request (a background thread in the game)
     * @param gmNotice tells the online GMs of level 3+
     */
    public PasswordResets(ResetStore resets, AuthStore accounts, SiteAccounts site, LongSupplier clock, Executor recorder,
                          Consumer<String> gmNotice) {
        this(resets, accounts, site, clock, recorder, gmNotice, null);
    }

    /** {@code decoySalt}: keys decoy rows by name (the auth API's secret in the game); null picks a random one. */
    public PasswordResets(ResetStore resets, AuthStore accounts, SiteAccounts site, LongSupplier clock, Executor recorder,
                          Consumer<String> gmNotice, byte[] decoySalt) {
        if (decoySalt == null || decoySalt.length == 0) {
            decoySalt = new byte[32];
            RANDOM.nextBytes(decoySalt);
        }
        this.decoySalt = decoySalt.clone();
        this.resets = resets;
        this.accounts = accounts;
        this.site = site;
        this.clock = clock;
        this.recorder = recorder;
        this.gmNotice = gmNotice;
    }

    /** The game's: the database, one daemon thread for recording, and a yellow notice to online GMs of level 3+. */
    public static PasswordResets forGame(SiteAccounts site, byte[] decoySalt) {
        AtomicInteger n = new AtomicInteger();
        Executor recorder = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "site-auth-reset-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        return new PasswordResets(new ResetStore.Jdbc(), new AuthStore.Jdbc(), site, System::currentTimeMillis,
                recorder, PasswordResets::tellGms, decoySalt);
    }

    private static void tellGms(String message) {
        try {
            net.packet.Packet p = tools.PacketCreator.sendYellowTip(message);
            for (net.server.channel.Channel ch : net.server.Server.getInstance().getAllChannels()) {
                var players = ch.getPlayerStorage();
                if (players == null) {
                    continue;
                }
                for (client.Character chr : players.getAllCharacters()) {
                    if (chr.gmLevel() >= MIN_GM_LEVEL) {
                        chr.sendPacket(p);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("Password reset GM notice not sent", e);
        }
    }

    // ── the site's side ─────────────────────────────────────────────────────────────────────────────

    /** {@code POST /reset/request}. The same answer for every name; only input that names nothing can fail. */
    public Ticket request(String user, String typedContact, String ipHash) {
        long now = clock.getAsLong();
        String ip = SiteAccounts.ipKey(ipHash);
        if (user == null || user.isBlank() || user.length() > SiteAccounts.VERIFY_MAX) {
            return Ticket.fail(SiteAccounts.BAD_INPUT, "user_length");
        }
        String contact = cleanContact(typedContact);
        if (contact == null) {
            return Ticket.fail(SiteAccounts.BAD_INPUT, "contact_length");
        }
        if (ContentGuard.problem(contact) != null) {
            return Ticket.fail(SiteAccounts.BAD_INPUT, "contact_words");
        }
        if (!allow(requests, REQUESTS_PER_HOUR, requestsByIp, REQUESTS_PER_IP_PER_HOUR, ip, now, TimeUnit.HOURS.toMillis(1))) {
            return Ticket.fail(SiteAccounts.RATE_LIMITED, null);
        }
        String id = newRequestId();
        String secret = newSecret();
        AuthStore.Account account;
        try {
            account = accounts.findAccount(user.trim());
        } catch (Exception e) {
            log.warn("Password reset request failed on the server side", e);
            return Ticket.fail(SiteAccounts.UNAVAILABLE, null);
        }
        // The refusal check runs for unknown names too (on a stand-in), so both paths do the same work.
        boolean real = !site.refused(account == null ? NO_ACCOUNT : account, now) && account != null;
        String decoyKey = decoyKey(user.trim());
        ResetStore.Request r = real
                ? ResetStore.Request.fresh(id, account.id(), account.name(), hash(secret), contact, ip, now)
                : ResetStore.Request.fresh(id, 0, decoyKey, hash(secret), "", "", now);
        recorder.execute(() -> record(r));
        return new Ticket(true, id, secret, null, null);
    }

    /** What an unknown name is checked as, so its answer takes as long as a real account's. */
    private static final AuthStore.Account NO_ACCOUNT = new AuthStore.Account(0, "", "", false, 0);

    /**
     * A decoy's key: {@code ~} and the first 40 hex characters of HMAC-SHA256(salt, lower-cased name). It is never a
     * real account name (those are letters and digits), lets a second request for the same unknown name replace the
     * first exactly as a real account's does, and does not keep the typed name (which may be a mistyped password).
     */
    String decoyKey(String name) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(decoySalt, "HmacSHA256"));
            byte[] h = mac.doFinal(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "~" + HexFormat.of().formatHex(h).substring(0, 40);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether a stored row is a decoy (an unknown, banned, bot or template account's request). */
    static boolean decoy(ResetStore.Request r) {
        return r.accountId() <= 0;
    }

    private void record(ResetStore.Request r) {
        try {
            resets.insertReplacing(r);
            if (decoy(r)) {
                return;   // no notice, no log line: a decoy says nothing about the name
            }
            int gm = 0;
            try {
                gm = accounts.profile(r.accountId()).gmLevel();
            } catch (Exception e) {
                // the notice just leaves the GM mark out
            }
            log.info("Password reset requested for account '{}' (id {}), request {}, from ipHash {}", r.accountName(),
                    r.accountId(), shortId(r.id()), ipPrefix(r.ipHash()));
            gmNotice.accept("[Password reset] " + r.accountName() + (gm > 0 ? " (GM level " + gm + " account!)" : "")
                    + " asks for a new password, contact: " + r.contact() + ". !resetpw list, then approve or deny.");
        } catch (Exception e) {
            log.warn("Password reset request not stored (is migration 037 applied?)", e);
        }
    }

    /** {@code POST /reset/status}. Unknown ids and wrong secrets are {@link State#EXPIRED}. */
    public State status(String requestId, String secret, String ipHash) {
        long now = clock.getAsLong();
        if (!allow(statuses, STATUS_PER_MINUTE, statusesByIp, STATUS_PER_IP_PER_MINUTE, SiteAccounts.ipKey(ipHash), now,
                TimeUnit.MINUTES.toMillis(1))) {
            return null;   // rate limited
        }
        try {
            ResetStore.Request r = owned(requestId, secret);
            return r == null ? State.EXPIRED : stateOf(r, now);
        } catch (Exception e) {
            log.warn("Password reset status failed on the server side", e);
            return State.EXPIRED;
        }
    }

    /**
     * {@code POST /reset/complete}: sets the new password (the sign-up rules and hashing) when the request is approved
     * and still fresh, marks it used, and answers as {@code /verify} does so the site can sign the player in.
     */
    public SiteAccounts.Result complete(String requestId, String secret, String pass, String ipHash) {
        long now = clock.getAsLong();
        String ip = SiteAccounts.ipKey(ipHash);
        if (!allow(completes, COMPLETES_PER_HOUR, completesByIp, COMPLETES_PER_IP_PER_HOUR, ip, now, TimeUnit.HOURS.toMillis(1))) {
            return SiteAccounts.Result.fail(SiteAccounts.RATE_LIMITED);
        }
        String rule = SiteAccounts.passRule(pass);
        if (rule != null) {
            return SiteAccounts.Result.badInput(rule);
        }
        try {
            ResetStore.Request r = owned(requestId, secret);
            if (r == null) {
                return SiteAccounts.Result.fail(EXPIRED);   // unknown id or a wrong secret
            }
            if (ResetStore.USED.equals(r.state())) {
                return SiteAccounts.Result.fail(USED);
            }
            State state = stateOf(r, now);
            if (state != State.APPROVED) {
                return SiteAccounts.Result.fail(state == State.EXPIRED ? EXPIRED : NOT_APPROVED);
            }
            AuthStore.Account account = accounts.findAccount(r.accountName());
            if (account == null || account.id() != r.accountId() || site.refused(account, now)) {
                return SiteAccounts.Result.fail(EXPIRED);   // banned since, or renamed
            }
            String passhash = AccountPasswords.hashForNewAccount(pass);
            if (!resets.complete(r.id(), r.accountId(), passhash, ip, now, now - REQUEST_TTL_MS, now - APPROVAL_TTL_MS)) {
                ResetStore.Request after = resets.find(r.id());   // used (or denied) a moment ago
                return SiteAccounts.Result.fail(after != null && ResetStore.USED.equals(after.state()) ? USED : EXPIRED);
            }
            log.info("Password reset done for account '{}' (id {}), request {}, approved by {}", account.name(),
                    account.id(), shortId(r.id()), r.decidedBy());
            AuthStore.Profile profile = accounts.profile(account.id());
            return new SiteAccounts.Result(true, account.id(), account.name(), profile.gmLevel(), profile.characters(),
                    null, null);
        } catch (Exception e) {
            log.warn("Password reset failed on the server side", e);
            return SiteAccounts.Result.fail(SiteAccounts.UNAVAILABLE);
        }
    }

    /** The request when the id is well formed, exists and the secret is its secret; else null. */
    private ResetStore.Request owned(String requestId, String secret) throws Exception {
        if (requestId == null || secret == null || secret.isEmpty() || secret.length() > 128
                || !REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        ResetStore.Request r = resets.find(requestId);
        if (r == null) {
            return null;
        }
        byte[] want = r.secretHash() == null ? new byte[0] : r.secretHash().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(want, hash(secret).getBytes(StandardCharsets.US_ASCII)) ? r : null;   // constant time
    }

    /** A request's state as the site sees it. Pure. */
    static State stateOf(ResetStore.Request r, long now) {
        boolean fresh = now - r.createdAt() < REQUEST_TTL_MS;
        return switch (r.state()) {
            case ResetStore.PENDING -> fresh ? State.PENDING : State.EXPIRED;
            case ResetStore.APPROVED -> fresh && now - r.decidedAt() < APPROVAL_TTL_MS ? State.APPROVED : State.EXPIRED;
            case ResetStore.DENIED -> State.DENIED;
            default -> State.EXPIRED;   // used, replaced
        };
    }

    /** A stored request's state for the dashboard: pending, approved, denied, expired, used or replaced. Pure. */
    public static String dashboardState(String state, long createdAt, long decidedAt, long now) {
        if (ResetStore.USED.equals(state) || ResetStore.REPLACED.equals(state)) {
            return state;
        }
        return stateOf(new ResetStore.Request("", 0, "", "", "", "", createdAt, state, decidedAt, "", 0), now).wire();
    }

    // ── the GMs' side (!resetpw)────────────────────────────────────────────────────────────────────

    /** {@code !resetpw list}: the open (pending, unexpired) requests, oldest first. */
    public static List<String> list(ResetStore store, long now) throws Exception {
        List<String> out = new ArrayList<>();
        List<ResetStore.Request> open = store.withState(ResetStore.PENDING, now - REQUEST_TTL_MS);
        if (open.isEmpty()) {
            out.add("No pending password reset requests.");
        }
        for (ResetStore.Request r : open.subList(0, Math.min(LIST_LIMIT, open.size()))) {
            out.add(describe(r, now));
        }
        if (open.size() > LIST_LIMIT) {
            out.add("... and " + (open.size() - LIST_LIMIT) + " more.");
        }
        return out;
    }

    /** One line of {@code !resetpw list}. */
    static String describe(ResetStore.Request r, long now) {
        long left = Math.max(0, r.createdAt() + REQUEST_TTL_MS - now);
        return r.accountName() + "  " + InviteCodes.pacific(r.createdAt()) + "  contact: " + r.contact() + "  ip "
                + ipPrefix(r.ipHash()) + "  (expires in " + TimeUnit.MILLISECONDS.toHours(left) + "h)";
    }

    /** {@code !resetpw approve <account>}: the account's newest pending request becomes approved for 2 hours. */
    public static String approve(ResetStore store, String account, String by, long now) throws Exception {
        if (account == null || account.isBlank() || account.length() > SiteAccounts.VERIFY_MAX) {
            return "Syntax: !resetpw approve <account>";
        }
        ResetStore.Request r = store.newestFor(account, now - REQUEST_TTL_MS, ResetStore.PENDING);
        if (r == null || !store.decide(r.id(), ResetStore.PENDING, ResetStore.APPROVED, by, now)) {
            return "No pending reset request for " + account + ". (!resetpw list)";
        }
        log.info("Password reset for account '{}' (id {}), request {}, approved by {}", r.accountName(), r.accountId(),
                shortId(r.id()), by);
        return "Approved the reset for " + r.accountName() + ". They have 2 hours to set a new password on the site,"
                + " in the browser they asked from. Reach them at: " + r.contact();
    }

    /** {@code !resetpw deny <account>}: the account's newest pending or approved (unused) request is denied. */
    public static String deny(ResetStore store, String account, String by, long now) throws Exception {
        if (account == null || account.isBlank() || account.length() > SiteAccounts.VERIFY_MAX) {
            return "Syntax: !resetpw deny <account>";
        }
        ResetStore.Request r = store.newestFor(account, now - REQUEST_TTL_MS, ResetStore.PENDING, ResetStore.APPROVED);
        if (r == null || !store.decide(r.id(), r.state(), ResetStore.DENIED, by, now)) {
            return "No open reset request for " + account + ". (!resetpw list)";
        }
        log.info("Password reset for account '{}' (id {}), request {}, denied by {}", r.accountName(), r.accountId(),
                shortId(r.id()), by);
        return "Denied the reset for " + r.accountName() + ".";
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * What the player typed to be reached at, made plain: control characters become spaces, characters outside the
     * basic plane (emoji) are dropped, spaces folded. Null when empty or longer than {@link #CONTACT_MAX}. Pure.
     */
    static String cleanContact(String typed) {
        if (typed == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        typed.codePoints().filter(cp -> cp < 0x10000)
                .forEach(cp -> sb.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.isEmpty() || s.length() > CONTACT_MAX ? null : s;
    }

    private static boolean allow(SiteAccounts.Window global, int globalMax, Map<String, SiteAccounts.Window> perIp,
                                 int perIpMax, String ip, long now, long window) {
        if (perIp.size() > MAX_TRACKED) {
            perIp.clear();   // a flood of distinct ipHashes: forget them rather than grow (the global cap still holds)
        }
        SiteAccounts.Window w = perIp.computeIfAbsent(ip, k -> new SiteAccounts.Window());
        synchronized (global) {
            if (global.count(now, window) >= globalMax || w.count(now, window) >= perIpMax) {
                return false;
            }
            global.add(now);
            w.add(now);
            return true;
        }
    }

    /** 16 random bytes as 32 hex characters. */
    static String newRequestId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 32 random bytes, base64url without padding (43 characters). */
    static String newSecret() {
        byte[] b = new byte[SECRET_BYTES];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** SHA-256 of the secret, hex: what the table keeps. The secret is 256 random bits, so no salt or stretching is needed. */
    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }

    static String ipPrefix(String ipHash) {
        return ipHash == null ? "" : ipHash.substring(0, Math.min(6, ipHash.length()));
    }
}
