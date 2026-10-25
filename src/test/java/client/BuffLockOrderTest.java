package client;

import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.MapleMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic c9ba4f4da and 8a2d4adaf): a Character's buff code takes its locks in the
 * order prtLock -> effLock -> chrLock (registerEffect, cancelEffect). updateActiveEffects, cancelBuffStats and the soft
 * branch of cancelAllBuffs took effLock first and then reached prtLock through StatEffect.isActive ->
 * getPartyMembersOnSameMap (or the public cancelEffect), the opposite order. A party member buffing the character
 * (which runs registerEffect on the target's locks) at the same moment hung both threads for good, and with thousands
 * of bots that moment comes.
 *
 * Each test plays the buffing party member: it holds prtLock, lets the method under test run on another thread until
 * it is waiting for prtLock, then asks for effLock the way registerEffect does. Before the fix the other thread was
 * already holding effLock, so the request timed out (in a live server: a permanent hang).
 */
class BuffLockOrderTest {

    private static final int SOURCE_A = 2001002;
    private static final int SOURCE_B = 2001003;

    @Test
    void updateActiveEffectsTakesThePartyLockBeforeTheEffectLock() throws Exception {
        Character chr = newCharacter();
        StatEffect buff = partyAwareEffect(chr, SOURCE_A);
        buffEffects(chr).put(SOURCE_A, statMap(BuffStat.WATK, holder(buff, 10)));
        buffEffectsCount(chr).put(BuffStat.WATK, (byte) 1);

        assertNoAbBaInversion(chr, Character::updateActiveEffects);
    }

    @Test
    void cancelBuffStatsTakesThePartyLockBeforeTheEffectLock() throws Exception {
        Character chr = newCharacter();
        StatEffect a = partyAwareEffect(chr, SOURCE_A);
        StatEffect b = partyAwareEffect(chr, SOURCE_B);
        Object holderA = holder(a, 10);
        buffEffects(chr).put(SOURCE_A, statMap(BuffStat.WATK, holderA));
        buffEffects(chr).put(SOURCE_B, statMap(BuffStat.WATK, holder(b, 5)));
        buffEffectsCount(chr).put(BuffStat.WATK, (byte) 2);
        effects(chr).put(BuffStat.WATK, holderA);  // A is the applied one: dropping it looks for the next best

        assertNoAbBaInversion(chr, c -> c.cancelBuffStats(BuffStat.WATK));
    }

    @Test
    void softCancelAllBuffsTakesThePartyLockBeforeTheEffectLock() throws Exception {
        Character chr = newCharacter();
        StatEffect summon = partyAwareEffect(chr, SOURCE_A);
        effects(chr).put(BuffStat.SUMMON, holder(summon, 1));

        assertNoAbBaInversion(chr, c -> c.cancelAllBuffs(true));
    }

    private static void assertNoAbBaInversion(Character chr, Consumer<Character> call) throws Exception {
        ReentrantLock prtLock = (ReentrantLock) lock(chr, Character.class, "prtLock");
        Lock effLock = lock(chr, AbstractCharacterObject.class, "effLock");

        Thread worker = new Thread(() -> {
            try {
                call.accept(chr);
            } catch (RuntimeException ignored) {
                // the mocked buffs are thin; only the locking up to here matters
            }
        }, "buff-lock-order-worker");
        worker.setDaemon(true);

        boolean effLockGranted;
        prtLock.lock();
        try {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!prtLock.hasQueuedThread(worker) && worker.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(prtLock.hasQueuedThread(worker),
                    "the method under test never asked for prtLock, so this test does not exercise the race");

            // registerEffect's next step on the party member's thread
            effLockGranted = effLock.tryLock(2, TimeUnit.SECONDS);
            if (effLockGranted) {
                effLock.unlock();
            }
        } finally {
            prtLock.unlock();
        }

        worker.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(worker.isAlive(), "the method under test is still stuck");
        assertTrue(effLockGranted, "AB-BA: the method held effLock while waiting for prtLock (a deadlock against "
                + "registerEffect)");
    }

    private static StatEffect partyAwareEffect(Character chr, int sourceId) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getBuffSourceId()).thenReturn(sourceId);
        when(effect.getSourceId()).thenReturn(sourceId);
        when(effect.getStatups()).thenReturn(Collections.emptyList());
        // the real isActive asks the party (prtLock); keep that call, it is the one that inverts the order
        when(effect.isActive(any(Character.class))).thenAnswer(inv -> chr.getPartyMembersOnSameMap().size() > 1);
        return effect;
    }

    private static Character newCharacter() throws Exception {
        Constructor<Character> ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Character chr = ctor.newInstance();
        Field map = AbstractCharacterObject.class.getDeclaredField("map");
        map.setAccessible(true);
        map.set(chr, mock(MapleMap.class));
        Field client = Character.class.getDeclaredField("client");
        client.setAccessible(true);
        client.set(chr, mock(Client.class));
        return chr;
    }

    private static Object holder(StatEffect effect, int value) throws Exception {
        Class<?> cls = Class.forName("client.Character$BuffStatValueHolder");
        Constructor<?> ctor = cls.getDeclaredConstructor(StatEffect.class, long.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(effect, System.currentTimeMillis(), value);
    }

    private static Map<BuffStat, Object> statMap(BuffStat stat, Object holder) {
        Map<BuffStat, Object> m = new LinkedHashMap<>();
        m.put(stat, holder);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> buffEffects(Character chr) throws Exception {
        return (Map<Integer, Object>) field(chr, "buffEffects");
    }

    @SuppressWarnings("unchecked")
    private static Map<BuffStat, Byte> buffEffectsCount(Character chr) throws Exception {
        return (Map<BuffStat, Byte>) field(chr, "buffEffectsCount");
    }

    @SuppressWarnings("unchecked")
    private static Map<BuffStat, Object> effects(Character chr) throws Exception {
        return (Map<BuffStat, Object>) field(chr, "effects");
    }

    private static Object field(Character chr, String name) throws Exception {
        Field f = Character.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(chr);
    }

    private static Lock lock(Character chr, Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (Lock) f.get(chr);
    }
}
