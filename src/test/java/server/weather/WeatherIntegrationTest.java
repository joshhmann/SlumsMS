package server.weather;

import client.BotClient;
import client.Character;
import client.Client;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.task.WeatherTask;
import net.server.world.World;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import provider.Data;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeatherIntegrationTest {
    String previous;
    @BeforeEach void enable() {
        previous = System.getProperty("weather.enabled");
        System.setProperty("weather.enabled", "true");
    }
    @AfterEach void restore() {
        WeatherService.clearSkyOverride();
        WeatherService.clearTimeOverride();
        if (previous == null) System.clearProperty("weather.enabled");
        else System.setProperty("weather.enabled", previous);
    }
    private Data mapWithBase(String bank, int index) {
        Data map = mock(Data.class), base = mock(Data.class), name = mock(Data.class), number = mock(Data.class);
        when(map.getChildByPath("back/0")).thenReturn(base);
        when(base.getChildByPath("bS")).thenReturn(name);
        when(base.getChildByPath("no")).thenReturn(number);
        when(name.getData()).thenReturn(bank);
        when(number.getData()).thenReturn(index);
        return map;
    }
    @Test void classicFmAliasHasSameSkyClassificationAsOriginalArt() {
        WeatherMapVisibility.register(100000000, mapWithBase("grassySoil", 0));
        WeatherMapVisibility.register(910000000, mapWithBase("ClassicFM_grassySoil", 0));
        assertTrue(WeatherMapVisibility.hasVisibleSky(100000000));
        assertTrue(WeatherMapVisibility.hasVisibleSky(910000000));
    }
    @Test void interiorsAndUnknownCustomBackdropsDoNotAcquireInvisibleWeather() {
        WeatherMapVisibility.register(993910000, mapWithBase("rootabyss", 10));
        WeatherMapVisibility.register(999999998, null);
        assertFalse(WeatherMapVisibility.hasVisibleSky(993910000));
        assertFalse(WeatherMapVisibility.hasVisibleSky(999999998));
    }
    @Test void frozenNightAndRainHaveExactClientWireOrder() {
        WeatherService.setTime(WeatherService.TIME_NIGHT);
        WeatherService.setSky(WeatherProfile.RAIN.id(), WeatherService.OVERRIDE_HOLD_MS);
        byte[] packet = WeatherPackets.weatherSync(100000000, true).getBytes();
        assertEquals(28, packet.length);
        ByteBuffer bytes = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x373D, Short.toUnsignedInt(bytes.getShort()));
        assertEquals(0, Short.toUnsignedInt(bytes.getShort()));
        assertEquals(10000, bytes.getInt());
        assertEquals(WeatherProfile.RAIN.id(), bytes.get());
        byte flags = bytes.get();
        assertNotEquals(0, flags & WeatherService.FLAG_SNAP);
        assertNotEquals(0, flags & WeatherService.FLAG_FROZEN);
        assertEquals(1.0f, WeatherService.nightLevel());
    }
    @Test void forcedClockWrapsAndCanReturnToAutomaticTime() {
        WeatherService.setTime(-1);
        assertEquals(1439, WeatherService.minuteOfDay());
        WeatherService.setTime(WeatherService.TIME_DAY);
        assertEquals(0.0f, WeatherService.nightLevel());
        WeatherService.clearTimeOverride();
        assertFalse(WeatherService.isTimeOverridden());
        assertTrue(WeatherService.minuteOfDay() >= 0 && WeatherService.minuteOfDay() < 1440);
    }
    @Test void disabledProfileDoesNotSendOrTouchWorldStorage() {
        System.setProperty("weather.enabled", "false");
        Character player = mock(Character.class);
        World world = mock(World.class);
        WeatherPackets.sendTo(player);
        new WeatherTask(world).run();
        verifyNoInteractions(player, world);
    }
    @Test void headlessMapEntrantDoesNotReceiveWeather() {
        Character player = mock(Character.class);
        when(player.getClient()).thenReturn(mock(BotClient.class));
        WeatherPackets.sendTo(player);
        verify(player, never()).sendPacket(any());
    }
    @Test void oneFailedRecipientDoesNotPreventLaterWeatherUpdates() {
        World world = mock(World.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        Character bad = mock(Character.class), good = mock(Character.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getAllCharacters()).thenReturn(Arrays.asList(null, bad, good));
        for (Character player : new Character[]{bad, good}) {
            when(player.isLoggedinWorld()).thenReturn(true);
            when(player.getMapId()).thenReturn(100000000);
        }
        doThrow(new IllegalStateException("Disconnected test recipient")).when(bad).sendPacket(any());
        assertDoesNotThrow(() -> WeatherPackets.broadcast(world, false));
        ArgumentCaptor<Packet> received = ArgumentCaptor.forClass(Packet.class);
        verify(good).sendPacket(received.capture());
        assertEquals(28, received.getValue().getBytes().length);
    }
    @Test void aTickAfterWorldStorageWasRemovedIsSafe() {
        World world = mock(World.class);
        assertDoesNotThrow(() -> new WeatherTask(world).run());
    }
}
