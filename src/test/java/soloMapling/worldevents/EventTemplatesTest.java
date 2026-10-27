package soloMapling.worldevents;

import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTemplatesTest {

    private static EventTemplates.Result parse(String yaml) throws Exception {
        return EventTemplates.parse(new StringReader(yaml));
    }

    @Test
    void shippedFileLoadsCleanAndCoversEveryKind() throws Exception {
        EventTemplates.Result r;
        try (FileReader in = new FileReader(EventTemplates.DEFAULT_PATH)) {
            r = EventTemplates.parse(in);
        }
        assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
        Set<EventTemplate.Kind> kinds = EnumSet.noneOf(EventTemplate.Kind.class);
        for (EventTemplate t : r.templates()) {
            kinds.add(t.kind());
            assertTrue(t.minutes() >= 1 && t.minutes() <= EventTemplates.MAX_MINUTES, t.id());
            // 1.6.0: a headline gathers a crowd (the map cap, BOT_MAP_CAP 50, still applies), and so may a GM-only town
            // event (perion_invasion, owner 2026-10-26); the scheduler's small ones stay small
            assertTrue(t.botCap() <= (t.slot() == EventTemplate.Slot.SMALL ? 10 : EventTemplates.MAX_BOT_CAP),
                    t.id() + " keeps the crowd believable");
            List<EventTemplate.Reward> all = new ArrayList<>(t.rewards());
            all.addAll(t.participation());
            for (EventTemplate.Reward reward : all) {
                // Owner rule: consumables only (potions, food, cakes), no mesos, equips or scrolls; small amounts.
                assertTrue(EventTemplates.consumable(reward.itemId()), t.id() + " reward " + reward.itemId());
                assertTrue(reward.qty() <= 5, t.id() + " reward " + reward.itemId() + " stays small");
            }
            assertTrue(t.participationExpPct() <= 3, t.id() + " participation EXP stays small");
        }
        assertEquals(EnumSet.allOf(EventTemplate.Kind.class), kinds);
    }

    @Test
    void readsAFullTemplate() throws Exception {
        EventTemplates.Result r = parse("""
                templates:
                  - id: inv
                    kind: invasion
                    name: Test Invasion
                    weight: 2.5
                    minutes: 15
                    cooldown_minutes: 60
                    levels: [20, 40]
                    bot_cap: 4
                    maps: [100, 200]
                    waves:
                      - {mob: 1, count: 5}
                      - {mob: 2, count: 6}
                    boss: 3
                    rewards:
                      - {item: 2000004, qty: 2, chance: 0.5}
                """);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        EventTemplate t = r.templates().get(0);
        assertEquals(EventTemplate.Kind.INVASION, t.kind());
        assertEquals(2.5, t.weight());
        assertEquals(15 * 60_000L, t.durationMs());
        assertEquals(20, t.minLevel());
        assertEquals(40, t.maxLevel());
        assertEquals(2, t.spots().size());
        assertEquals(2, t.waves().size());
        assertEquals(3, t.bossMobId());
        assertEquals(0.5, t.rewards().get(0).chance());
        assertTrue(t.enabled());
    }

    @Test
    void shippedInvasionsCallOnlyTheirOwnBandAndHenesysEndsHard() throws Exception {
        EventTemplates.Result r;
        try (FileReader in = new FileReader(EventTemplates.DEFAULT_PATH)) {
            r = EventTemplates.parse(in);
        }
        // Owner 2026-10-26: a lv 70 ignores a mushroom invasion; the derived band (henesys 17-70) drew bots that never died
        for (EventTemplate t : r.templates()) {
            if (t.kind() == EventTemplate.Kind.INVASION) {
                assertTrue(t.minLevel() > 0 && t.maxLevel() <= 40, t.id() + " band " + t.minLevel() + "-" + t.maxLevel());
            }
        }
        EventTemplate henesys = r.templates().stream().filter(t -> t.id().equals("henesys_invasion")).findFirst()
                .orElseThrow();
        // Owner 2026-10-27 ("there should be madness, even lower levels should get in"): the town and mushroom
        // invasions take lv 1-40 and Henesys calls a crowd; Boar Stampede keeps the boars' own band (a far field)
        assertEquals(1, henesys.minLevel());
        assertEquals(40, henesys.maxLevel());
        assertTrue(henesys.botCap() > 40 && henesys.botCap() <= EventTemplates.MAX_BOT_CAP, "a crowd under the cap");
        for (String id : java.util.List.of("perion_invasion", "mushroom_invasion")) {
            EventTemplate t = r.templates().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertEquals(1, t.minLevel(), id);
            assertEquals(40, t.maxLevel(), id);
        }
        EventTemplate boars = r.templates().stream().filter(x -> x.id().equals("boar_stampede")).findFirst()
                .orElseThrow();
        assertEquals(20, boars.minLevel());
        assertEquals(10, boars.botCap(), "a small event at its ceiling");
        EventTemplate.Wave last = henesys.waves().get(henesys.waves().size() - 1);
        assertEquals(6300005, last.miniBossId(), "the last wave is led by Zombie Mushmom");
        assertEquals(8130100, henesys.bossMobId(), "then Jr. Balrog");
        for (EventTemplate.Wave w : henesys.waves().subList(0, henesys.waves().size() - 1)) {
            assertEquals(0, w.miniBossId(), "only the last wave brings a mini-boss");
        }
    }

    // Owner 2026-10-27 ("It needs to be harder mobs"): ~100 lv 1-40 bots cleared the mushroom waves in ~92 s and
    // none died, so Henesys is now invaded by lv 40-65 field mobs and a Jr. Balrog, each wave tougher than the last.
    @Test
    void henesysInvasionBringsHarderMobsEachWaveAndABigBoss() throws Exception {
        EventTemplate henesys = shipped("henesys_invasion");
        List<Integer> mobs = new ArrayList<>();
        for (EventTemplate.Wave w : henesys.waves()) {
            mobs.add(w.mobId());
            assertTrue(w.count() >= 12 && w.count() <= EventTemplates.MAX_WAVE_COUNT, "a crowd of " + w.mobId());
        }
        assertEquals(List.of(4230101, 5130100, 5130102, 6230100), mobs,
                "Zombie Lupin, Drake, Dark Stone Golem, Wild Kargo");
        assertTrue(henesys.bossHp() >= 150_000 && henesys.bossHp() <= EventTemplates.MAX_BOSS_HP,
                "the boss lasts for a crowd: " + henesys.bossHp());
        // the other headline event is untouched
        EventTemplate cake = shipped("giant_cake");
        assertEquals(9400606, cake.bossMobId());
        assertEquals(4_000_000L, cake.bossHp());
    }

    @Test
    void henesysInvadersExistInTheServersMobDataAtTheirLevels() throws Exception {
        soloMapling.WzTestData.assumePresent("Mob.wz");
        EventTemplate henesys = shipped("henesys_invasion");
        int previous = 0;
        for (EventTemplate.Wave w : henesys.waves()) {
            assertTrue(server.life.LifeFactory.monsterExists(w.mobId()), "Mob.wz has " + w.mobId());
            int level = server.life.LifeFactory.getMonsterLevel(w.mobId());
            assertTrue(level >= 40 && level <= 70, w.mobId() + " is lv " + level);
            assertTrue(level > previous, "each wave is tougher: " + w.mobId() + " lv " + level);
            previous = level;
            if (w.miniBossId() > 0) {
                assertTrue(server.life.LifeFactory.monsterExists(w.miniBossId()), "Mob.wz has " + w.miniBossId());
            }
        }
        assertTrue(server.life.LifeFactory.monsterExists(henesys.bossMobId()));
        assertTrue(server.life.LifeFactory.monsterIsBoss(henesys.bossMobId()), "Jr. Balrog is a boss");
        assertTrue(server.life.LifeFactory.getMonsterLevel(henesys.bossMobId()) >= 70, "a boss above the waves");
    }

    private static EventTemplate shipped(String id) throws Exception {
        EventTemplates.Result r;
        try (FileReader in = new FileReader(EventTemplates.DEFAULT_PATH)) {
            r = EventTemplates.parse(in);
        }
        assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
        return r.templates().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aWaveMayBringAMiniBossOnlyInAnInvasion() throws Exception {
        EventTemplates.Result r = parse("""
                templates:
                  - id: inv
                    kind: invasion
                    maps: [100]
                    waves:
                      - {mob: 1, count: 5}
                      - {mob: 2, count: 6, mini_boss: 9}
                    boss: 3
                  - id: cake
                    kind: giant_cake
                    maps: [100]
                    boss: 3
                    waves:
                      - {mob: 2, count: 6, mini_boss: 9}
                  - id: neg
                    kind: invasion
                    maps: [100]
                    waves:
                      - {mob: 2, count: 6, mini_boss: -4}
                    boss: 3
                """);
        assertEquals(List.of("inv"), r.templates().stream().map(EventTemplate::id).toList());
        EventTemplate t = r.templates().get(0);
        assertEquals(0, t.waves().get(0).miniBossId());
        assertEquals(9, t.waves().get(1).miniBossId());
        String all = String.join("\n", r.errors());
        assertTrue(all.contains("cake") && all.contains("mini_boss only goes with an invasion wave"), all);
        assertTrue(all.contains("neg") && all.contains("bad mini_boss"), all);
    }

    @Test
    void badTemplatesAreSkippedAndReportedTheRestLoad() throws Exception {
        EventTemplates.Result r = parse("""
                templates:
                  - {id: ok, kind: hot_spot, maps: [1], exp_bonus: 1.5}
                  - {id: weird, kind: party, maps: [1]}
                  - {id: noboss, kind: invasion, maps: [1], waves: [{mob: 1, count: 2}]}
                  - {id: rates, kind: hot_spot, maps: [1], exp_bonus: 3}
                  - {id: long, kind: hot_spot, maps: [1], exp_bonus: 1.2, minutes: 600}
                  - {id: ok, kind: hot_spot, maps: [2], exp_bonus: 1.5}
                  - {id: paid, kind: hot_spot, maps: [1], exp_bonus: 1.5, rewards: [{item: 2000004, qty: 1}]}
                  - {id: nomob, kind: boss_sighting, spots: [{map: 1}]}
                  - {id: noitem, kind: wandering_npc, npc: 5, spots: [{map: 1}]}
                  - {id: nomap, kind: treasure_hunt, npc: 5}
                  - {id: chest, kind: treasure_hunt, maps: [1]}
                """);
        assertEquals(1, r.templates().size(), r.templates().toString());
        assertEquals("ok", r.templates().get(0).id());
        String all = String.join("\n", r.errors());
        for (String bad : new String[]{"weird", "noboss", "rates", "long", "ok: duplicate", "paid", "nomob",
                "noitem", "nomap", "chest"}) {
            assertTrue(all.contains(bad), "reported " + bad + " in\n" + all);
        }
    }

    @Test
    void readsAHeadlineCakeWithItsRewardTables() throws Exception {
        EventTemplates.Result r = parse("""
                templates:
                  - id: cake
                    kind: giant_cake
                    slot: headline
                    weight: 3
                    minutes: 30
                    bot_cap: 40
                    maps: [100000000]
                    boss: 9400606
                    boss_hp: 4000000
                    waves:
                      - {mob: 9400607, count: 12}
                    rewards:
                      - {item: 2020032, qty: 5}
                    participation:
                      - {item: 2020032, qty: 2, chance: 0.5}
                    participation_exp_pct: 2
                """);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        EventTemplate t = r.templates().get(0);
        assertEquals(EventTemplate.Kind.GIANT_CAKE, t.kind());
        assertEquals(EventTemplate.Slot.HEADLINE, t.slot());
        assertEquals(4_000_000L, t.bossHp());
        assertEquals(40, t.botCap());
        assertEquals(List.of(new EventTemplate.Reward(2020032, 5, 1.0)), t.rewards());
        assertEquals(List.of(new EventTemplate.Reward(2020032, 2, 0.5)), t.participation());
        assertEquals(2.0, t.participationExpPct());
        assertTrue(t.bossFight());
    }

    @Test
    void slotDefaultsSmallOrManualByWeight() throws Exception {
        EventTemplates.Result r = parse("""
                templates:
                  - {id: a, kind: hot_spot, maps: [1], exp_bonus: 1.5, weight: 2}
                  - {id: b, kind: hot_spot, maps: [1], exp_bonus: 1.5, weight: 0}
                """);
        assertEquals(EventTemplate.Slot.SMALL, r.templates().get(0).slot());
        assertEquals(EventTemplate.Slot.MANUAL, r.templates().get(1).slot());
    }

    @Test
    void rewardTablesRefuseAnythingButSmallConsumables() throws Exception {
        EventTemplates.Result r = parse("""
                templates:
                  - {id: sword, kind: invasion, maps: [1], waves: [{mob: 1, count: 2}], boss: 3, rewards: [{item: 1302000, qty: 1}]}
                  - {id: scroll, kind: invasion, maps: [1], waves: [{mob: 1, count: 2}], boss: 3, rewards: [{item: 2040001, qty: 1}]}
                  - {id: hotpart, kind: hot_spot, maps: [1], exp_bonus: 1.5, participation: [{item: 2000004, qty: 1}]}
                  - {id: bigexp, kind: giant_cake, maps: [1], boss: 3, participation_exp_pct: 50}
                  - {id: bighp, kind: giant_cake, maps: [1], boss: 3, boss_hp: 999999999}
                  - {id: noboss, kind: giant_cake, maps: [1]}
                  - {id: slotty, kind: giant_cake, maps: [1], boss: 3, slot: evening}
                  - {id: crowd, kind: giant_cake, maps: [1], boss: 3, bot_cap: 250}
                  - {id: fine, kind: giant_cake, maps: [1], boss: 3, participation: [{item: 2022161, qty: 1}]}
                """);
        assertEquals(List.of("fine"), r.templates().stream().map(EventTemplate::id).toList(), r.errors().toString());
        String all = String.join("\n", r.errors());
        for (String bad : new String[]{"sword", "scroll", "hotpart", "bigexp", "bighp", "noboss", "slotty", "crowd"}) {
            assertTrue(all.contains(bad), "reported " + bad + " in\n" + all);
        }
    }

    @Test
    void disabledTemplatesStillLoad() throws Exception {
        EventTemplates.Result r = parse("""
                templates:
                  - {id: off, kind: hot_spot, maps: [1], exp_bonus: 1.5, enabled: false}
                """);
        assertTrue(r.errors().isEmpty());
        assertEquals(false, r.templates().get(0).enabled());
    }

    @Test
    void noTemplatesListIsAnError() throws Exception {
        assertTrue(!parse("foo: 1\n").errors().isEmpty());
    }
}
