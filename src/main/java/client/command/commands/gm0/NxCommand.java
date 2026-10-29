package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import soloMapling.Economy.exchange.ExchangeChat;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.GameLiveWallet;

/**
 * {@code @nx}: the NX exchange in game (ticket 113, owner 2026-10-28). Preview a buy or sell, confirm it, see and
 * cancel your orders; escrow and payouts use this character while you are online ({@link ExchangeChat}).
 */
public class NxCommand extends Command {
    {
        setDescription("Trade NX and items (@nx tickers) for mesos on the exchange (@nx for help).");
    }

    /** 1.6.1: held on live: @help leaves it out while the exchange is not open here (it still answers "not open"). */
    @Override
    public boolean listed() {
        return ExchangeService.exchange() != null;
    }

    @Override
    public void execute(Client c, String[] params) {
        for (String line : ExchangeChat.handle(ExchangeService.exchange(), GameLiveWallet.of(c.getPlayer()), params)) {
            c.getPlayer().yellowMessage(line);
        }
    }
}
