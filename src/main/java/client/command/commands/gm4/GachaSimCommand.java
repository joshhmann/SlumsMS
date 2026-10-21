package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.ItemInformationProvider;
import server.gachapon.Gachapon;
import soloMapling.Gachapon.GachaPools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * 1.5.0 gachapon, GM test: rolls a machine N times without giving anything and prints what came out against the
 * file's odds.
 * !gachasim <town> [n]   - n rolls (default 10,000, at most 1,000,000)
 * !gachasim reload       - re-reads gachapon-pools.yaml (the old pools stay if the new file does not load)
 */
public class GachaSimCommand extends Command {
    {
        setDescription("Simulate gachapon rolls: !gachasim <town> [n] | !gachasim reload");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length > 0 && params[0].equalsIgnoreCase("reload")) {
            GachaPools p = GachaPools.reload();
            player.yellowMessage("Gachapon pools: " + p.towns().size() + " machines, " + p.problems().size()
                    + " problems" + (p.towns().isEmpty() ? " (NOT loaded, the old pools stay)" : ""));
            p.problems().stream().limit(10).forEach(s -> player.yellowMessage("  " + s));
            return;
        }
        GachaPools pools = GachaPools.get();
        GachaPools.Town town = params.length == 0 ? null : pools.find(params[0]);
        if (town == null) {
            player.yellowMessage("Use !gachasim <town> [n]. Towns: " + String.join(", ", pools.towns().keySet()));
            return;
        }
        int n = params.length > 1 && isInteger(params[1]) ? Math.min(1_000_000, Math.max(1, Integer.parseInt(params[1])))
                : 10_000;
        GachaPools.Simulation sim = pools.simulate(town, n, ThreadLocalRandom.current());
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        player.yellowMessage(String.format("%s: %,d rolls", town.name(), sim.rolls()));
        for (GachaPools.TierOdds t : pools.odds(town)) {
            int got = sim.tiers().getOrDefault(t.tier(), 0);
            player.yellowMessage(String.format("  %-8s %,8d = %s (file: %s)", t.tier().key(), got,
                    Gachapon.pct((double) got / sim.rolls()), Gachapon.pct(t.chance())));
            if (t.tier() == GachaPools.Tier.RARE || t.tier() == GachaPools.Tier.JACKPOT) {
                List<Map.Entry<Integer, Integer>> hits = new ArrayList<>();
                for (GachaPools.ItemOdds io : t.items()) {
                    Integer k = sim.items().get(io.itemId());
                    if (k != null) {
                        hits.add(Map.entry(io.itemId(), k));
                    }
                }
                hits.sort((a, b) -> b.getValue() - a.getValue());
                StringBuilder sb = new StringBuilder("     ");
                for (int i = 0; i < Math.min(6, hits.size()); i++) {
                    sb.append(ii.getName(hits.get(i).getKey())).append(" x").append(hits.get(i).getValue()).append("; ");
                }
                player.yellowMessage(sb.toString());
            }
        }
    }
}
