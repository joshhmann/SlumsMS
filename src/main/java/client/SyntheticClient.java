package client;

import io.netty.buffer.Unpooled;
import io.netty.handler.timeout.IdleStateEvent;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import soloMapling.autoqa.AutoQaLine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Auto-QA's headless REAL player client (soloMapling.autoqa.SyntheticPlayers): a Client with no socket that logs in
 * through the real PlayerLoggedinHandler and hands packets to the real handlers. It is deliberately NOT a
 * {@link BotClient}: {@code BotHelpers.isBot} is "the client is a BotClient", so every bot system (affinity, chat
 * replies, trades, parties, guilds, drama) treats this character as a person.
 * <p>
 * What the server sends it is kept as {@link AutoQaLine}s for the packets a tester reads (map chat, whispers,
 * notices, the yellow command lines, buddy/party/guild chat, party/buddy/trade/guild invites); every other packet is
 * dropped after reading its opcode. The last party and guild invites are remembered so the runner can accept them.
 */
public final class SyntheticClient extends Client {

    static final int KEEP = 4000;

    private static final int CHATTEXT = SendOpcode.CHATTEXT.getValue();
    private static final int WHISPER = SendOpcode.WHISPER.getValue();
    private static final int SERVERMESSAGE = SendOpcode.SERVERMESSAGE.getValue();
    private static final int YELLOW = SendOpcode.SET_WEEK_EVENT_MESSAGE.getValue();
    private static final int MULTICHAT = SendOpcode.MULTICHAT.getValue();
    private static final int SPOUSE_CHAT = SendOpcode.SPOUSE_CHAT.getValue();
    private static final int PARTY = SendOpcode.PARTY_OPERATION.getValue();
    private static final int BUDDY = SendOpcode.BUDDYLIST.getValue();
    private static final int GUILD = SendOpcode.GUILD_OPERATION.getValue();
    private static final int INTERACTION = SendOpcode.PLAYER_INTERACTION.getValue();
    private static final int MARRIAGE_REQUEST = SendOpcode.MARRIAGE_REQUEST.getValue();
    private static final int NPC_TALK = SendOpcode.NPC_TALK.getValue();

    private final int slot;
    private final Deque<AutoQaLine> heard = new ArrayDeque<>();
    private volatile int lastPartyInviteId = -1;
    private volatile int lastGuildInviteId = -1;
    private volatile String lastGuildInviter;
    private volatile boolean closed;
    private volatile String lastProposer;
    private volatile int lastProposerId;

    public SyntheticClient(int slot, int world, int channel) {
        super(null, -2 - slot, "autoqa-" + slot, null, world, channel);
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }

    @Override
    public void sendPacket(Packet packet) {
        if (closed || packet == null) {
            return;
        }
        try {
            hear(packet.getBytes());
        } catch (RuntimeException e) {
            // a packet we cannot read is a packet we do not care about
        }
    }

