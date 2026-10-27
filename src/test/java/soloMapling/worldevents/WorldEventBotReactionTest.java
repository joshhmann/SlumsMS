package soloMapling.worldevents;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Need;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePolicy;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfile;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 world events, the bots' side: the brain's EVENT activity leans by Bartle type (Killers and Achievers rush an
 * invasion, Socializers the cake, Explorers a treasure hunt), committed bots stay, the crowd cap holds, and on an
 * invasion map bots really die (with a cooldown and a per-event cap).
 */
class WorldEventBotReactionTest {

    static final int BOT = 1_000_000_123;

    @BeforeEach
    void clean() {
        WorldEventSteer.clearForTest();
        WorldEvents.getInstance().resetForTest();
        LifePolicy.addBias(WorldEventBias.INSTANCE);
    }

    @AfterEach
    void after() {
        LifePolicy.removeBias(WorldEventBias.INSTANCE);
        WorldEventSteer.clearForTest();
        WorldEvents.getInstance().resetForTest();
    }

    static SocialProfile corner(SocialProfile.Type type) {
        return new SocialProfile(type.cornerX * 0.9, type.cornerY * 0.9);
    }

    @Test
    void eachTypeLeansToItsKindOfEvent() {
        SocialProfile achiever = corner(SocialProfile.Type.ACHIEVER);
        SocialProfile explorer = corner(SocialProfile.Type.EXPLORER);
        SocialProfile socializer = corner(SocialProfile.Type.SOCIALIZER);
        SocialProfile killer = corner(SocialProfile.Type.KILLER);
        EventTemplate.Kind inv = EventTemplate.Kind.INVASION;
        assertTrue(WorldEventBias.lean(inv, killer) > WorldEventBias.lean(inv, socializer));
        assertTrue(WorldEventBias.lean(inv, achiever) > WorldEventBias.lean(inv, explorer));
        assertTrue(WorldEventBias.lean(EventTemplate.Kind.GIANT_CAKE, socializer)
                > WorldEventBias.lean(EventTemplate.Kind.GIANT_CAKE, explorer));
        assertTrue(WorldEventBias.lean(EventTemplate.Kind.TREASURE_HUNT, explorer)
                > WorldEventBias.lean(EventTemplate.Kind.TREASURE_HUNT, killer));
        for (SocialProfile p : List.of(achiever, explorer, socializer, killer)) {
            for (EventTemplate.Kind k : EventTemplate.Kind.values()) {
                double lean = WorldEventBias.lean(k, p);
                assertTrue(lean >= WorldEventBias.BASE && lean <= LifePolicy.MAX_PLUGIN_BIAS + 1e-9, k + " " + lean);
            }
        }
        // the brain-off path rolls the same lean once
        assertTrue(WorldEventBias.joinChance(inv, killer) > WorldEventBias.joinChance(inv, explorer));
        assertTrue(WorldEventBias.joinChance(inv, explorer) >= 0.1 && WorldEventBias.joinChance(inv, killer) <= 0.9);
    }

    /** One decision for a bot at this point with these needs; EVENT is eligible only with an offer (as the host). */
    static LifeDecision.Result decide(SocialProfile social, double boredom, double progress, double social_,
                                      boolean offered, boolean commitment) {
        Map<Need, Double> needs = new EnumMap<>(Need.class);
        needs.put(Need.BOREDOM, boredom);
        needs.put(Need.PROGRESS, progress);
        needs.put(Need.SOCIAL, social_);
        needs.put(Need.SUPPLIES, 0.0);
        needs.put(Need.FATIGUE, 0.0);
        needs.put(Need.BAG_SPACE, 0.0);
        Map<Activity, String> eligible = new EnumMap<>(Activity.class);
        eligible.put(Activity.GRIND, "grind");
        eligible.put(Activity.MOVE_MAP, "move");
        if (offered) {
            eligible.put(Activity.EVENT, "offered");
        }
        // a grind a third of the way through: its stickiness still counts (as on TEST, where GRIND scores 1.6-2.5)
        LifeDecision.Current current = new LifeDecision.Current(Activity.GRIND, 10_000_000 - 200_000, 600_000,
                LifePolicy.STICKINESS);
        var context = new LifePolicy.Context(new LifeDecision.Needs(needs), eligible, 0, 0, 0, false, false,
                commitment, current, List.of(), List.of());
        var situation = new LifePolicy.Situation(social, false, false, 0, false, null, 0, BOT);
        LifeProfile profile = LifeProfile.fromSocial(social, BOT);
        return LifeDecision.evaluate(context.needs(), LifePolicy.options(context, profile, situation), current,
                10_000_000, LifePolicy.INTERRUPT_MARGIN, 0, a -> 0, commitment);
    }

