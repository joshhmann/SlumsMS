package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.Ledger.Posting;
import soloMapling.Economy.exchange.Ledger.Why;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * t-xc-1 on a server that runs the NX desk ({@code -Dslums.exchange.desk}, as TEST does). With the desk on the NX
 * order book is off (ticket 162 §4), so the check must not rest an NX order: it trades its 100 NX twice at the desk's
 * quote and the only cost is the spread. Before this, {@code ExchangeQa.stage} rested the test regular's NX bid anyway
 * ({@link Exchange#placeRegularForQa} went straight to {@code placeOrder}, past {@link Exchange#place}'s desk gate),
 * the probe's book steps were refused by {@code @nx sell 100 <price>} ("NX trades at the desk's quote now"), so its
 * next {@code @nx confirm} answered TOKEN_EXPIRED with nothing to confirm.
 */
class ExchangeQaDeskTest {

    static final long NX0 = 100_000;
    static final long MESO0 = 50_000_000;
    static final long HOUSE = 9;
    static final long HOUSE_CHAR = 19;

    MemoryExchangeStore store;
    AtomicLong now;
    DeskConfig deskCfg;
    int seq;
    long totalNx, totalMeso;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        store.addAccount(1, NX0);
        store.addCharacter(11, 1, MESO0);
        store.addAccount(HOUSE, 1_000_000);
        store.addCharacter(HOUSE_CHAR, HOUSE, 1_000_000_000);
        now = new AtomicLong(1_800_000_000_000L);
        deskCfg = DeskConfig.defaults().on();
    }

    /** An exchange with the desk on, on this store. */
    Exchange start() throws Exception {
        Exchange e = new Exchange(store, ExchangeLiveTest.cfg(0, HOUSE), store::online, store.finder(), now::get);
        e.guard(ExchangeTest.engineGuard());
        e.siteReauth(SiteReauth.OFF);
        e.deskConfig(deskCfg);
        assertTrue(e.recover().isEmpty());
        totalNx = store.totalNx();
        totalMeso = store.totalMeso();
        return e;
    }

    /** Moves NX and mesos into the desk's HOUSE buckets, as {@code !exchange desk fund} does. */
    void seedDesk(long nx, long meso) throws Exception {
        List<Posting> ps = new ArrayList<>();
        if (nx > 0) {
            ps.add(new Posting(Why.FUND, Bucket.wallet(Asset.NX, 1), Exchange.deskBucket(Asset.NX), nx, 1, 0, 0));
        }
        if (meso > 0) {
            ps.add(new Posting(Why.FUND, Bucket.wallet(Asset.MESO, HOUSE_CHAR), Exchange.deskBucket(Asset.MESO), meso, HOUSE, 0,
                    0));
        }
        store.apply(new ExchangeStore.Batch(9000, now.get(), ps, List.of(), List.of()));
        totalNx = store.totalNx();
        totalMeso = store.totalMeso();
    }

    Response preview(Exchange e, long account, Side side, long nx) {
        return e.preview(new Place("p" + (++seq), account, 11, side, 0, nx, Ticker.NX));
    }

    Response previewAndConfirm(Exchange e, long account, Side side, long nx) {
        Response pv = preview(e, account, side, nx);
        if (!pv.ok()) {
            return pv;
        }
        return e.confirm("c" + (++seq), account, (String) pv.data().get("token"));
    }

    /**
     * The defect: with the desk on, the NX book is off, so the check's staging must refuse instead of resting an order
     * nobody can trade against (its escrow would sit there until a staff cancel). Fails before the fix: the regular's
     * bid rests on the book and {@code stage} returns happily.
     */
    @Test
    void noNxBookOrderRestsWhileTheDeskRuns() throws Exception {
        Exchange e = start();
        seedDesk(5_000, 15_000_000);
        assertTrue(e.depth(Side.BUY, 1).isEmpty(), "the book starts empty");
        // The price the probe used: the band's resting buy price, which under the desk is the desk's own bid.
        long price = e.nxBand()[0];
        store.login(1, 11);
        LiveWallet w = store.finder().find(1);
        long need = e.config().buyTotal(ExchangeQa.NX, price);
        assertTrue(e.fundHouseLive(ExchangeQa.REGULAR, w, Asset.MESO, need, true).ok(), "the regular is funded");
        Response bid = e.placeRegularForQa(ExchangeQa.REGULAR, Side.BUY, price, ExchangeQa.NX);
        assertFalse(bid.ok(), "the NX book is off under the desk: no NX order may rest: " + bid);
        assertEquals("BAD_REQUEST", bid.code());
        assertTrue(e.depth(Side.BUY, 1).isEmpty(), "nothing rests on the book the desk replaced");
        assertTrue(e.openIdsOf(Regulars.owner(ExchangeQa.REGULAR)).isEmpty(), "no order for the test regular");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ExchangeQa.stage(e, store.finder().find(1)));
        assertTrue(refused.getMessage().contains("desk"), "the reason names the desk: " + refused.getMessage());
    }

    /**
     * The desk round trip t-xc-1 now runs: BUY 100 NX at the desk's sell quote, SELL it back at its buy quote. The
     * wallet's NX comes back, the desk's NX comes back, nothing is minted and the player is down exactly the spread.
     */
    @Test
    void aDeskRoundTripBuysThenSellsAndConservesTheLedger() throws Exception {
        Exchange e = start();
        seedDesk(5_000, 15_000_000);
        store.login(1, 11);
        LiveWallet w = store.finder().find(1);
        Desk desk = e.desk();
        ExchangeQa.Snapshot before = ExchangeQa.Snapshot.of(e, w);
        long nx = ExchangeQa.DESK_NX;

        Response buy = previewAndConfirm(e, 1, Side.BUY, nx);
        assertTrue(buy.ok(), buy.toString());
        Response sell = previewAndConfirm(e, 1, Side.SELL, nx);
        assertTrue(sell.ok(), sell.toString());

        ExchangeQa.DeskRound round = ExchangeQa.deskRound(desk, 1, nx, before, ExchangeQa.Snapshot.of(e, w));
        assertEquals(List.of(), round.problems(), "the round trip conserves the ledger");
        assertEquals(before.nx(), round.after().nx(), "the wallet's NX is back");
        assertEquals(before.deskNx(), round.after().deskNx(), "the desk's NX is back");
        assertEquals(desk.sell(), round.buyPrice(), "the BUY paid the desk's sell quote");
        assertEquals(desk.buy(), round.sellPrice(), "the SELL got the desk's buy quote");
        assertEquals(desk.sell() - desk.buy(), round.spreadPaidPerMille(), "the cost is the spread, per 1,000 NX");
        assertEquals(0L, round.fees(), "the owner's set B has no desk fee");
        assertEquals(totalNx, store.totalNx(), "NX conserved");
        assertEquals(totalMeso, store.totalMeso(), "mesos conserved");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "the ledger reconciles");
        assertTrue(desk.fillsOf(1, 2).size() == 2, "the desk recorded both fills");
    }

    /**
     * The probe drives the chat, so the lines it waits for must be the lines the desk says: a preview per side
     * ({@code ^NX desk: buy} / {@code ^NX desk: sell}) and a fill ({@code ... mesos now ...}). The reported t-xc-1
     * failure is pinned at the end: a typed price is refused ("no price") and the next {@code @nx confirm} has nothing
     * left to confirm (TOKEN_EXPIRED), because the old probe kept the book's {@code @nx sell 100 <price>} steps.
     */
    @Test
    void theDeskChatSaysWhatTheProbeWaitsFor() throws Exception {
        Exchange e = start();
        seedDesk(5_000, 15_000_000);
        store.login(1, 11);
        LiveWallet w = store.finder().find(1);

        List<String> buy = ExchangeChat.handle(e, w, new String[]{"buy", "100"});
        assertTrue(Pattern.compile("^NX desk: buy").matcher(buy.get(0)).find(), buy.toString());
        List<String> bought = ExchangeChat.handle(e, w, new String[]{"confirm"});
        assertTrue(Pattern.compile("mesos now").matcher(String.join(" | ", bought)).find(), bought.toString());

        List<String> sell = ExchangeChat.handle(e, w, new String[]{"sell", "100"});
        assertTrue(Pattern.compile("^NX desk: sell").matcher(sell.get(0)).find(), sell.toString());
        List<String> sold = ExchangeChat.handle(e, w, new String[]{"confirm"});
        assertTrue(Pattern.compile("mesos now").matcher(String.join(" | ", sold)).find(), sold.toString());

        List<String> typed = ExchangeChat.handle(e, w, new String[]{"sell", "100", "5000"});
        assertTrue(typed.get(0).contains("no price"), "a typed price has no meaning at the desk: " + typed);
        List<String> nothing = ExchangeChat.handle(e, w, new String[]{"confirm"});
        assertTrue(String.join(" | ", nothing).contains("TOKEN_EXPIRED"), nothing.toString());
    }

    /** A wallet with less than one desk trade's mesos is refused before anything moves, and says what is needed. */
    @Test
    void theRoundTripRefusesWhenTheWalletCannotPay() throws Exception {
        store = new MemoryExchangeStore();
        store.addAccount(1, NX0);
        store.addCharacter(11, 1, 1_000);
        store.addAccount(HOUSE, 1_000_000);
        store.addCharacter(HOUSE_CHAR, HOUSE, 1_000_000_000);
        Exchange e = start();
        seedDesk(5_000, 15_000_000);
        store.login(1, 11);
        LiveWallet w = store.finder().find(1);
        long nxSeeded = store.nx(1);
        long mesoBefore = store.meso(11);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ExchangeQa.roundDesk(e, w, ExchangeQa.DESK_NX));
        assertTrue(refused.getMessage().contains("mesos"), refused.getMessage());
        assertEquals(nxSeeded, store.nx(1), "nothing moved");
        assertEquals(mesoBefore, store.meso(11), "nothing moved");
    }
}