    /** Decodes the packets a tester would read; public for the decoder test. */
    public void hear(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return;
        }
        int op = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
        if (op != CHATTEXT && op != WHISPER && op != SERVERMESSAGE && op != YELLOW && op != MULTICHAT && op != SPOUSE_CHAT
                && op != PARTY && op != BUDDY && op != GUILD && op != INTERACTION && op != MARRIAGE_REQUEST && op != NPC_TALK) {
            return;
        }
        InPacket p = new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));
        p.readShort();
        if (op == CHATTEXT) {
            int cid = p.readInt();
            p.readByte();
            String text = p.readString();
            add("CHAT", nameOf(cid) + ": " + text);
        } else if (op == WHISPER) {
            int flag = p.readByte() & 0xFF;
            if (flag == 0x12) {
                String from = p.readString();
                p.readByte();
                p.readByte();
                add("WHISPER", from + ": " + p.readString());
            } else if (flag == 0x0A) {
                String target = p.readString();
                add("WHISPER_RESULT", target + (p.readByte() != 0 ? " ok" : " not found"));
            }
        } else if (op == SERVERMESSAGE) {
            int type = p.readByte() & 0xFF;
            if (type == 4 && bytes.length >= 6 && bytes.length == 6 + ((bytes[4] & 0xFF) | (bytes[5] & 0xFF) << 8)) {
                p.readByte(); // the scrolling ticker carries a flag byte before its text; a plain type-4 notice does not
            }
            add("NOTICE", "[" + type + "] " + p.readString());
        } else if (op == YELLOW) {
            p.readByte();
            add("YELLOW", p.readString());
        } else if (op == MULTICHAT) {
            int mode = p.readByte() & 0xFF;
            String name = p.readString();
            String text = p.readString();
            add("MULTICHAT", (mode == 0 ? "buddy" : mode == 1 ? "party" : mode == 2 ? "guild" : "alliance") + " " + name + ": " + text);
        } else if (op == SPOUSE_CHAT) {
            add("SPOUSE_CHAT", p.readString() + ": " + p.readString());
        } else if (op == PARTY) {
            int code = p.readByte() & 0xFF;
            if (code == 4) {
                lastPartyInviteId = p.readInt();
                add("PARTY_INVITE", p.readString());
            } else if (code == 21 || code == 22 || code == 23) {
                add("PARTY_STATUS", code + " " + p.readString());
            } else if (code == 16 || code == 17 || code == 19) {
                add("PARTY_STATUS", String.valueOf(code));
            }
        } else if (op == BUDDY) {
            int code = p.readByte() & 0xFF;
            if (code == 9) {
                p.readInt();
                add("BUDDY_REQUEST", p.readString());
            }
        } else if (op == GUILD) {
            int code = p.readByte() & 0xFF;
            if (code == 0x05) {
                lastGuildInviteId = p.readInt();
                lastGuildInviter = p.readString();
                add("GUILD_INVITE", lastGuildInviter);
            }
        } else if (op == NPC_TALK) {
            p.readByte();
            int npc = p.readInt();
            p.readByte();
            p.readByte();
            String talk = p.readString().replaceFirst("^#w", "").replaceAll("#[bkrdegnl]", "").replace("\r\n", " ")
                    .replaceAll("\\s+", " ").trim();
            add("NPC_TALK", npc + ": " + talk);
        } else if (op == MARRIAGE_REQUEST) {
            p.readByte();
            lastProposer = p.readString();
            lastProposerId = p.readInt();
            add("PROPOSAL", lastProposer);
        } else if (op == INTERACTION) {
            int mode = p.readByte() & 0xFF;
            if (mode == 2) {
                p.readByte();
                add("TRADE_INVITE", p.readString());
            }
        }
    }

    private String nameOf(int cid) {
        Character me = getPlayer();
        if (me != null && me.getId() == cid) {
            return me.getName();
        }
        try {
            if (me != null && me.getMap() != null) {
                Character c = me.getMap().getCharacterById(cid);
                if (c != null) {
                    return c.getName();
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the id
        }
        return "#" + cid;
    }

    private synchronized void add(String kind, String message) {
        heard.addLast(new AutoQaLine(System.currentTimeMillis(), "heard:" + slot, kind, message));
        while (heard.size() > KEEP) {
            heard.removeFirst();
        }
    }

    /** What arrived since the time (inclusive). */
    public synchronized List<AutoQaLine> heardSince(long sinceMs) {
        List<AutoQaLine> out = new ArrayList<>();
        for (AutoQaLine l : heard) {
            if (l.timeMs() >= sinceMs) {
                out.add(l);
            }
        }
        return out;
    }

    public int lastPartyInviteId() {
        return lastPartyInviteId;
    }

    public void clearPartyInvite() {
        lastPartyInviteId = -1;
    }

    public int lastGuildInviteId() {
        return lastGuildInviteId;
    }

    public void clearGuildInvite() {
        lastGuildInviteId = -1;
    }

    public String lastProposer() {
        return lastProposer;
    }

    public int lastProposerId() {
        return lastProposerId;
    }

    public void clearProposal() {
        lastProposer = null;
    }

    /** Stops hearing (after logout). */
    public void close() {
        closed = true;
    }

    // ── no socket: the same guards BotClient has ──

    @Override
    public void announceBossHpBar(server.life.Monster mm, int mobHash, Packet packet) {
    }

    @Override
    public void disconnectSession() {
    }

    @Override
    public void closeSession() {
    }

    @Override
    public void checkIfIdle(final IdleStateEvent event) {
    }

    @Override
    public long getLastPacket() {
        return System.currentTimeMillis();
    }
}
