package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.ItemInformationProvider;
import server.maps.FieldLimit;
import server.maps.MapleMap;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

// Ported from GreenCatMS. Credit: NutNNut.
record BotMovementProfile(int totalSpeedStat, int totalJumpStat, boolean snowShoes)
        implements Serializable {
    // Serialized inside cached BotNavigationGraph instances; keep explicit so
    // cache compatibility is controlled by GRAPH_VERSION instead of compiler-generated UIDs.
    @Serial
    private static final long serialVersionUID = 1L;

    static final int BASE_TOTAL_STAT = 100;
    static final int STAT_BUCKET_SIZE = 5;
    static final int MAX_EFFECTIVE_SPEED_STAT = 200;
    static final int MAX_EFFECTIVE_JUMP_STAT = 123;
    static final BotMovementProfile BASE = new BotMovementProfile(BASE_TOTAL_STAT, BASE_TOTAL_STAT);

    // Earned speed (1.5.0, ticket 58, owner decision 2026-10-21): a bot moves exactly like a player with the same
    // things - 100% plus its real gear, its real buffs, and Haste only when it really has it (it holds the skill, or a
    // party member on the same map who holds it keeps the party hasted, as a player thief would). Capped like a
    // player: speed 140, jump 123. The old level/class baseline (115-155) is gone: it was counted on top of real
    // gear, and it split the nav graphs into many tiers - now almost every bot plays on the base graph.
    static final int PLAYER_MAX_SPEED = 140;
    static final int PLAYER_MAX_JUMP = MAX_EFFECTIVE_JUMP_STAT; // 123

    // Haste: Assassin, Bandit and Night Walker (the same skill); counted at the level the bot really holds.
    static final int[] HASTE_SKILLS = {4101004, 4201003, 14101003};

    BotMovementProfile {
        totalSpeedStat = bucketStat(totalSpeedStat);
        totalJumpStat = bucketStat(totalJumpStat);
        totalSpeedStat = Math.min(totalSpeedStat, MAX_EFFECTIVE_SPEED_STAT);
        totalJumpStat = Math.min(totalJumpStat, MAX_EFFECTIVE_JUMP_STAT);
    }

    BotMovementProfile(int totalSpeedStat, int totalJumpStat) {
        this(totalSpeedStat, totalJumpStat, false);
    }

    static BotMovementProfile base() {
        return BASE;
    }

    static BotMovementProfile fromCharacter(Character character) {
        if (character == null) {
            return BASE;
        }
        if (hasForcedBaseMovementStats(character)) {
            return BASE;
        }
        // getTotal*Stat = 100 + equips + real SPEED/JUMP buffs (a real player's Haste on a partied bot lands here).
        int speed = character.getTotalMoveSpeedStat();
        int jump = character.getTotalJumpStat();
        if (!hasRealSpeedBuff(character)) {
            int[] haste = hasteStats(character);
            speed += haste[0];
            jump += haste[1];
        }
        return new BotMovementProfile(Math.min(speed, PLAYER_MAX_SPEED), Math.min(jump, PLAYER_MAX_JUMP),
                wearsSnowShoes(character));
    }

    private static boolean hasRealSpeedBuff(Character character) {
        try {
            return character.getBuffedValue(client.BuffStat.SPEED) != null;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** The level of Haste this character really holds, or 0 (a bot with no skills holds none). */
    static int hasteLevel(Character character) {
        try {
            for (int id : HASTE_SKILLS) {
                int level = character.getSkillLevel(id);
                if (level > 0) {
                    return level;
                }
            }
        } catch (RuntimeException unavailable) {
            return 0;
        }
        return 0;
    }

    static boolean holdsHaste(Character character) {
        return hasteLevel(character) > 0;
    }

    /**
     * {speed, jump} Haste gives this bot: its own Haste, else the best Haste of a party member standing on the same
     * map (a player thief keeps the party hasted while they train together). {0, 0} when nobody there has it.
     */
    static int[] hasteStats(Character character) {
        int[] best = hasteOf(character);
        Party party;
        try {
            party = character.getParty();
        } catch (RuntimeException unavailable) {
            party = null;
        }
        if (best[0] > 0 || party == null) {
            return best;
        }
        for (PartyCharacter member : party.getMembers()) {
            if (member == null || member.getId() == character.getId() || !member.isOnline()
                    || member.getMapId() != character.getMapId() || member.getChannel() != channelOf(character)) {
                continue;
            }
            Character mate = member.getPlayer();
            if (mate == null) {
                continue;
            }
            int[] h = hasteOf(mate);
            if (h[0] > best[0]) {
                best = h;
            }
        }
        return best;
    }

    private static int channelOf(Character character) {
        try {
            return character.getClient().getChannel();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    /** {speed, jump} of the Haste this character holds, from the skill's own data at its level. */
    static int[] hasteOf(Character character) {
        int level = hasteLevel(character);
        if (level <= 0) {
            return new int[]{0, 0};
        }
        for (int id : HASTE_SKILLS) {
            try {
                if (character.getSkillLevel(id) != level) {
                    continue;
                }
                server.StatEffect effect = client.SkillFactory.getSkill(id).getEffect(level);
                int speed = 0;
                int jump = 0;
                for (tools.Pair<client.BuffStat, Integer> up : effect.getStatups()) {
                    if (up.getLeft() == client.BuffStat.SPEED) {
                        speed = up.getRight();
                    } else if (up.getLeft() == client.BuffStat.JUMP) {
                        jump = up.getRight();
                    }
                }
                return new int[]{speed, jump};
            } catch (RuntimeException unavailable) {
                // WZ skill data missing (unit tests): v83 Haste gives 2 speed and 1 jump per level
                return new int[]{2 * level, level};
            }
        }
        return new int[]{2 * level, level};
    }

    /* Snowshoes carry WZ info/fs (e.g. 10) on the worn shoe and cancel field
     *  slipperiness client-side — the wearer gets normal walk physics on snow/ice maps. */
    private static boolean wearsSnowShoes(Character character) {
        try {
            Item shoe = character.getInventory(InventoryType.EQUIPPED).getItem((short) -7);
            if (shoe == null) {
                return false;
            }
            Map<String, Integer> stats =
                    ItemInformationProvider.getInstance().getEquipStats(shoe.getItemId());
            return stats != null && stats.getOrDefault("fs", 0) >= 1;
        } catch (Throwable t) {
            return false; // WZ/equip data unavailable (unit tests, partial mocks)
        }
    }

    private static boolean hasForcedBaseMovementStats(Character character) {
        MapleMap map = character.getMap();
        return map != null && FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit());
    }

    private static int bucketStat(int stat) {
        int clamped = Math.max(1, stat);
        if (clamped < STAT_BUCKET_SIZE) {
            return clamped;
        }
        // Nearest bucket, not floor: a 144% bot plays on the 145 graph — halves the
        // worst-case drift between real stats and the physics/graph profile.
        return (int) (Math.round(clamped / (double) STAT_BUCKET_SIZE) * STAT_BUCKET_SIZE);
    }

    double speedMultiplier() {
        return totalSpeedStat / (double) BASE_TOTAL_STAT;
    }

    double jumpMultiplier() {
        return totalJumpStat / (double) BASE_TOTAL_STAT;
    }

    double walkVelocityPxs() {
        return BotMovementManager.cfg.WALK_VEL * speedMultiplier();
    }

    double hForcePxs() {
        return BotPhysicsEngine.cfg.HFORCE_PXS * speedMultiplier();
    }

    float jumpSpeedPxs() {
        return (float) (BotPhysicsEngine.cfg.JUMP_SPEED_PXS * jumpMultiplier());
    }

    float ropeJumpSpeedPxs() {
        return (float) (BotPhysicsEngine.cfg.JUMP_ROPE_PXS * jumpMultiplier());
    }
}
