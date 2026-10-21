package soloMapling.ArtificialPlayer.BotAttackSystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/*
 * A test-side model of how MapleRoot's v83 client handles another character's attack packet (ticket 32), fed by the
 * client's own data (ClientAttackData). docs/real-bots/client-remote-attack-rules.md has the addresses and pseudocode;
 * each check names the client function it mirrors.
 *
 * check() decodes the packet the way CUserRemote::OnAttack (0x9803ab) does and returns every reason the viewer would
 * drop or misdraw it ("drop:" - nothing or part of the attack is drawn, "crash:" - the packet is malformed for the
 * client); playerLike() adds what differs from what a player's own client would have sent ("unlike:").
 */
final class ClientAttackRules {

    static final int CLOSE = 0xBA, RANGED = 0xBB, MAGIC = 0xBC, BODY = 0xBD, PREPARE = 0xBE, CANCEL = 0xBF;
    private static final Set<Integer> KEYDOWN_INT = Set.of(2121001, 2221001, 2321001, 22121000, 22151001);
    private static final int MESO_EXPLOSION = 4211006;

    /* What a viewer knows about the attacker: its visible weapon, its job, and the keydown skill it last prepared. */
    record Attacker(int weaponItemId, int jobId, int preparedSkill) {}

    /* The attack as the client decoded it. */
    record Decoded(int type, int mobs, int lines, int skillId, int skillLevel, int option, int action, boolean left,
                   int speed, int bullet) {}

    private final ClientAttackData data;

    ClientAttackRules(ClientAttackData data) {
        this.data = data;
    }

    /* The attack's decode (null when it does not decode) and every drop/crash the viewer's client hits with it. */
    List<String> check(byte[] packet, Attacker attacker, Decoded[] out) {
        List<String> bad = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        try {
            int type = b.getShort() & 0xFFFF;
            if (type == PREPARE || type == CANCEL) {
                return checkKeydownPacket(type, b);
            }
            if (type < CLOSE || type > BODY) {
                bad.add("crash: not an attack opcode " + Integer.toHexString(type));
                return bad;
            }
            b.getInt(); // attacker id (CUserPool)
            int info = b.get() & 0xFF;
            int mobs = info >> 4, lines = info & 0xF;
            b.get(); // attacker level
            int slv = b.get() & 0xFF;
            int skillId = slv > 0 ? b.getInt() : 0;
            int option = b.get() & 0xFF;
            int actionAndDir = b.getShort() & 0xFFFF;
            int action = actionAndDir & 0x7FFF;
            boolean left = (actionAndDir >> 15) != 0;
            int speed = b.get() & 0xFF;
            b.get(); // mastery
            int bullet = b.getInt();
            for (int m = 0; m < mobs; m++) {
                int mobId = b.getInt();
                if (mobId == 0) {
                    continue; // the client reads nothing more for an empty slot (0x98052f)
                }
                b.get(); // hit action
                int n = lines;
                if (skillId == MESO_EXPLOSION) {
                    n = b.get() & 0xFF;
                }
                for (int i = 0; i < n; i++) {
                    b.getInt();
                }
            }
            if (type == RANGED) {
                b.getShort();
                b.getShort();
            }
            if (KEYDOWN_INT.contains(skillId)) {
                b.getInt(); // keydown time (0x980671)
            }
            if (b.hasRemaining()) {
                bad.add("unlike: " + b.remaining() + " bytes the client never reads");
            }
            Decoded d = new Decoded(type, mobs, lines, skillId, slv, option, action, left, speed, bullet);
            if (out != null) {
                out[0] = d;
            }
            bad.addAll(rules(d, attacker));
        } catch (java.nio.BufferUnderflowException e) {
            bad.add("crash: the packet ends before the client stops reading (CInPacket throws)");
        }
        return bad;
    }

    private List<String> checkKeydownPacket(int type, ByteBuffer b) {
        List<String> bad = new ArrayList<>();
        b.getInt(); // attacker id
        int skillId = b.getInt();
        ClientAttackData.Skill skill = data.skills.get(skillId);
        if (skill == null) {
            bad.add("crash: OnPrepare/OnPrepareCancel for skill " + skillId + " the client does not have"
                    + (type == PREPARE ? " (0x980bd8 throws)" : ""));
            return bad;
        }
        if (type == PREPARE) {
            b.get(); // level
            int pose = b.getShort() & 0x7FFF;
            b.get(); // speed
            if (pose != 0x7FFF && pose >= data.actions.size()) {
                bad.add("crash: keydown pose " + pose + " is outside the client's action table");
            }
            if (b.hasRemaining()) {
                bad.add("unlike: " + b.remaining() + " bytes the client never reads");
            }
        }
        return bad;
    }

