package soloMapling.server;

import client.BuffStat;
import client.Character;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import net.packet.InPacket;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotSkillActions;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Attack capture (ticket 32, test server only): every attack, skill cast and keydown a real GM character's client
 * sends, one TSV line each in logs/attack-capture.tsv, so the bots' attacks can be copied from a real v83 client
 * instead of guessed from skill data. Off unless the JVM is started with -Dslums.attackcapture=true; there is no
 * config.yaml key on purpose. Only characters with GM level 2 or more are captured, never a bot - the owner plays
 * the classes on an admin character, and a friend on the test server is never logged.
 *
 * The columns use the bot side's names (BotAttackDriver / BotAttackEffects): the client's attack header is
 * display, direction, stance - a bot sends 0, its bodyActionId and its facingMask there - so the capture calls the
 * direction byte bodyActionId and the stance byte facingMask. The whole packet is kept as hex, opcode first, so a
 * field decoded wrongly here can be read again from the file. tools/attack-capture/attack_capture.py summarizes a
 * capture per job and diffs it against the bots (docs/real-bots/attack-capture.md).
 */
public final class AttackCapture {
    private static final Logger log = LoggerFactory.getLogger(AttackCapture.class);

    public static final String PROPERTY = "slums.attackcapture";
    public static final int MIN_GM_LEVEL = 2;

    static final List<String> COLUMNS = List.of("time", "event", "char", "jobId", "job", "level", "mapId",
            "skillId", "skillName", "skillLevel", "display", "bodyActionId", "actionName", "facingMask", "speed",
            "rangeDirection", "numAttacked", "numDamage", "charge", "projectile", "cashProjectile", "bulletSlot",
            "weaponId", "weaponClass", "weaponType", "buffs", "extra", "hex");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<Integer, String> skillNames = new ConcurrentHashMap<>();

    private static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    private static volatile Path file = Path.of("logs", "attack-capture.tsv");
    private static BufferedWriter out;

    private AttackCapture() {}

    public static boolean isEnabled() {
        return enabled;
    }

    /* Tests: turn capture on and write to the given file. */
    static synchronized void useFile(Path newFile, boolean on) {
        closeQuietly();
        file = newFile;
        enabled = on;
    }

    /* Whether this character's packets are captured: capture on, a real player, GM level 2 or more. */
    public static boolean captures(Character chr) {
        return enabled && chr != null && !BotHelpers.isBot(chr) && chr.gmLevel() >= MIN_GM_LEVEL;
    }

    /* A close-range, ranged, magic or touch attack; route is "close", "ranged", "magic" or "touch". */
    public static void attack(Character chr, String route, AttackInfo attack, InPacket p) {
        if (!captures(chr) || attack == null) {
            return;
        }
        guard(route, () -> {
            byte[] raw = raw(p);
            Map<String, Object> row = base(chr, route, attack.skill, attack.skilllevel);
            row.put("display", attack.display & 0xFF);
            row.put("bodyActionId", attack.direction & 0xFF);
            row.put("actionName", actionName(attack.direction & 0xFF));
            row.put("facingMask", attack.stance & 0xFF);
            row.put("speed", attack.speed);
            row.put("numAttacked", attack.numAttacked);
            row.put("numDamage", attack.numDamage);
            row.put("charge", attack.charge);
            if (attack.ranged) {
                row.put("rangeDirection", attack.rangedirection & 0xFF);
                int[] slots = bulletSlots(raw, attack.rangedTailPos);
                if (slots != null) {
                    row.put("bulletSlot", slots[0]);
                    row.put("projectile", ammoAt(chr, InventoryType.USE, slots[0]));
                    row.put("cashProjectile", ammoAt(chr, InventoryType.CASH, slots[1]));
                    row.put("extra", "rangedTail=" + hex(raw, attack.rangedTailPos, RANGED_TAIL));
                }
            }
            row.put("hex", hex(raw, 0, raw.length));
            write(row);
        });
    }

