package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.Ledger.Asset;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/** A trader bot's holdings in memory (tests and the offline simulation): what BotTraderWallet is in the game. */
public final class FakeBotWallet implements LiveWallet {
    public final long id;
    public long mesos;
    public final Map<Asset, Long> items = new EnumMap<>(Asset.class);
    public boolean online = true;
    public boolean saveFails;
    public long bagRoom = Long.MAX_VALUE;
    public int saves;

    public FakeBotWallet(long id, long mesos) {
        this.id = id;
        this.mesos = mesos;
    }

    public FakeBotWallet with(Asset a, long n) {
        items.put(a, n);
        return this;
    }

    @Override
    public long account() {
        return id;
    }

    @Override
    public long character() {
        return id;
    }

    @Override
    public synchronized <T> T locked(Supplier<T> body) {
        return body.get();
    }

    @Override
    public boolean usable() {
        return online;
    }

    @Override
    public boolean saveNow() {
        saves++;
        return !saveFails;
    }

    @Override
    public long have(Asset a) {
        return a == Asset.NX ? 0 : a.item() ? items.getOrDefault(a, 0L) : mesos;
    }

    @Override
    public boolean take(Asset a, long n) {
        if (a == Asset.NX || n < 0 || have(a) < n) {
            return false;
        }
        if (a.item()) {
            items.merge(a, -n, Long::sum);
        } else {
            mesos -= n;
        }
        return true;
    }

    @Override
    public boolean give(Asset a, long n) {
        if (a == Asset.NX || n < 0) {
            return false;
        }
        if (a.item()) {
            if (n > bagRoom) {
                return false;
            }
            items.merge(a, n, Long::sum);
            if (bagRoom != Long.MAX_VALUE) {
                bagRoom -= n;
            }
            return true;
        }
        if (mesos + n > Integer.MAX_VALUE) {
            return false;
        }
        mesos += n;
        return true;
    }

    @Override
    public long room(Asset a, long want) {
        return a.item() ? Math.max(0, Math.min(want, bagRoom)) : LiveWallet.super.room(a, want);
    }
}