    private List<String> rules(Decoded d, Attacker attacker) {
        List<String> bad = new ArrayList<>();
        ClientAttackData.Skill skill = d.skillId() != 0 ? data.skills.get(d.skillId()) : null;
        ClientAttackData.ExeSkill exe = d.skillId() != 0 ? data.exe.get(d.skillId()) : null;
        int slv = skill == null ? 0 : Math.max(1, Math.min(d.skillLevel(), skill.maxLevel()));
        if (d.skillId() != 0 && skill == null) {
            bad.add("drop: skill " + d.skillId() + " is not in the client's Skill.wz (drawn as a plain attack at best)");
        }
        if (d.action() >= data.actions.size()) {
            bad.add("crash: pose " + d.action() + " is outside the client's action table (0x9807cf reads past it)");
            return bad;
        }
        ClientAttackData.Weapon weapon = data.weapons.get(attacker.weaponItemId());
        int wt = attacker.weaponItemId() / 1000000 == 1 ? attacker.weaponItemId() / 10000 % 100 : 0;
        if (wt != 0 && !((wt >= 30 && wt <= 33) || (wt >= 37 && wt <= 49))) {
            wt = 0; // get_weapon_type (0x460aa0)
        }
        boolean checksAction = d.type() != BODY && (exe == null || !exe.flags().contains("noActionCheck"));
        if (checksAction) {
            bad.addAll(checkAttackAction(d, skill, exe, slv, wt, weapon, attacker));
        }
        switch (d.type()) {
            case CLOSE -> {
                if (weapon == null || weapon.afterImage().isEmpty()) {
                    bad.add("drop: melee with a weapon that has no afterImage draws no hits (0x936f34)");
                }
            }
            case RANGED -> {
                boolean shooting = wt == 45 || wt == 46 || wt == 47 || wt == 49;
                if (!shooting && (exe == null || !exe.flags().contains("shootWithoutWeapon"))) {
                    bad.add("drop: a shot from a non-shooting weapon draws nothing (0x9812b4 / 0x76681d)");
                }
            }
            case MAGIC -> {
                if (skill == null) {
                    bad.add("drop: a spell without a skill the client knows draws nothing (0x98252a)");
                }
            }
            default -> { }
        }
        if (exe != null && exe.flags().contains("keepPose") && attacker.preparedSkill() != d.skillId()) {
            bad.add("drop: " + d.skillId() + " keeps the attacker's keydown pose and no keydown packet started one"
                    + " (OnAttack 0x980452 skips the pose)");
        }
        return bad;
    }

    /* CUser::CheckAttackAction (0x92edb2) -> 0x7665b1 (T1, T2) / 0x766525 (T3) and the weapon message (0x92ee56). */
    private List<String> checkAttackAction(Decoded d, ClientAttackData.Skill skill, ClientAttackData.ExeSkill exe,
                                           int slv, int wt, ClientAttackData.Weapon weapon, Attacker attacker) {
        List<String> bad = new ArrayList<>();
        int attackType = weapon != null ? weapon.attackType() : 0;
        if (attackType < 1 || attackType > 9) {
            if (skill == null && (attacker.jobId() / 100 == 21 || attacker.jobId() == 2000)
                    && d.action() >= 122 && d.action() <= 124) {
                return bad; // an Aran's plain pole-arm swing (0x92ee27)
            }
            bad.add("drop: the weapon's attack type " + attackType + " is outside 1..9 - no pose is valid");
            return bad;
        }
        if (skill != null && (exe == null || !exe.weaponTypes().contains(wt))) {
            bad.add("drop: weapon type " + wt + " cannot use skill " + skill.id()
                    + " (viewer's chat: \"This skill cannot be used with the equipped item.\")");
            return bad;
        }
        if (d.action() == 32) { // proneStab: only without a skill (0x766393)
            if (skill != null) {
                bad.add("drop: proneStab with a skill");
            }
            return bad;
        }
        boolean type3 = skill != null && skill.skillType() == 3;
        List<Integer> own = skill != null ? ownPoses(skill, slv) : List.of();
        if (!own.isEmpty() && !type3) {
            boolean ok = own.contains(d.action()) || (d.action() == 60 && own.contains(59));
            if (!ok) {
                bad.add("drop: pose " + d.action() + " (" + data.actions.get(d.action()) + ") is not one of skill "
                        + skill.id() + "'s " + names(own) + " (0x766418)");
            }
            return bad;
        }
        if (skill == null && (attacker.jobId() / 100 == 21 || attacker.jobId() == 2000)
                && d.action() >= 122 && d.action() <= 124) {
            return bad;
        }
        List<Integer> plain = new ArrayList<>();
        for (String t : new String[]{"T1", "T2", "T3"}) {
            for (int code : data.table(t, attackType, type3)) {
                plain.add(code);
            }
        }
        if (!plain.contains(d.action())) {
            bad.add("drop: pose " + d.action() + " (" + data.actions.get(d.action()) + ") is not a plain pose of a"
                    + " weapon of attack type " + attackType + ": " + names(plain));
        }
        return bad;
    }

