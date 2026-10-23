package server.bounce;

import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BounceZoneServiceTest {

    @AfterEach
    void reset() {
        BounceZones.useForTest(null);
        BounceZoneService.clearBypass(7);
    }

    static final class Fake implements BounceZoneService.Recipient {
        final int jobType;
        final List<Packet> got = new ArrayList<>();

        Fake(int jobType) {
            this.jobType = jobType;
        }

        public int jobType() {
            return jobType;
        }

        public void send(Packet packet) {
            got.add(packet);
        }
    }

    private static Character at(int mapId) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(7);
        when(chr.getName()).thenReturn("Tester");
        when(chr.getMapId()).thenReturn(mapId);
        return chr;
    }

    @Test
    void reloadPushesTheNewSetToEveryoneWithTheirOwnNativeBounce(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("zones.yaml");
        Files.writeString(f, BounceZonesTest.SAMPLE);
        Fake adventurer = new Fake(0), cygnus = new Fake(1), aran = new Fake(2);
        List<Fake> online = List.of(adventurer, cygnus, aran);

        assertEquals(3, BounceZoneService.reloadAndPush(() -> BounceZones.reload(f), online));
        for (Fake r : online) {
            assertEquals(2, r.got.size()); // the zones, then the bounce strengths (version 2)
            assertEquals(BounceZonePacketsTest.IMPULSES_DEFAULT, HexFormat.of().formatHex(r.got.get(1).getBytes()));
            assertEquals(BounceZonePackets.sync(BounceZones.getInstance(), BounceZones.nativeBounce(r.jobType)).getBytes().length,
                    r.got.get(0).getBytes().length);
        }
        String cygnusHex = HexFormat.of().formatHex(cygnus.got.get(0).getBytes());
        assertTrue(cygnusHex.startsWith("3737019a9a9800"), cygnusHex); // 10001050
        assertTrue(HexFormat.of().formatHex(aran.got.get(0).getBytes()).startsWith("3737011a313101")); // 20001050

        // the owner turns the jump quests off and reloads: everyone gets the smaller set
        Files.writeString(f, BounceZonesTest.SAMPLE.replace("enabled: true\n    block_flash_jump: true",
                "enabled: false\n    block_flash_jump: true"));
        BounceZoneService.reloadAndPush(() -> BounceZones.reload(f), online);
        assertFalse(BounceZones.getInstance().blocksBounce(101000100));
        assertTrue(adventurer.got.get(2).getBytes().length < adventurer.got.get(0).getBytes().length);
    }

    @Test
    void backstopRefusesBouncesOnBlockedMapsOnlyAndHonoursTheBypass() throws Exception {
        BounceZones.useForTest(BounceZonesTest.parse(BounceZonesTest.SAMPLE, new ArrayList<>()));
        assertTrue(BounceZoneService.refuse(at(101000100), 1050));
        assertTrue(BounceZoneService.refuse(at(101000100), 10001054));
        assertTrue(BounceZoneService.refuse(at(101000100), BounceZones.FLASH_JUMP_HERMIT));
        assertFalse(BounceZoneService.refuse(at(240000000), BounceZones.FLASH_JUMP_NIGHT_WALKER));
        assertTrue(BounceZoneService.refuse(at(240000000), 20001050));
        assertFalse(BounceZoneService.refuse(at(100000000), 1050));
        assertFalse(BounceZoneService.refuse(at(101000100), 2001002));

        assertTrue(BounceZoneService.toggleBypass(7));
        assertFalse(BounceZoneService.refuse(at(101000100), 1050));
        assertFalse(BounceZoneService.toggleBypass(7));
        assertTrue(BounceZoneService.refuse(at(101000100), 1050));
    }

    @Test
    void hereExplainsTheBlock() throws Exception {
        BounceZones z = BounceZonesTest.parse(BounceZonesTest.SAMPLE, new ArrayList<>());
        assertEquals("Map 101000102: bounces BLOCKED by jq, Flash Jump blocked. Listed in [jq].",
                client.command.commands.gm3.BounceCommand.here(z, 101000102));
        assertEquals("Map 100000000: bounces allowed, Flash Jump allowed. Listed in [off (off)].",
                client.command.commands.gm3.BounceCommand.here(z, 100000000));
        assertEquals("Map 999: bounces allowed, Flash Jump allowed.",
                client.command.commands.gm3.BounceCommand.here(z, 999));
    }
}
