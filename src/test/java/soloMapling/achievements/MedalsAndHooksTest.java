package soloMapling.achievements;

import client.Character;
import client.inventory.Equip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MedalsAndHooksTest {

    @AfterEach
    void tearDown() {
        Achievements.setEnabledForTest(null);
        Achievements.useForTest(null);
    }

    @Test
    void aMedalCopyCarriesNoStatsAtAll() {
        Equip e = mock(Equip.class);
        GameHands.zeroStats(e);
        short zero = 0;
        org.mockito.Mockito.verify(e).setStr(zero);
        org.mockito.Mockito.verify(e).setDex(zero);
        org.mockito.Mockito.verify(e).setInt(zero);
        org.mockito.Mockito.verify(e).setLuk(zero);
        org.mockito.Mockito.verify(e).setHp(zero);
        org.mockito.Mockito.verify(e).setMp(zero);
        org.mockito.Mockito.verify(e).setWatk(zero);
        org.mockito.Mockito.verify(e).setMatk(zero);
        org.mockito.Mockito.verify(e).setWdef(zero);
        org.mockito.Mockito.verify(e).setMdef(zero);
        org.mockito.Mockito.verify(e).setAcc(zero);
        org.mockito.Mockito.verify(e).setAvoid(zero);
        org.mockito.Mockito.verify(e).setHands(zero);
        org.mockito.Mockito.verify(e).setSpeed(zero);
        org.mockito.Mockito.verify(e).setJump(zero);
        org.mockito.Mockito.verify(e).setVicious(zero);
        org.mockito.Mockito.verify(e).setUpgradeSlots((byte) 0);
    }

    /** 1.6.1 notes promise "a yellow line": the yellow tip (as the Slums Stamps claim), not light-blue type 6. */
    @Test
    void theEarnedLineIsTheYellowTip() {
        Character chr = mock(Character.class);
        org.mockito.Mockito.when(chr.getId()).thenReturn(4_321);
        new GameHands().tell(chr, "Achievement: Fishing Hobby - Keep 10 catches while fishing.");
        org.mockito.Mockito.verify(chr).yellowMessage("Achievement: Fishing Hobby - Keep 10 catches while fishing.");
        org.mockito.Mockito.verify(chr, org.mockito.Mockito.never()).dropMessage(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void withTheSwitchOffEveryHookDoesNothingAndNeverThrows() {
        Achievements.setEnabledForTest(false);
        AtomicInteger queued = new AtomicInteger();
        try {
            Achievements.useForTest(new AchievementService(GoalCatalogTest.shipped(), new MemoryStore(), new FakeHands(),
                    System::currentTimeMillis, r -> queued.incrementAndGet()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Character chr = mock(Character.class);
        Achievements.onFish(chr);
        Achievements.onJob(chr, 112);
        Achievements.onLevel(chr);
        Achievements.onMobKill(chr, 2220000);
        Achievements.onClear("ZakumBattle", List.of(chr));
        Achievements.onMap(chr, 100000000);
        Achievements.onLogin(chr);
        Achievements.onBotWedding(1_000_000_001);
        assertNull(Achievements.service(), "off means no service, whatever was injected");
        assertEquals(0, queued.get());
    }

    @Test
    void thePlayersListShowsEarnedGoalsAndProgress() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeHands hands = new FakeHands();
        AchievementService s = new AchievementService(GoalCatalogTest.shipped(), store, hands, () -> 5L, Runnable::run);
        Subject me = new Subject(77, false, mock(Character.class));
        s.handle(me, new Event(Trigger.LEVEL, 31), false);
        for (int i = 0; i < 4; i++) {
            s.handle(me, Event.of(Trigger.FISH), false);
        }
        List<String> lines = client.command.commands.gm0.AchievementsCommand.lines(s, 77, false);
        assertTrue(lines.get(0).startsWith("Achievements: 1 of "), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Earned: Level 30")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("[4/10] Fishing Hobby")), lines.toString());
        List<String> all = client.command.commands.gm0.AchievementsCommand.lines(s, 77, true);
        assertEquals(1 + s.catalog().goals().size(), all.size());
        assertTrue(all.stream().anyMatch(l -> l.startsWith("[done] Level 30")), all.toString());
    }
}
