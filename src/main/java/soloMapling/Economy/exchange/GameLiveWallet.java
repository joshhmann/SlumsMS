package soloMapling.Economy.exchange;

import client.BotClient;
import client.Character;
import client.Client;
import net.server.Server;
import net.server.world.World;
import server.CashShop;

import java.util.function.Supplier;

/**
 * Ticket 113 round 2: the {@link LiveWallet} of a logged-in player in the running game. The lock is the character's
 * monitor, which {@link Character#saveCharToDB(boolean)} (synchronized) also takes: every save, the autosave, the
 * channel change's and the logout's, waits while the exchange changes this wallet. Mesos change through
 * {@link Character#takeMesoExact}/{@link Character#giveMesoExact} (atomic with {@code gainMeso}), NX through
 * {@link CashShop#takeNxCredit}/{@link CashShop#giveNxCredit} (atomic with {@code gainCash}).
 */
public final class GameLiveWallet implements LiveWallet {

    private final Character chr;

    private GameLiveWallet(Character chr) {
        this.chr = chr;
    }

    /** The account's playing character (not a bot, not an owned alt), or null when it has none in a world. */
    public static LiveWallet find(long account) {
        try {
            for (World w : Server.getInstance().getWorlds()) {
                for (Character c : w.getPlayerStorage().getAllCharacters()) {
                    if (c.getAccountID() == account && isPlayer(c)) {
                        return new GameLiveWallet(c);
                    }
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** The wallet of a character the caller already has (the in-game command), or null for a bot or an alt. */
    public static LiveWallet of(Character c) {
        return c != null && isPlayer(c) ? new GameLiveWallet(c) : null;
    }

    private static boolean isPlayer(Character c) {
        Client cl = c.getClient();
        return cl != null && !(cl instanceof BotClient) && !c.isOwnedAlt() && cl.getPlayer() == c;
    }

    @Override
    public long account() {
        return chr.getAccountID();
    }

    @Override
    public long character() {
        return chr.getId();
    }

    @Override
    public <T> T locked(Supplier<T> body) {
        synchronized (chr) {
            return body.get();
        }
    }

    @Override
    public boolean usable() {
        Client cl = chr.getClient();
        if (cl == null || !chr.isLoggedin() || cl.isInTransition() || cl.getPlayer() != chr || chr.isOwnedAlt()) {
            return false;
        }
        CashShop cs = chr.getCashShop();
        if (cs == null || cs.isOpened()) {
            return false;
        }
        try {
            return cl.getChannelServer() != null
                    && cl.getChannelServer().getPlayerStorage().getCharacterById(chr.getId()) == chr;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean saveNow() {
        try {
            chr.saveCharToDB(true);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long have(Ledger.Asset asset) {
        return asset == Ledger.Asset.NX ? chr.getCashShop().getCash(1) : chr.getMeso();
    }

    @Override
    public boolean take(Ledger.Asset asset, long amount) {
        if (amount < 0 || amount > Integer.MAX_VALUE) {
            return false;
        }
        return asset == Ledger.Asset.NX ? chr.getCashShop().takeNxCredit((int) amount)
                : chr.takeMesoExact((int) amount);
    }

    @Override
    public boolean give(Ledger.Asset asset, long amount) {
        if (amount < 0 || amount > Integer.MAX_VALUE) {
            return false;
        }
        return asset == Ledger.Asset.NX ? chr.getCashShop().giveNxCredit((int) amount)
                : chr.giveMesoExact((int) amount);
    }

    @Override
    public void tell(String line) {
        try {
            chr.yellowMessage(line);
        } catch (RuntimeException ignored) {
            // the player left; the money is already in memory and saves with the character
        }
    }
}
