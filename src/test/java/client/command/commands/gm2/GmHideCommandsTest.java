package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.3 cv-13: the QA client sent "!unhide" twice at login. Both commands applied GM Hide skill 9101004, which
 * toggles hide (StatEffect.applyTo -> Character.toggleHide), so the second call hid the GM again and bots skipped
 * the hidden GM (BotHelpers.java:67). Each command must only act when it changes something.
 */
class GmHideCommandsTest {

    /** GM whose hidden flag follows Character.Hide, like the real Character. */
    private static final class FakeGm {
        final AtomicBoolean hidden = new AtomicBoolean();
        final Character chr = mock(Character.class);

        FakeGm(boolean startHidden) {
            hidden.set(startHidden);
            when(chr.isHidden()).thenAnswer(inv -> hidden.get());
            doAnswer(inv -> {
                hidden.set(inv.getArgument(0));
                return null;
            }).when(chr).Hide(anyBoolean());
        }
    }

    @Test
    void twoUnhideCallsLeaveTheGmVisible() {
        FakeGm gm = new FakeGm(true);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm.chr);

        try (MockedStatic<SkillFactory> factory = mockStatic(SkillFactory.class)) {
            // The old implementation's toggle, mapped onto the fake's state: applying the skill flips hide.
            Skill skill = mock(Skill.class);
            StatEffect effect = mock(StatEffect.class);
            when(skill.getEffect(anyInt())).thenReturn(effect);
            when(effect.applyTo(any(Character.class))).thenAnswer(inv -> {
                gm.hidden.set(!gm.hidden.get());
                return true;
            });
            factory.when(() -> SkillFactory.getSkill(9101004)).thenReturn(skill);

            new UnHideCommand().execute(c, new String[0]);
            new UnHideCommand().execute(c, new String[0]);
        }

        assertFalse(gm.hidden.get(), "GM stays visible after two !unhide calls");
    }

    @Test
    void twoHideCallsLeaveTheGmHidden() {
        FakeGm gm = new FakeGm(false);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm.chr);

        try (MockedStatic<SkillFactory> factory = mockStatic(SkillFactory.class)) {
            Skill skill = mock(Skill.class);
            StatEffect effect = mock(StatEffect.class);
            when(skill.getEffect(anyInt())).thenReturn(effect);
            when(effect.applyTo(any(Character.class))).thenAnswer(inv -> {
                gm.hidden.set(!gm.hidden.get());
                return true;
            });
            factory.when(() -> SkillFactory.getSkill(9101004)).thenReturn(skill);

            new HideCommand().execute(c, new String[0]);
            new HideCommand().execute(c, new String[0]);
        }

        assertTrue(gm.hidden.get(), "GM stays hidden after two !hide calls");
    }

    @Test
    void unhideOnAVisibleGmOnlySaysAlreadyVisible() {
        FakeGm gm = new FakeGm(false);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm.chr);

        new UnHideCommand().execute(c, new String[0]);

        verify(gm.chr).yellowMessage("You are already visible.");
        verify(gm.chr, never()).Hide(anyBoolean());
        assertFalse(gm.hidden.get());
    }

    @Test
    void unhideOnAHiddenGmUnhidesWithoutAWarning() {
        FakeGm gm = new FakeGm(true);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm.chr);

        new UnHideCommand().execute(c, new String[0]);

        verify(gm.chr).Hide(false);
        verify(gm.chr, never()).yellowMessage(anyString());
        assertFalse(gm.hidden.get());
    }

    @Test
    void hideOnAHiddenGmOnlySaysAlreadyHidden() {
        FakeGm gm = new FakeGm(true);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm.chr);

        new HideCommand().execute(c, new String[0]);

        verify(gm.chr).yellowMessage("You are already hidden.");
        verify(gm.chr, never()).Hide(anyBoolean());
        assertTrue(gm.hidden.get());
    }

    @Test
    void hideOnAVisibleGmHides() {
        FakeGm gm = new FakeGm(false);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm.chr);

        new HideCommand().execute(c, new String[0]);

        verify(gm.chr).Hide(true);
        verify(gm.chr, never()).yellowMessage(anyString());
        assertTrue(gm.hidden.get());
    }
}