    /* SKILLENTRY's own poses at a level: the level's action when the client knows it, else its known actions. */
    List<Integer> ownPoses(ClientAttackData.Skill skill, int slv) {
        String levelAction = skill.levelActions().get(slv);
        if (levelAction != null && data.actions.indexOf(levelAction) >= 0) {
            return List.of(data.actions.indexOf(levelAction));
        }
        List<Integer> out = new ArrayList<>();
        for (String a : skill.actions()) {
            int code = data.actions.indexOf(a);
            if (code >= 0) {
                out.add(code);
            }
        }
        return out;
    }

    /*
     * What a player's client would have sent differently (CUserLocal's send paths): the route DoActiveSkill takes,
     * the pose its route picks, the weapon's attack speed, the counts the level allows.
     */
    List<String> playerLike(Decoded d, Attacker attacker) {
        List<String> bad = new ArrayList<>();
        ClientAttackData.Skill skill = data.skills.get(d.skillId());
        ClientAttackData.ExeSkill exe = data.exe.get(d.skillId());
        ClientAttackData.Weapon weapon = data.weapons.get(attacker.weaponItemId());
        if (weapon != null && weapon.attackSpeed() > 0) {
            int speed = Math.max(2, Math.min(10, weapon.attackSpeed()));
            if (d.speed() != speed) {
                bad.add("unlike: attack speed " + d.speed() + ", the weapon's is " + speed + " (0x765066)");
            }
        }
        if (skill == null || exe == null) {
            return bad;
        }
        int route = switch (exe.route()) {
            case "melee" -> CLOSE;
            case "shoot" -> RANGED;
            case "magic" -> MAGIC;
            case "keydown" -> keydownRoute(d.skillId());
            default -> -1;
        };
        if (route != d.type() && !(route == -1 && hiddenMelee(d.skillId()) && d.type() == CLOSE)) {
            bad.add("unlike: sent as " + Integer.toHexString(d.type()) + ", the client's DoActiveSkill sends "
                    + d.skillId() + " as " + exe.route());
        }
        int slv = Math.max(1, Math.min(d.skillLevel(), skill.maxLevel()));
        int[] c = skill.countsAt(slv);
        if (c[0] > 0 && d.mobs() > c[0]) {
            bad.add("unlike: " + d.mobs() + " mobs, level " + slv + " hits " + c[0]);
        }
        int perMob = Math.max(c[1], c[2]);
        if (perMob > 0 && d.lines() != perMob && d.lines() != perMob * 2) {
            bad.add("unlike: " + d.lines() + " lines a mob, level " + slv + " draws " + perMob);
        }
        return bad;
    }

    /* The attack packet each keydown skill's release sends (0x95bfec dispatch). */
    static int keydownRoute(int skillId) {
        return switch (skillId) {
            case 3121004, 5221004, 13111002, 3221001 -> RANGED;
            case 2121001, 2221001, 2321001, 22121000, 22151001, 2111002 -> MAGIC;
            case 5101004, 15101003, 1121001, 1221001, 1321001 -> CLOSE;
            default -> -1;
        };
    }

    /* The Aran combo hits a client sends for Full Swing / Overswing (the melee path's own list, 0x950c07). */
    static boolean hiddenMelee(int skillId) {
        return skillId == 21110007 || skillId == 21110008 || skillId == 21120009 || skillId == 21120010;
    }

    private String names(List<Integer> codes) {
        List<String> out = new ArrayList<>();
        for (int c : codes) {
            out.add(c + ":" + data.actions.get(c));
        }
        return out.toString();
    }
}
