package soloMapling.worldevents;

import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.StringReader;
import java.util.EnumSet;
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
            assertTrue(t.botCap() <= 10, t.id() + " keeps the crowd believable");
            for (EventTemplate.Reward reward : t.rewards()) {
                // Owner rule for the first set: consumables only (potions), no mesos, equips or scrolls.
                assertEquals(200, reward.itemId() / 10_000, t.id() + " reward " + reward.itemId());
            }
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