    /** Over a grid of moods, how often the bot drops its grind for an event of this kind. */
    static double share(SocialProfile social, EventTemplate.Kind kind) {
        WorldEventSteer.offer(BOT, 7, 100000000, kind, System.currentTimeMillis());
        int picked = 0;
        int total = 0;
        for (double boredom = 0; boredom <= 1.0001; boredom += 0.1) {
            for (double progress = 0; progress <= 1.0001; progress += 0.1) {
                for (double soc = 0; soc <= 1.0001; soc += 0.25) {
                    total++;
                    if (decide(social, boredom, progress, soc, true, false).selected().orElse(null) == Activity.EVENT) {
                        picked++;
                    }
                }
            }
        }
        return picked / (double) total;
    }

    @Test
    void theBrainPicksEventWithTheRightBartleLeanings() {
        SocialProfile achiever = corner(SocialProfile.Type.ACHIEVER);
        SocialProfile explorer = corner(SocialProfile.Type.EXPLORER);
        SocialProfile socializer = corner(SocialProfile.Type.SOCIALIZER);
        SocialProfile killer = corner(SocialProfile.Type.KILLER);
        double killerInvasion = share(killer, EventTemplate.Kind.INVASION);
        double achieverInvasion = share(achiever, EventTemplate.Kind.INVASION);
        double explorerInvasion = share(explorer, EventTemplate.Kind.INVASION);
        assertTrue(killerInvasion > explorerInvasion + 0.1, killerInvasion + " vs " + explorerInvasion);
        assertTrue(achieverInvasion > explorerInvasion + 0.05, achieverInvasion + " vs " + explorerInvasion);
        assertTrue(killerInvasion > 0.5, "a Killer rushes an invasion in most moods: " + killerInvasion);
        double socializerCake = share(socializer, EventTemplate.Kind.GIANT_CAKE);
        double explorerCake = share(explorer, EventTemplate.Kind.GIANT_CAKE);
        assertTrue(socializerCake > explorerCake + 0.2, socializerCake + " vs " + explorerCake);
        double explorerTreasure = share(explorer, EventTemplate.Kind.TREASURE_HUNT);
        double killerTreasure = share(killer, EventTemplate.Kind.TREASURE_HUNT);
        assertTrue(explorerTreasure > killerTreasure + 0.2, explorerTreasure + " vs " + killerTreasure);
        // nobody always goes, nobody never goes: it is a pull, not an order
        for (double s : new double[]{killerInvasion, explorerInvasion, socializerCake, explorerTreasure}) {
            assertTrue(s > 0 && s < 0.98, String.valueOf(s));
        }
    }

    @Test
    void noOfferNoEventAndCommittedBotsStay() {
        SocialProfile killer = corner(SocialProfile.Type.KILLER);
        // a bored killer without an offer: EVENT is not even a choice, and the plug-in adds nothing
        assertFalse(decide(killer, 1, 0, 1, false, false).ranking().stream()
                .anyMatch(s -> s.activity() == Activity.EVENT));
        assertEquals(0, WorldEventBias.INSTANCE.bonus(Activity.EVENT, null,
                new LifePolicy.Situation(killer, false, false, 0, false, null, 0, BOT)));
        // with an offer, the same bot goes...
        WorldEventSteer.offer(BOT, 7, 100000000, EventTemplate.Kind.INVASION, System.currentTimeMillis());
        assertEquals(Activity.EVENT, decide(killer, 1, 0, 1, true, false).selected().orElseThrow());
        // ...unless it is committed (a trade, a party with a player, a conversation): it holds what it does
        var held = decide(killer, 1, 0, 1, true, true);
        assertEquals(LifeDecision.Outcome.HOLD_COMMITMENT, held.outcome());
        assertEquals(Activity.GRIND, held.selected().orElseThrow());
    }

