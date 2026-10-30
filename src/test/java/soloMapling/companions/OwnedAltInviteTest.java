package soloMapling.companions;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 1.6.2 ticket 99 (nutnnut/Cosmic 59f9bc091): a party invite naming your own offline character calls it. */
class OwnedAltInviteTest {

    boolean before;

    @BeforeEach
    void setUp() {
        before = YamlConfig.config.server.OWNED_ALTS_INVITE_SUMMON_ENABLED;
    }

    @AfterEach
    void tearDown() {
        YamlConfig.config.server.OWNED_ALTS_INVITE_SUMMON_ENABLED = before;
    }

    @Test
    void offTheInviteAnswersAsBefore() {
        YamlConfig.config.server.OWNED_ALTS_INVITE_SUMMON_ENABLED = false;
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory();
        db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        assertFalse(OwnedAltInvite.onInviteNotFound(OwnedAltServiceTest.owner(1), "Alt2", s, c -> { }));
        assertEquals(0, db.loads.get());
    }

    @Test
    void yourOwnOfflineCharacterIsCalledAnyoneElseIsNotFound() {
        YamlConfig.config.server.OWNED_ALTS_INVITE_SUMMON_ENABLED = true;
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory();
        db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = OwnedAltServiceTest.owner(1);
        List<Character> notFound = new ArrayList<>();
        assertTrue(OwnedAltInvite.onInviteNotFound(owner, "alt2", s, notFound::add));
        assertEquals(1, db.loads.get(), "called as @botme alt2");
        assertTrue(notFound.isEmpty());
        assertTrue(OwnedAltInvite.onInviteNotFound(owner, "Stranger", s, notFound::add));
        assertEquals(List.of(owner), notFound, "the normal cannot-find answer");
        assertEquals(1, db.loads.get());
        verify(owner, never()).yellowMessage("Stranger");
    }
}
