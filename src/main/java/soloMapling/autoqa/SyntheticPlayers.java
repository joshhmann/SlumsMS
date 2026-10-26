package soloMapling.autoqa;

import client.Character;
import client.Client;
import client.DefaultDates;
import client.Job;
import client.SkinColor;
import client.SyntheticClient;
import client.creator.CharacterFactoryRecipe;
import io.netty.buffer.Unpooled;
import net.PacketHandler;
import net.PacketProcessor;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.server.Server;
import net.server.coordinator.session.Hwid;
import net.server.coordinator.session.SessionCoordinator;
import soloMapling.ArtificialPlayer.BotNames;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-QA's synthetic players: real characters (normal database ids, far below the bot id base) on their own
 * accounts {@code autoqa1} / {@code autoqa2}, logged in through the real {@code PlayerLoggedinHandler} on a
 * {@link SyntheticClient}, inside the server process. Player 1 is a GM (level 3: it runs {@code !qa}); player 2 is a
 * plain player for the two-player checks. Both are level 70 Warriors in Henesys when first made.
 * <p>
 * Everything they do goes through the channel's packet handlers, exactly as a client's packets would.
 */
public final class SyntheticPlayers {

    public static final String[] NAMES = {"AutoQaOne", "AutoQaTwo"};
    /**
     * 1.6.0 (owner 2026-10-26): the social trace's observers, slots 3-6 on accounts {@code autoqa3..autoqa6}: plain
     * level-30 players that only stand in town so bot threads have a real player to happen in front of. They never
     * chat, trade or accept anything, and auto-QA's own logout leaves them alone ({@link #logoutAll}).
     */
    public static final String[] OBSERVER_NAMES = {"AutoQaThree", "AutoQaFour", "AutoQaFive", "AutoQaSix"};
    public static final int FIRST_OBSERVER_SLOT = NAMES.length + 1;
    public static final int GM_LEVEL = 3;
    static final int LEVEL = 70;
    static final int OBSERVER_LEVEL = 30;
    static final int CHANNEL = 1;
    static final int HENESYS = 100000000;

    private static final Map<Integer, SyntheticPlayer> online = new ConcurrentHashMap<>();

    private SyntheticPlayers() {
    }

    /** The synthetic player of this slot (1 or 2), logged in now if it is not. */
    public static synchronized SyntheticPlayer login(int slot) {
        return login(slot, CHANNEL);
    }

    /** The default name of a slot: 1-2 auto-QA's players, 3-6 the social trace's observers. */
    public static String nameOf(int slot) {
        if (slot >= 1 && slot <= NAMES.length) {
            return NAMES[slot - 1];
        }
        int o = slot - FIRST_OBSERVER_SLOT;
        if (o >= 0 && o < OBSERVER_NAMES.length) {
            return OBSERVER_NAMES[o];
        }
        throw new IllegalStateException("no synthetic player slot " + slot);
    }

    /** True for a social-trace observer slot (3-6). */
    public static boolean isObserverSlot(int slot) {
        return slot >= FIRST_OBSERVER_SLOT && slot < FIRST_OBSERVER_SLOT + OBSERVER_NAMES.length;
    }

    /** True for a character that is one of the social trace's observers (while online). */
    public static boolean isObserver(Character chr) {
        return chr != null && chr.getClient() instanceof SyntheticClient sc && isObserverSlot(sc.slot());
    }