    /* A summon's attack: its skill, the direction byte the client sent and the mobs hit. */
    public static void summon(Character chr, int skillId, int skillLevel, int direction, int numAttacked, InPacket p) {
        if (!captures(chr)) {
            return;
        }
        guard("summon", () -> {
            Map<String, Object> row = base(chr, "summon", skillId, skillLevel);
            row.put("bodyActionId", direction & 0xFF);
            row.put("numAttacked", numAttacked);
            row.put("hex", hex(raw(p)));
            write(row);
        });
    }

    /* A buff or other skill cast (SPECIAL_MOVE), with the level the client named. */
    public static void cast(Character chr, int skillId, int skillLevel, InPacket p) {
        if (!captures(chr)) {
            return;
        }
        guard("cast", () -> {
            Map<String, Object> row = base(chr, "cast", skillId, skillLevel);
            row.put("hex", hex(raw(p)));
            write(row);
        });
    }

    /* A keydown skill being prepared (SKILL_EFFECT: Big Bang, Hurricane, Rapid Fire, Corkscrew Blow, ...). */
    public static void prepare(Character chr, int skillId, int skillLevel, int flags, int speed, int aids, InPacket p) {
        if (!captures(chr)) {
            return;
        }
        guard("prepare", () -> {
            Map<String, Object> row = base(chr, "prepare", skillId, skillLevel);
            row.put("speed", speed);
            row.put("extra", "flags=" + (flags & 0xFF) + ";aids=" + (aids & 0xFF));
            row.put("hex", hex(raw(p)));
            write(row);
        });
    }

    /* A keydown released or a buff cancelled (CANCEL_BUFF). */
    public static void cancel(Character chr, int skillId, InPacket p) {
        if (!captures(chr)) {
            return;
        }
        guard("cancel", () -> {
            Map<String, Object> row = base(chr, "cancel", skillId, chr.getSkillLevel(skillId));
            row.put("hex", hex(raw(p)));
            write(row);
        });
    }

    private static Map<String, Object> base(Character chr, String event, int skillId, int skillLevel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", LocalDateTime.now().format(TIME));
        row.put("event", event);
        row.put("char", chr.getName());
        row.put("jobId", chr.getJob() != null ? chr.getJob().getId() : "");
        row.put("job", chr.getJob());
        row.put("level", chr.getLevel());
        row.put("mapId", chr.getMapId());
        row.put("skillId", skillId);
        row.put("skillName", skillName(skillId));
        row.put("skillLevel", skillLevel);
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        int weaponId = weapon != null ? weapon.getItemId() : 0;
        row.put("weaponId", weaponId);
        row.put("weaponClass", BotSkillActions.weaponClass(weaponId));
        row.put("weaponType", weaponId > 0 ? ItemInformationProvider.getInstance().getWeaponType(weaponId) : "");
        row.put("buffs", buffs(chr));
        return row;
    }

    // The buffs that change what an attack looks like or which skill a class may use next.
    private static final Map<String, BuffStat> SHOWN_BUFFS = new LinkedHashMap<>();

    static {
        SHOWN_BUFFS.put("combo", BuffStat.COMBO);
        SHOWN_BUFFS.put("energy", BuffStat.ENERGY_CHARGE);
        SHOWN_BUFFS.put("morph", BuffStat.MORPH);
        SHOWN_BUFFS.put("shadowPartner", BuffStat.SHADOWPARTNER);
        SHOWN_BUFFS.put("soulArrow", BuffStat.SOULARROW);
        SHOWN_BUFFS.put("shadowClaw", BuffStat.SHADOW_CLAW);
        SHOWN_BUFFS.put("booster", BuffStat.BOOSTER);
        SHOWN_BUFFS.put("finalAttack", BuffStat.FINALATTACK);
        SHOWN_BUFFS.put("bodyPressure", BuffStat.BODY_PRESSURE);
        SHOWN_BUFFS.put("comboDrain", BuffStat.COMBO_DRAIN);
        SHOWN_BUFFS.put("darkSight", BuffStat.DARKSIGHT);
    }

