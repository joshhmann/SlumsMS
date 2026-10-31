package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import constants.skills.Cleric;
import net.opcodes.SendOpcode;
import net.packet.Packet;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.Data;
import provider.wz.XMLDomMapleData;
import server.StatEffect;
import server.maps.MapleMap;
import soloMapling.qa.QaLog;
import soloMapling.qa.QaLogTestAccess;
import tools.PacketCreator;

import java.awt.Point;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * b140: a bot's party Heal shows viewers what a real player's Heal shows them in this server, plus the one cast effect
 * the stock server leaves out. The stock side is built here too: a player's Heal goes SPECIAL_MOVE ->
 * SpecialMoveHandler -> StatEffect.applyTo (Heal level 30 from MapleRoot's Skill.wz XML), on the same map, party and
 * viewer, and every packet each character receives is compared byte for byte with the bot's cast.
 */
class BotPartyHealCastTest {

    private static final int CASTER = 1_000_000_000 + 11;
    private static final int TARGET = 77;
    private static final int VIEWER = 88;

    @TempDir
    Path dir;

    @AfterEach
    void clean() {
        QaLogTestAccess.reset();
        BotAttackDriver.clearBot(CASTER);
    }

    /* MapleRoot's Skill.wz 230.img 2301002 level 30 (party box, 400% of the caster's max HP, 24 MP, 6 mobs). */
    private StatEffect heal30() throws Exception {
        Path f = dir.resolve("30.img.xml");
        Files.writeString(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"30\">"
                + "<string name=\"hs\" value=\"h30\"/><vector name=\"lt\" x=\"-300\" y=\"-200\"/>"
                + "<int name=\"mobCount\" value=\"6\"/><int name=\"mpCon\" value=\"24\"/>"
                + "<vector name=\"rb\" x=\"300\" y=\"200\"/><int name=\"hp\" value=\"400\"/>"
                + "<string name=\"attackCount\" value=\"2\"/></imgdir>");
        Data level;
        try (FileInputStream in = new FileInputStream(f.toFile())) {
            level = new XMLDomMapleData(in, dir);
        }
        return StatEffect.loadSkillEffectFromData(level, Cleric.HEAL, false);
    }

    /* A map whose broadcasts reach every character on it but the source, as MapleMap.broadcastMessage does. */
    private static final class Scene {
        final MapleMap map = mock(MapleMap.class);
        final Map<Integer, List<byte[]>> got = new LinkedHashMap<>();
        final List<Character> chars = new ArrayList<>();
        final List<Integer> hpChanged = new ArrayList<>();

        Scene() {
            doAnswer(inv -> {
                Character source = inv.getArgument(0);
                Packet p = inv.getArgument(1);
                boolean repeat = inv.getArgument(2);
                int n = 0;
                for (Character c : chars) {
                    if (repeat || c != source) {
                        c.sendPacket(p);
                        n++;
                    }
                }
                return n;
            }).when(map).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
            when(map.getMapObjectsInRect(any(), anyList())).thenAnswer(inv -> new ArrayList<>(chars));
        }

        Character add(int id, String name, int x, Party party) {
            Character c = mock(Character.class);
            when(c.getId()).thenReturn(id);
            when(c.getName()).thenReturn(name);
            when(c.getMap()).thenReturn(map);
            when(c.getMapId()).thenReturn(101000000);
            when(c.getPosition()).thenReturn(new Point(x, 100));
            when(c.getParty()).thenReturn(party);
            when(c.isAlive()).thenReturn(true);
            when(c.getHp()).thenReturn(1);
            when(c.getCurrentMaxHp()).thenReturn(11_117);
            when(c.getJob()).thenReturn(client.Job.PRIEST);
            List<byte[]> mine = new ArrayList<>();
            got.put(id, mine);
            doAnswer(inv -> mine.add(((Packet) inv.getArgument(0)).getBytes())).when(c).sendPacket(any(Packet.class));
            when(c.applyHpMpChange(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
                hpChanged.add(id);
                return true;
            });
            chars.add(c);
            return c;
        }
    }

    private static int opcode(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(0);
    }

    @Test
    void aBotHealSendsWhatAPlayersHealSendsPlusTheCastEffect() throws Exception {
        StatEffect heal = heal30();
        Party party = mock(Party.class);

        // stock: a real player casts Heal (SpecialMoveHandler: skill.getEffect(level).applyTo(chr, pos))
        Scene stock = new Scene();
        Character player = stock.add(CASTER, "Kairi", 500, party);
        stock.add(TARGET, "AqClient", 600, party);
        stock.add(VIEWER, "Viewer", 550, null);
        assertTrue(heal.applyTo(player, (Point) null));

        // the bot casts Heal on the same member, with the same map and viewer
        Scene bots = new Scene();
        Character bot = bots.add(CASTER, "Kairi", 500, party);
        Character healed = bots.add(TARGET, "AqClient", 600, party);
        bots.add(VIEWER, "Viewer", 550, null);
        long before = System.currentTimeMillis();
        BotPartySupport.GameEffects.castHeal(bot, healed, 30, heal);

        // the stock sequence: the member's own "affected" effect, the rest of the map (the caster too) the foreign one;
        // no cast effect on the caster
        byte[] own = PacketCreator.showOwnBuffEffect(Cleric.HEAL, 2).getBytes();
        byte[] foreign = PacketCreator.showBuffEffect(TARGET, Cleric.HEAL, 2).getBytes();
        assertEquals(1, stock.got.get(TARGET).size());
        assertArrayEquals(own, stock.got.get(TARGET).get(0));
        for (int id : new int[]{VIEWER, CASTER}) {
            assertEquals(1, stock.got.get(id).size());
            assertArrayEquals(foreign, stock.got.get(id).get(0));
        }

        // the bot: the cast effect a player's buff gets (StatEffect.applyBuffEffect's packet), then the stock sequence
        byte[] cast = PacketCreator.showBuffEffect(CASTER, Cleric.HEAL, 1, (byte) 3).getBytes();
        assertArrayEquals(cast, BotBuffEffects.castPacket(bot, Cleric.HEAL).getBytes());
        for (int id : new int[]{TARGET, VIEWER, CASTER}) {
            List<byte[]> want = new ArrayList<>();
            if (id != CASTER) {
                want.add(cast);   // to the map but the caster, as every broadcast of its own effect
            }
            want.addAll(stock.got.get(id));
            List<byte[]> sent = bots.got.get(id);
            assertEquals(want.size(), sent.size(), "packets to " + id);
            for (int i = 0; i < want.size(); i++) {
                assertArrayEquals(want.get(i), sent.get(i), "packet " + i + " to " + id);
            }
        }

        // no 0-mob MAGIC_ATTACK: a player's client sends Heal's magic attack only with an undead mob in the box
        for (List<byte[]> l : bots.got.values()) {
            for (byte[] b : l) {
                assertNotEquals(SendOpcode.MAGIC_ATTACK.getValue(), opcode(b), "no Heal magic attack without mobs");
            }
        }
        ByteBuffer c = ByteBuffer.wrap(cast).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(SendOpcode.SHOW_FOREIGN_EFFECT.getValue(), c.getShort(0));
        assertEquals(CASTER, c.getInt(2), "on the caster");
        assertEquals(1, c.get(6), "effect 1: skill use (CUser::OnEffect case 1 plays the pose and draws the column)");
        assertEquals(Cleric.HEAL, c.getInt(7));

        // the member's HP goes through the same StatEffect path (non-primary applyTo) in both
        assertEquals(List.of(TARGET), bots.hpChanged);
        assertTrue(stock.hpChanged.contains(TARGET));

        // the next attack waits for the cast to play out
        assertTrue(BotAttackDriver.swingEndsAt(CASTER) >= before + BotPartySupport.HEAL_ATTACK_HOLD_MS);
    }

    @Test
    void theHoldNeverShortensALongerWait() {
        long far = System.currentTimeMillis() + 60_000;
        BotAttackDriver.holdAttacks(CASTER, far);
        BotAttackDriver.holdAttacks(CASTER, far - 50_000);
        assertEquals(far, BotAttackDriver.swingEndsAt(CASTER));
    }

    @Test
    void eachHealCastIsLoggedUnderCombatWithThePacketsSentToTheHealedPlayersRun() throws Exception {
        List<String> lines = new ArrayList<>();
        // the healed player (id 77) runs a COMBAT scenario that watches the dojo; the heal happens in Ellinia
        QaLogTestAccess.listen(TARGET, java.util.Set.of(925020100), EnumSet.of(QaLog.Topic.COMBAT), dir, lines);
        Scene s = new Scene();
        Party party = mock(Party.class);
        Character bot = s.add(CASTER, "HealBot", 500, party);
        Character target = s.add(TARGET, "Owner", 600, party);
        StatEffect heal = heal30();
        BotPartySupport.GameEffects.castHeal(bot, target, 30, heal);
        BotPartySupport.GameEffects.castHeal(bot, target, 30, heal); // inside the 2 s throttle

        List<String> heals = lines.stream().filter(l -> l.contains("PARTY HEAL")).toList();
        assertEquals(List.of("COMBAT PARTY HEAL HealBot -> Owner skill 2301002 lv 30 hp 1->1 sent "
                + "SHOW_FOREIGN_EFFECT/1>map SHOW_ITEM_GAIN_INCHAT/2>target SHOW_FOREIGN_EFFECT/2>map hold 900ms"), heals);
    }

    @Test
    void nothingIsLoggedWhenCombatIsOff() {
        Scene s = new Scene();
        Character bot = s.add(CASTER, "HealBot", 500, null);
        BotPartySupport.logHeal(bot, s.add(TARGET, "Owner", 600, null), 30, List.of(), 1, 2);
        assertFalse(QaLog.on(QaLog.Topic.COMBAT));
    }
}
