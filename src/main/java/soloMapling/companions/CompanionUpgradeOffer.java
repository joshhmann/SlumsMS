package soloMapling.companions;

// Ported from https://github.com/Kiwiism/cosmic_agents.git
//   src/main/java/server/agents/capabilities/trade/AgentOfferService.java @ 3c7e094357329932734a860e5a831d57add27a00
//   (scheduleLootOfferPrompt: after an item is looted, ask the owner once, after a delay, at most one ask per window,
//   and only when the owner wants to be asked)
// changed: the "is it an upgrade?" question is our own GearUpgrades scorer for the owner or a sibling alt, the ask
// waits for a plain yes instead of their pending-action flow and hands the piece over in a normal trade window
// (BotGiveTrade), and the rate limit and the on/off switch live in our per-companion settings (CompanionSettings.offers)
// rather than a per-agent memory.

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 1.6.7 alt-qol-batch group D: a companion that loots a piece better than what its owner (or a sibling alt) wears says
 * so once and hands it over when the owner answers yes. Behind {@link PartyCompanionPolicy.Feature#LOOT} and the
 * companion's own {@code offers on|off} switch, with at most one ask per {@link #OFFER_GAP_MS} and never the same
 * piece twice, so a good drop is not a stream of lines.
 *
 * <p>Who hears it: the owner (the piece is theirs to take) or, when the piece helps a sibling alt more, that piece is
 * still offered to the owner - a sibling has no way to answer, so it is only counted as an interested party for the
 * "is it an upgrade at all?" question and the handover still goes to the owner.
 */
public final class CompanionUpgradeOffer {

    /** At most one offer per companion in this window (cosmic_agents' cooldown, ours is 2 minutes). */
    public static final long OFFER_GAP_MS = 120_000L;

    /** The words that answer an offer with a yes (the confirm-drop wording, this file's own copy: different package). */
    private static final Set<String> YES = Set.of("yes", "yep", "yeah", "yea", "y", "ok", "okay", "sure", "confirm");

    /** The words that cancel an offer: anything said that is not a yes drops it, so a stale "yes" takes nothing. */
    private static final Set<String> NO = Set.of("no", "nope", "nah", "keep", "later", "dont", "don't", "stop");

    /** How the piece changes hands (the real one opens a trade window; tests collect the call). */
    @FunctionalInterface
    public interface Handover {
        boolean give(Character from, Character to, Equip piece, Consumer<String> say);
    }

    private static final Handover TRADE = (from, to, piece, say) -> BotGiveTrade.start(new BotGiveTrade.Plan(from, to,
            List.of(new Give(InventoryType.EQUIP, piece, (short) 1)), 0, "here, it's yours", say, null));

    /** An offer waiting for the owner's answer. */
    record Pending(Character alt, Character owner, Equip piece, long at) {
    }

    /** When each companion last offered something (companion id -> ms). */
    private static final Map<Integer, Long> lastOfferedAt = new ConcurrentHashMap<>();
    /** The pieces already offered (companion id -> identity set), so the same drop is never offered twice. */
    private static final Map<Integer, Set<Equip>> offered = new ConcurrentHashMap<>();
    /** The offer waiting for each owner (owner id -> offer). */
    private static final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private static volatile Handover handover = TRADE;
    private static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    private CompanionUpgradeOffer() {
    }

    /** Tests: how the piece changes hands and what "now" is (nulls restore the real ones). */
    public static void useForTest(Handover h, java.util.function.LongSupplier c) {
        handover = h == null ? TRADE : h;
        clock = c == null ? System::currentTimeMillis : c;
        forget();
    }

    /** Tests: forget every offer, cooldown and pending answer. */
    public static void forget() {
        lastOfferedAt.clear();
        offered.clear();
        pending.clear();
    }

    /**
     * A piece the companion just looted: offer it once when it helps its owner (or a sibling alt) and the owner wants
     * to be asked. {@code say} is the companion's voice; returns the line it said, or null when it said nothing.
     */
    public static String consider(Character alt, Character owner, Equip piece, Consumer<String> say) {
        if (alt == null || owner == null || piece == null || say == null) {
            return null;
        }
        if (!PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.LOOT, alt)) {
            return null; // the switch is off for it: no new lines, exactly as before
        }
        if (!CompanionSettingsStore.stored(alt).offers()) {
            return null;
        }
        if (owner.getMap() != alt.getMap() || !BotGiveTrade.canGive(alt)) {
            return null; // not standing together, or it cannot hand anything over
        }
        long now = clock.getAsLong();
        Long last = lastOfferedAt.get(alt.getId());
        if (last != null && now - last < OFFER_GAP_MS) {
            return null; // one ask per window
        }
        Set<Equip> already = offered.computeIfAbsent(alt.getId(), id -> ConcurrentHashMap.newKeySet());
        if (!already.add(piece)) {
            return null; // this exact piece was offered before
        }
        if (!helps(owner, alt, piece)) {
            return null;
        }
        lastOfferedAt.put(alt.getId(), now);
        pending.put(owner.getId(), new Pending(alt, owner, piece, now));
        String line = "found a " + name(piece) + ", better than yours, want it?";
        say.accept(line);
        return line;
    }

    /** Whether the piece is an upgrade for the owner or for one of its other alts. */
    static boolean helps(Character owner, Character alt, Equip piece) {
        try {
            if (GearUpgrades.scorer().gain(owner, piece) > 0) {
                return true;
            }
            for (OwnedAltService.Active a : OwnedAltService.getInstance().active()) {
                if (a.owner() != null && a.owner().getId() == owner.getId() && a.alt() != alt
                        && a.alt().getMap() == owner.getMap()
                        && GearUpgrades.scorer().gain(a.alt(), piece) > 0) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError noItemData) {
            return false; // cannot weigh it: nothing is promised
        }
        return false;
    }

    /**
     * The owner's line, when an offer is waiting: a yes hands the piece over and returns true; anything else (or an
     * expired offer) drops it and returns false, so ordinary chat stays chat.
     */
    public static boolean answer(Character owner, String line) {
        if (owner == null || line == null) {
            return false;
        }
        Pending p = pending.get(owner.getId());
        if (p == null) {
            return false;
        }
        String text = line.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        boolean yes = yes(text);
        boolean no = !yes && saysNo(text);
        if (!yes && !no) {
            return false; // "brb" is not an answer: the offer keeps waiting
        }
        pending.remove(owner.getId());
        if (!yes || clock.getAsLong() - p.at() > OFFER_GAP_MS) {
            return false;
        }
        return handover.give(p.alt(), p.owner(), p.piece(), null);
    }

    /** Whether an offer is waiting for this owner (a test and the card's own read). */
    public static boolean waitingFor(Character owner) {
        return owner != null && pending.containsKey(owner.getId());
    }

    static boolean yes(String text) {
        String[] words = text.split("[^a-z0-9']+");
        boolean any = false;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!YES.contains(word)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    static boolean saysNo(String text) {
        for (String word : text.split("[^a-z0-9']+")) {
            if (NO.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static String name(Equip piece) {
        try {
            String n = soloMapling.ArtificialPlayer.BotTradeSystem.TradeCategories.facts().name(piece.getItemId());
            return n == null || n.isBlank() ? "piece" : n;
        } catch (RuntimeException | LinkageError noItemData) {
            return "piece";
        }
    }
}
