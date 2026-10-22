package server.bounce;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.io.StringReader;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc3: a bot never flash-jumps where bounce-zones.yaml blocks Flash Jump for players (jump quests). */
class BotFlashJumpZoneTest {

    @AfterEach
    void reset() {
        BounceZones.useForTest(null);
    }

    @Test
    void flashJumpIsBlockedWhereTheZoneBlocksIt() throws Exception {
        BounceZones.useForTest(BounceZones.parse(new YamlReader(new StringReader("""
                categories:
                  - name: jq
                    enabled: true
                    block_flash_jump: true
                    maps: [101000100-101000104]
                  - name: lava
                    enabled: true
                    maps: [280020000-280020001]
                """)).read(), new ArrayList<>()));
        assertTrue(GCMovement.flashJumpBlocked(101000102));
        assertFalse(GCMovement.flashJumpBlocked(280020000), "bounce-only zone: Flash Jump stays");
        assertFalse(GCMovement.flashJumpBlocked(104040000));
    }
}