    /**
     * The synthetic player of this slot, logged in on {@code channel} now if it is not (an online one stays on its
     * channel). 1.6.0: the observers spread over channels 1-3.
     */
    public static synchronized SyntheticPlayer login(int slot, int channel) {
        SyntheticPlayer p = online.get(slot);
        if (p != null && p.isOnline()) {
            return p;
        }
        nameOf(slot); // refuses an unknown slot
        int world = Server.getInstance().getHomeWorldId();
        try {
            int accountId = ensureAccount("autoqa" + slot);
            int charId = ensureCharacter(accountId, world, slot);
            SyntheticClient c = new SyntheticClient(slot, world, channel);
            c.setAccID(accountId);
            c.setAccountName("autoqa" + slot);
            // Normal login loads these views when sending the character list. Channel login skips that
            // screen here, but autosave and disconnect still update the same account-view cache.
            Server.getInstance().loadAccountCharacters(c);
            // the login server's part: the account row says "in transition", and the session cache knows our hwid
            c.updateLoginState(Client.LOGIN_SERVER_TRANSITION);
            c.setAccID(accountId);
            SessionCoordinator.getInstance().primeSyntheticLogin(c.getRemoteAddress(), new Hwid(hwidOf(slot)));
            ByteBufOutPacket in = new ByteBufOutPacket();
            in.writeInt(charId);
            handle(c, RecvOpcode.PLAYER_LOGGEDIN, in.getBytes());
            Character chr = c.getPlayer();
            if (chr == null || !c.isLoggedIn()) {
                throw new IllegalStateException("the login handler did not log " + nameOf(slot) + " in");
            }
            if (chr.getWorldChatOnOff()) {
                chr.toggleWorldChat(); // its map chat must stay map chat
            }
            p = new SyntheticPlayer(slot, c);
            online.put(slot, p);
            AutoQa.log.info("Auto-QA: synthetic player {} ({}) logged in on channel {}", chr.getName(), chr.getId(), channel);
            return p;
        } catch (SQLException e) {
            throw new IllegalStateException("database: " + e.getMessage());
        }
    }

    /**
     * Logs auto-QA's synthetic players out the way a closed client does. 1.6.0: the social trace's observers stay (an
     * auto-QA run ending mid-trace must not end the trace's watching); {@link #logoutObservers} logs those out.
     */
    public static synchronized void logoutAll() {
        for (var e : List.copyOf(online.entrySet())) {
            if (!isObserverSlot(e.getKey())) {
                e.getValue().logout();
                online.remove(e.getKey());
            }
        }
    }

    /** Logs the social trace's observers out. */
    public static synchronized void logoutObservers() {
        for (var e : List.copyOf(online.entrySet())) {
            if (isObserverSlot(e.getKey())) {
                e.getValue().logout();
                online.remove(e.getKey());
            }
        }
    }

    public static SyntheticPlayer get(int slot) {
        return online.get(slot);
    }

    /** True for a character id that is one of ours (while online). */
    public static boolean isSynthetic(Character chr) {
        return chr != null && chr.getClient() instanceof SyntheticClient;
    }

    /** Hands a packet body (no opcode) to the channel's handler for the opcode, as Client.channelRead does. */
    static void handle(SyntheticClient c, RecvOpcode op, byte[] body) {
        PacketHandler h = PacketProcessor.getChannelServerProcessor(c.getWorld(), c.getChannel()).getHandler((short) op.getValue());
        if (h == null) {
            throw new IllegalStateException("no handler for " + op);
        }
        if (!h.validateState(c)) {
            throw new IllegalStateException(op + " refused: not logged in");
        }
        noteReceived(c, op);
        h.handlePacket(new ByteBufInPacket(Unpooled.wrappedBuffer(body)), c);
    }

    /**
     * What {@code Client.channelRead} notes for every packet: the player's last deliberate action and last packet.
     * Without it the pretend player counted as "AFK over five minutes" five minutes into a run, and every bot
     * whisper, gift, invite and friendly act aimed at it was dropped (PlayerBusy) - rc6 auto-QA triage.
     */
    static void noteReceived(SyntheticClient c, RecvOpcode op) {
        c.notePlayerAction((short) op.getValue());
        c.updateLastPacket();
    }

    /** A fixed, valid-looking hwid per slot (8 hex digits). */
    static String hwidOf(int slot) {
        return String.format("A0A0%04X", slot);
    }

