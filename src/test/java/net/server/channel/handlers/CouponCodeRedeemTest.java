package net.server.channel.handlers;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import org.junit.jupiter.api.Test;
import server.CashShop;
import soloMapling.server.FakeNxDb;
import soloMapling.server.NXCodeManager;
import tools.DatabaseConnection;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 nx-1: an NX merchant's code, minted by NXCodeManager, redeemed through the real CouponCodeHandler packet
 * path: it credits 10,000 NX credit (gainCash(1, ...)) once, with or without the dashes, and never twice.
 */
class CouponCodeRedeemTest {

    private final FakeNxDb db = new FakeNxDb();

    private static Client buyer(String name, CashShop cs) {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(c);
        when(chr.getCashShop()).thenReturn(cs);
        when(c.getPlayer()).thenReturn(chr);
        when(c.tryacquireClient()).thenReturn(true);
        when(c.attemptCsCoupon()).thenReturn(true);
        return c;
    }

    private static void typeIntoCouponBox(Client c, String typed) {
        ByteBufOutPacket out = new ByteBufOutPacket();
        out.writeShort(0); // the two bytes the handler skips
        out.writeString(typed);
        new CouponCodeHandler().handlePacket(new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes())), c);
    }

    @Test
    void aMintedCodeCreditsTenThousandNxCreditOnceEvenTypedWithDashes() {
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenAnswer(a -> db.connection());
            String code = NXCodeManager.mintCode(10000);
            assertNotNull(code);

            CashShop cs = mock(CashShop.class);
            Client c = buyer("Buyer", cs);
            typeIntoCouponBox(c, code.toLowerCase()); // dashes and lower case, as a player might
            verify(cs, times(1)).gainCash(1, 10000);
            verify(cs, never()).gainCash(2, 10000);
            verify(cs, never()).gainCash(4, 10000);
            assertEquals("Buyer", db.code(NXCodeManager.normalize(code)).get("retriever"));

            typeIntoCouponBox(c, NXCodeManager.normalize(code)); // the same code again
            CashShop other = mock(CashShop.class);
            typeIntoCouponBox(buyer("Friend", other), code);
            verify(cs, times(1)).gainCash(anyInt(), anyInt());
            verify(other, never()).gainCash(anyInt(), anyInt());
        }
    }

    @Test
    void aRedeemThatLosesTheClaimCreditsNothing() {
        // two redeems at once: both pass the SELECT; before 1.6.0 both credited, now only the one whose UPDATE claims it
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenAnswer(a -> db.connection());
            String code = NXCodeManager.mintCode(10000);
            Map<String, Object> row = db.code(NXCodeManager.normalize(code));
            db.beforeClaim = () -> row.put("retriever", "Faster");
            CashShop cs = mock(CashShop.class);
            typeIntoCouponBox(buyer("Slower", cs), code);
            verify(cs, never()).gainCash(anyInt(), anyInt());
            assertEquals("Faster", row.get("retriever"));
        }
    }

    @Test
    void aDatabaseErrorOnTheClaimCreditsNothing() {
        // before 1.6.0 an SQLException fell through to "success" with the items already read: NX without a claim
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenAnswer(a -> db.connection());
            String code = NXCodeManager.mintCode(10000);
            db.failOn = "UPDATE";
            CashShop cs = mock(CashShop.class);
            typeIntoCouponBox(buyer("Buyer", cs), code);
            verify(cs, never()).gainCash(anyInt(), anyInt());
            assertNull(db.code(NXCodeManager.normalize(code)).get("retriever"));
        }
    }

    @Test
    void anUnknownCodeCreditsNothing() {
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenAnswer(a -> db.connection());
            CashShop cs = mock(CashShop.class);
            typeIntoCouponBox(buyer("Buyer", cs), "AAAAA-AAAAA-AAAAA");
            verify(cs, never()).gainCash(anyInt(), anyInt());
        }
    }
}