    @Test
    void offersLapseAndGoWithTheirEvent() {
        long now = 1_000_000;
        WorldEventSteer.offer(BOT, 7, 100, EventTemplate.Kind.INVASION, now);
        assertTrue(WorldEventSteer.offerFor(BOT, now + 60_000) != null);
        assertNull(WorldEventSteer.offerFor(BOT, now + WorldEventSteer.OFFER_MS + 1), "an unanswered offer lapses");
        WorldEventSteer.offer(BOT, 7, 100, EventTemplate.Kind.INVASION, now);
        WorldEventSteer.release(7);
        assertNull(WorldEventSteer.offerFor(BOT, now));
    }

    static ActiveEvent event(int id, int cap) {
        EventTemplate t = new EventTemplate("cake", EventTemplate.Kind.GIANT_CAKE, "Giant Cake", 3, 30, 600, 1, 200,
                cap, List.of(EventTemplate.Spot.map(100000000)), List.of(), 9400606, 0, 1, List.of(), true,
                EventTemplate.Slot.HEADLINE, 1000, List.of(), 0);
        return new ActiveEvent(id, t, t.spots().get(0), 1, System.currentTimeMillis(), "test", 1, 200);
    }

    static Character bot(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getMapId()).thenReturn(100000000);
        when(chr.getName()).thenReturn("Bot" + id);
        when(chr.getLevel()).thenReturn(30);
        return chr;
    }

    @Test
    void theCrowdCapHoldsWhateverTheBrainsPick() {
        WorldEvents w = WorldEvents.getInstance();
        ActiveEvent e = event(41, 3);
        w.addActiveForTest(e);
        long now = System.currentTimeMillis();
        int took = 0;
        for (int i = 0; i < 10; i++) {
            Character chr = bot(BOT + i);
            WorldEventSteer.offer(chr.getId(), e.id, e.mapId(), e.template.kind(), now);
            boolean may = w.mayAccept(chr, now);
            boolean ok = w.accept(chr, now);
            assertEquals(may, ok, "mayAccept says what accept does");
            if (ok) {
                took++;
                assertEquals(e.mapId(), WorldEventSteer.mapFor(chr.getId()), "an accepted bot is steered there");
            } else {
                assertEquals(-1, WorldEventSteer.mapFor(chr.getId()));
            }
        }
        assertEquals(3, took);
        assertEquals(3, e.botsJoined());
        assertFalse(w.accept(bot(BOT + 99), now), "no offer, no join");
        // the -D cap and the 50 ceiling apply on top of the template's
        assertEquals(3, WorldEvents.crowdCap(e.template));
        assertEquals(EventTemplates.MAX_BOT_CAP, WorldEvents.crowdCap(event(42, 500).template));
    }

    @Test
    void invasionDeathsHaveACooldownAndACap() {
        long now = 10_000_000;
        assertTrue(WorldEvents.swarmRoll(0, now, 0, 0, 0.1), "a heavy hit under the roll kills");
        assertFalse(WorldEvents.swarmRoll(0, now, 0, 0, 0.9), "not every heavy hit kills");
        assertFalse(WorldEvents.swarmRoll(now - 10_000, now, 0, 0, 0.1), "per-bot cooldown");
        assertTrue(WorldEvents.swarmRoll(now - WorldEvents.SWARM_BOT_COOLDOWN_MS, now, 0, 0, 0.1));
        assertFalse(WorldEvents.swarmRoll(0, now, WorldEvents.SWARM_DEATHS_PER_EVENT, 0, 0.1), "per-event cap");
        assertTrue(WorldEvents.lowAfterHit(400, 1000), "under half is a heavy hit");
        assertFalse(WorldEvents.lowAfterHit(600, 1000));
        assertFalse(WorldEvents.lowAfterHit(0, 0));
        // with no invasion running, nothing changes for anyone (one empty-set check)
        assertNull(WorldEvents.swarmDeath(bot(BOT), now, new Random(1)));
        assertFalse(WorldEvents.swarmed(bot(BOT), 1));
    }

    @Test
    void theWinnerIsTheRealPlayerWithTheMostDamage() {
        Map<Integer, Long> damage = Map.of(1_000_000_001, 90_000L, 5, 30_000L, 6, 45_000L, 7, 45_000L);
        WorldEvents.Winner w = WorldEvents.topReal(damage, id -> id < 1_000_000_000);
        assertEquals(6, w.charId(), "a bot's damage never wins; a tie goes to the lower id");
        assertEquals(45_000L, w.damage());
        assertNull(WorldEvents.topReal(Map.of(1_000_000_001, 5L), id -> id < 1_000_000_000));
    }

    @Test
    void participationExpIsASmallShareOfALevel() {
        int lv10 = WorldEvents.participationExp(10, 2);
        int lv100 = WorldEvents.participationExp(100, 2);
        assertTrue(lv10 > 0 && lv100 > lv10, lv10 + " " + lv100);
        assertEquals(Math.round(constants.game.ExpTable.getExpNeededForLevel(100) * 0.02), lv100);
        assertEquals(0, WorldEvents.participationExp(100, 0));
        assertEquals(0, WorldEvents.participationExp(200, 2), "nothing at the cap");
        assertEquals(WorldEvents.participationExp(50, EventTemplates.MAX_PARTICIPATION_EXP_PCT),
                WorldEvents.participationExp(50, 99), "never more than the cap");
    }

    static server.life.Monster mob(int oid, int x, int y) {
        server.life.Monster m = mock(server.life.Monster.class);
        when(m.getObjectId()).thenReturn(oid);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new java.awt.Point(x, y));
        return m;
    }

    @Test
    void atAnEventABotHuntsTheNearestInvaderAnywhereOnTheMap() {
        // TEST ev-2: the roam seek box (900 x 140 px) missed every invader on the other street tiers, so the bots
        // stood still ~750 px away; at an event they now chase the nearest one wherever it is
        WorldEvents w = WorldEvents.getInstance();
        ActiveEvent e = event(43, 40);
        w.addActiveForTest(e);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        when(map.getChannelId()).thenReturn(1);
        var far = mob(501, 1500, 0);
        var high = mob(502, 300, -400);   // 300 px along, 400 px up: costs (300, 800)
        var near = mob(503, 700, 0);      // 700 px along the same street
        var players = mob(504, 120, 0);   // nearest, but a real player's (KS manners)
        for (var m : List.of(far, high, near, players)) {
            e.mobOids.add(m.getObjectId());
            when(map.getMonsterByOid(m.getObjectId())).thenReturn(m);
        }
        Character chr = bot(BOT);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(new java.awt.Point(0, 0));
        assertEquals(near, WorldEvents.nearestEventMob(chr, m -> m != players));
        assertEquals(players, WorldEvents.nearestEventMob(chr, m -> true));
        assertNull(WorldEvents.nearestEventMob(chr, m -> false));
        assertTrue(WorldEvents.isEventMob(chr, far));
        assertFalse(WorldEvents.isEventMob(chr, mob(999, 0, 0)), "a map's own mob is not the event's");
        assertTrue(WorldEvents.chaseCost(new java.awt.Point(0, 0), new java.awt.Point(0, 100))
                > WorldEvents.chaseCost(new java.awt.Point(0, 0), new java.awt.Point(100, 0)), "a climb costs more");
    }
}