    private static int ensureAccount(String name) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT id FROM accounts WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
            // a random password nobody knows: the account is only ever used from inside the server
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts (name, password, birthday, tempban) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, UUID.randomUUID() + UUID.randomUUID().toString());
                ps.setDate(3, Date.valueOf(DefaultDates.getBirthday()));
                ps.setTimestamp(4, Timestamp.valueOf(DefaultDates.getTempban()));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    private static int ensureCharacter(int accountId, int world, int slot) throws SQLException {
        int gm = slot == 1 ? GM_LEVEL : 0;
        int level = isObserverSlot(slot) ? OBSERVER_LEVEL : LEVEL;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT id FROM characters WHERE accountid = ? AND world = ? ORDER BY id LIMIT 1")) {
                ps.setInt(1, accountId);
                ps.setInt(2, world);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        try (PreparedStatement up = con.prepareStatement("UPDATE characters SET gm = ? WHERE id = ?")) {
                            up.setInt(1, gm);
                            up.setInt(2, id);
                            up.executeUpdate();
                        }
                        return id;
                    }
                }
            }
        }
        String name = nameOf(slot);
        for (int i = 0; Character.getIdByName(name) >= 0 || !BotNames.reserveForPlayer(name); i++) {
            name = nameOf(slot).substring(0, 7) + (slot * 10 + i); // AutoQaO11, ... if a bot or someone holds it
        }
        SyntheticClient tmp = new SyntheticClient(slot, world, CHANNEL);
        tmp.setAccID(accountId);
        Character c = Character.getDefault(tmp);
        c.setWorld(world);
        c.setName(name);
        c.setGender(0);
        c.setSkinColor(SkinColor.NORMAL);
        c.setHair(30000);
        c.setFace(20000);
        c.setLevel(level);
        c.setJob(Job.WARRIOR);
        c.setMapId(HENESYS);
        c.setGMLevel(gm);
        CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(Job.WARRIOR, level, HENESYS, 0, 0, 0, 0);
        recipe.setStr(level == LEVEL ? 250 : 120);
        recipe.setDex(level == LEVEL ? 60 : 35);
        recipe.setMaxHp(level == LEVEL ? 4000 : 1500);
        recipe.setMaxMp(level == LEVEL ? 500 : 200);
        recipe.setMeso(level == LEVEL ? 1_000_000 : 50_000);
        if (!c.insertNewChar(recipe)) {
            throw new SQLException("could not insert the character " + name);
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement up = con.prepareStatement("UPDATE characters SET gm = ?, level = ? WHERE id = ?")) {
            up.setInt(1, gm);
            up.setInt(2, level);
            up.setInt(3, c.getId());
            up.executeUpdate();
        }
        AutoQa.log.info("Auto-QA: made synthetic player {} ({}) on account {}", name, c.getId(), accountId);
        return c.getId();
    }

    /**
     * rc6 inference checks: a synthetic player sends no movement packets, so without this it would stay wherever it
     * entered the map (a portal sits above its floor) and float there for every viewer and for the foothold sweep.
     * A real client falls onto the ground at once; this does the same on entry ({@code MapleMap.addPlayer}), before
     * the map tells anyone where it stands.
     */
    public static void groundOnEntry(server.maps.MapleMap map, Character chr) {
        if (map == null || chr == null || !(chr.getClient() instanceof SyntheticClient)) {
            return;
        }
        java.awt.Point ground = groundPoint(map.getFootholds(), chr.getPosition());
        if (ground != null) {
            chr.setPosition(ground);
            chr.setStance(4 | (chr.getStance() & 1)); // standing (v83 stance 4/5), facing as before
        }
    }

    /** Where a synthetic player entering at {@code p} lands: {@code BotGround.settle}; null with no ground under it. */
    static java.awt.Point groundPoint(server.maps.FootholdTree footholds, java.awt.Point p) {
        return soloMapling.ArtificialPlayer.BotMovementSystem.BotGround.settle(footholds, p);
    }

    /** The names in use, for the docs and the report. */
    static List<String> names() {
        return List.of(NAMES);
    }
}