    private static String buffs(Character chr) {
        StringJoiner held = new StringJoiner(";");
        for (Map.Entry<String, BuffStat> buff : SHOWN_BUFFS.entrySet()) {
            Integer value = chr.getBuffedValue(buff.getValue());
            if (value != null) {
                held.add(buff.getKey() + "=" + value);
            }
        }
        int charge = chr.getBuffSource(BuffStat.WK_CHARGE);
        if (charge > 0) {
            held.add("wkCharge=" + charge);
        }
        if (chr.getCombo() > 0) {
            held.add("aranCombo=" + chr.getCombo());
        }
        return held.toString();
    }

    // A ranged attack's tail after the range-direction byte: the rest of the attack time, the USE slot of the
    // projectile, the CASH slot of its look, one more byte (v83 layout).
    static final int RANGED_TAIL = 7;

    /* The {USE slot, CASH slot} a ranged attack names, or null when the packet is too short or was not parsed. */
    static int[] bulletSlots(byte[] raw, int tailPos) {
        if (raw == null || tailPos < 0 || tailPos + RANGED_TAIL > raw.length) {
            return null;
        }
        return new int[]{littleShort(raw, tailPos + 2), littleShort(raw, tailPos + 4)};
    }

    private static int littleShort(byte[] raw, int at) {
        return (short) ((raw[at] & 0xFF) | (raw[at + 1] & 0xFF) << 8);
    }

    // The ammo (stars, arrows, bullets, cash stars) at that slot, or empty when the slot holds none.
    private static Object ammoAt(Character chr, InventoryType type, int slot) {
        if (slot <= 0) {
            return "";
        }
        Item item = chr.getInventory(type).getItem((short) slot);
        if (item == null) {
            return "";
        }
        int id = item.getItemId();
        boolean ammo = ItemConstants.isThrowingStar(id) || ItemConstants.isBullet(id) || ItemConstants.isArrow(id)
                || id / 1000 == 5021;
        return ammo ? id : "";
    }

    static String actionName(int bodyActionId) {
        String name = BotSkillActions.nameOf(bodyActionId);
        return name != null ? name : "";
    }

    private static String skillName(int skillId) {
        if (skillId <= 0) {
            return "";
        }
        return skillNames.computeIfAbsent(skillId, id -> {
            try {
                String name = SkillFactory.getSkillName(id);
                return name != null ? name : "";
            } catch (RuntimeException e) {
                return ""; // no String.wz (tests)
            }
        });
    }

    /* The whole packet, opcode first, leaving the reader where it was. */
    static byte[] raw(InPacket p) {
        int at = p.getPosition();
        p.seek(0);
        byte[] all = p.readBytes(p.available());
        p.seek(at);
        return all;
    }

    static String hex(byte[] raw) {
        return hex(raw, 0, raw.length);
    }

    static String hex(byte[] raw, int from, int length) {
        return HexFormat.of().withUpperCase().formatHex(raw, from, from + length);
    }

    /* One TSV line in COLUMNS order; a missing column is empty and tabs or line breaks in a value become spaces. */
    static String line(Map<String, Object> row) {
        StringJoiner line = new StringJoiner("\t");
        for (String column : COLUMNS) {
            Object value = row.get(column);
            line.add(value == null ? "" : value.toString().replaceAll("[\\t\\r\\n]", " "));
        }
        return line.toString();
    }

    static String header() {
        return String.join("\t", COLUMNS);
    }

    private static synchronized void write(Map<String, Object> row) throws IOException {
        if (out == null) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean fresh = !Files.exists(file) || Files.size(file) == 0;
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            if (fresh) {
                out.write(header());
                out.newLine();
            }
        }
        out.write(line(row));
        out.newLine();
        out.flush();
    }

    private static synchronized void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // nothing more to save
            }
            out = null;
        }
    }

    private interface Capture {
        void run() throws IOException;
    }

    // A capture never gets in the way of the attack it records.
    private static void guard(String event, Capture capture) {
        try {
            capture.run();
        } catch (IOException | RuntimeException e) {
            log.warn("Attack capture of a {} failed", event, e);
        }
    }
}
