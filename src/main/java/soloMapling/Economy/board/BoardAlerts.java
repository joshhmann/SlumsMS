package soloMapling.Economy.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Price alerts (owner, 2026-10-26: 10 per account, an alert fires once and then waits until it is re-armed by hand).
 * Every 15 minutes each armed alert is held against the cheapest unit price in the Free Market right now (the same
 * shops {@code listings.json} lists): {@code below} fires when that price is at or under the alert's, {@code above}
 * when it is at or over. A fired alert stays quiet until the site re-arms it: the server sees it disarmed
 * ({@code armed: false}) and armed again, or the site sends an {@code armedAt} later than the firing. Pure.
 */
public final class BoardAlerts {

    public static final int MAX_ALERTS = 10;
    public static final int MAX_WATCHLIST = 20;

    private BoardAlerts() {
    }

    /** An alert that fires now, and the listing price that set it off. */
    public record Fire(Board.Alert alert, long listingPrice) {
    }

    /**
     * The alerts that fire now; each is recorded in {@code state} as fired. {@code lowest}: the cheapest unit price per
     * item id among the open FM listings. Disarmed alerts are acknowledged in {@code state}.
     */
    public static List<Fire> due(List<Board.AccountAlerts> accounts, Map<Integer, Long> lowest, BoardState state,
                                 long now) {
        List<Fire> out = new ArrayList<>();
        for (Board.AccountAlerts a : accounts) {
            int n = 0;
            for (Board.Alert al : a.alerts()) {
                if (++n > MAX_ALERTS) {
                    break;
                }
                if (!al.armed()) {
                    state.ack(al.id());
                    continue;
                }
                BoardState.Fired before = state.fired(al.id());
                boolean rearmed = before == null || before.acked() || al.armedAt() > before.firedAt();
                if (!rearmed) {
                    continue;
                }
                Long low = lowest.get(al.itemId());
                if (low == null || low <= 0) {
                    continue;
                }
                boolean hit = al.dir() == Board.Dir.BELOW ? low <= al.price() : low >= al.price();
                if (!hit) {
                    continue;
                }
                state.fire(new BoardState.Fired(al.id(), a.account(), al.itemId(), al.dir(), al.price(), now, low,
                        false));
                out.add(new Fire(al, low));
            }
        }
        return out;
    }
}
