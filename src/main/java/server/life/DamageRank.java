package server.life;

import client.Character;
import config.YamlConfig;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.SoloMaplingConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 1.6.1 (client ticket 118, DPS meter): LP 0x3739 BOSS_DAMAGE_RANK, the party's damage ranking on one mob.
 *
 * <pre>
 * int mobId | byte n | n x { int charId, mapleAsciiString name, long damage }   sorted by damage, highest first
 * </pre>
 *
 * Sent at most once a second per mob, from the hit path, only to real-player party members on the mob's map who
 * have damaged it and whose client build is at least {@code DAMAGE_RANK_MIN_BUILD}; bots (no client) are never
 * recipients but are ranked by name. Only for bosses and mobs with at least {@code DAMAGE_RANK_MIN_MOB_HP} max HP.
 * Switch: {@code DAMAGE_RANK_ENABLED} or {@code -Dslums.dmgrank=true}. A hit costs one boolean read when off.
 */
public final class DamageRank {
    public static final int MAX_ENTRIES = 6;
    public static final long INTERVAL_MS = 1000;

    /** A party member as seen by the filter and the ranking. */
    public record Member(int charId, String name, boolean realPlayer, int build, boolean sameMap, boolean damaged) {
    }

    private DamageRank() {
    }

    public static boolean enabled() {
        return YamlConfig.config.server.DAMAGE_RANK_ENABLED || Boolean.getBoolean("slums.dmgrank");
    }

    /** Called after a hit has been added to the mob's damage table. */
    static void onHit(Monster mob, Character from) {
        if (!enabled() || !mob.isBoss() && mob.getMaxHp() < YamlConfig.config.server.DAMAGE_RANK_MIN_MOB_HP) {
            return;
        }
        Party party = from.getParty();
        if (party == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!due(mob.dmgRankAt, now)) {
            return;
        }
        mob.dmgRankAt = now;
        try {
            Map<Integer, Long> snapshot = mob.damageSnapshot();
            List<Member> members = new ArrayList<>();
            List<Character> chars = new ArrayList<>();
            int minBuild = YamlConfig.config.server.DAMAGE_RANK_MIN_BUILD;
            for (PartyCharacter pc : party.getMembers()) {
                Character c = mob.getMap().getCharacterById(pc.getId());
                boolean player = c != null && isRealPlayer(c);
                members.add(new Member(pc.getId(), pc.getName(), player,
                        player && c.getClient() != null ? c.getClient().getClientBuild() : 0,
                        c != null, snapshot.containsKey(pc.getId())));
                chars.add(c);
            }
            List<Integer> to = recipients(members, minBuild);
            if (to.isEmpty()) {
                return;
            }
            Packet packet = packet(mob.getId(), rank(snapshot, members, MAX_ENTRIES));
            for (int i = 0; i < members.size(); i++) {
                if (to.contains(members.get(i).charId())) {
                    chars.get(i).sendPacket(packet);
                }
            }
        } catch (RuntimeException e) {
            // the meter must never break a hit
        }
    }

    private static boolean isRealPlayer(Character c) {
        return !BotHelpers.isBot(c) && c.getId() < SoloMaplingConstants.GameConstants.BOT_BASE_ID;
    }

    /** True when at least INTERVAL_MS has passed since the last send for this mob. */
    public static boolean due(long lastSentAt, long now) {
        return now - lastSentAt >= INTERVAL_MS;
    }

    /** Char ids that get the packet: real players on the map, who damaged the mob, with a new enough build. */
    public static List<Integer> recipients(List<Member> party, int minBuild) {
        List<Integer> out = new ArrayList<>();
        for (Member m : party) {
            if (m.realPlayer() && m.sameMap() && m.damaged() && m.build() >= minBuild) {
                out.add(m.charId());
            }
        }
        return out;
    }

    /** Party members that damaged the mob (bots too), highest damage first, at most cap. */
    public static List<Entry> rank(Map<Integer, Long> snapshot, List<Member> party, int cap) {
        List<Entry> all = new ArrayList<>();
        for (Member m : party) {
            Long dmg = snapshot.get(m.charId());
            if (dmg != null) {
                all.add(new Entry(m.charId(), m.name(), dmg));
            }
        }
        all.sort(Comparator.comparingLong(Entry::damage).reversed().thenComparingInt(Entry::charId));
        return all.size() > cap ? new ArrayList<>(all.subList(0, cap)) : all;
    }

    public record Entry(int charId, String name, long damage) {
    }

    public static Packet packet(int mobId, List<Entry> entries) {
        OutPacket p = OutPacket.create(SendOpcode.BOSS_DAMAGE_RANK);
        p.writeInt(mobId);
        p.writeByte(entries.size());
        for (Entry e : entries) {
            p.writeInt(e.charId());
            p.writeString(e.name());
            p.writeLong(e.damage());
        }
        return p;
    }
}
