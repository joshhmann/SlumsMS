package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6 scene focus, on lines from the test server's own !qa logs (2026-10-23): the 08:04 we-1 run, before the rc6
 * wedding crowd fix, where the guests chatted about everything but the wedding; the 13:42 run after it; a gw-1 run;
 * and the rv-2 runs where no called bot took a single mob.
 */
class SceneFocusTest {

    static final List<String> WEDDING_BEFORE = List.of(
            "# !qa we-1 - Admin (1)",
            "08:04:27.587 COUPLE WEDDING #1 begins: Skater1 & Inuyasha44, 5 guests at (138,180)",
            "08:04:45.163 BOT_CHAT PianusLuv: selling my old bow",
            "08:04:47.569 BOT_CHAT Skater1: the first part is ez",
            "08:05:09.586 BOT_CHAT IamMystic: we are gathered here today in amoria...",
            "08:05:09.587 COUPLE SAY IamMystic: we are gathered here today in amoria...",
            "08:05:13.177 BOT_CHAT iiMeso: gz!! what did u pick",
            "08:05:19.752 BOT_CHAT CorsairPika: almost to the next advance",
            "08:05:22.728 BOT_CHAT PianusLuv: so close, keep going",
            "08:05:28.002 BOT_CHAT kirbygolem: im lvl 28 can i join u",
            "08:05:33.961 BOT_CHAT PianusLuv: theres a wedding in amoria rn!",
            "08:05:37.050 BOT_CHAT kirbygolem: idk my claw is trash brb",
            "08:05:39.586 COUPLE SAY Skater1: i do",
            "08:05:48.586 COUPLE SAY Inuyasha44: yes, i do ^^",
            "08:06:01.457 BOT_CHAT iiMeso: cash shop, couldn't resist",
            "08:06:03.586 COUPLE SAY IamMystic: the rings please ^^",
            "08:06:03.589 COUPLE RINGS 1112806 on Skater1 and Inuyasha44",
            "08:06:07.792 BOT_CHAT PianusLuv: taxi it is lol",
            "08:06:18.586 COUPLE SAY IamMystic: congratulations Skater1 and Inuyasha44!! <3",
            "08:06:18.588 COUPLE MARRIED couple #-3: Skater1 & Inuyasha44",
            "08:06:52.053 BOT_CHAT PianusLuv: back from school",
            "08:06:54.884 BOT_CHAT CorsairPika: wb");

    static final List<String> WEDDING_AFTER = List.of(
            "13:42:16.730 COUPLE WEDDING #1 begins: devil85 & CreamyMilkJr, 5 guests at (200,-123)",
            "13:42:16.731 COUPLE WEDDING #1 cast: officiant KnuckleHawk; guests yetiiy, SpiritSonic, sirqueen, DopeGod",
            "13:42:25.729 BOT_CHAT yetiiy: they make a cute couple",
            "13:42:28.042 BOT_CHAT SpiritSonic: is there cake after",
            "13:42:31.331 BOT_CHAT sirqueen: ok who brought tissues",
            "13:42:46.729 BOT_CHAT DopeGod: who's catching the bouquet",
            "13:42:58.729 COUPLE SAY KnuckleHawk: we are gathered here today in amoria...",
            "13:43:00.461 BOT_CHAT SpiritSonic: i always cry at weddings lol",
            "13:43:13.729 COUPLE SAY KnuckleHawk: devil85 and CreamyMilkJr, step up to the altar",
            "13:43:28.729 COUPLE SAY devil85: i do <3",
            "13:43:34.600 BOT_CHAT yetiiy: i saw them training together at the subway lol",
            "13:43:37.729 COUPLE SAY CreamyMilkJr: yes, i do ^^",
            "13:43:52.741 COUPLE RINGS 1112806 on devil85 and CreamyMilkJr",
            "13:44:07.731 COUPLE MARRIED couple #-3: devil85 & CreamyMilkJr",
            "13:44:20.686 BOT_CHAT sirqueen: after party at the fm lol",
            "13:44:37.217 BOT_CHAT yetiiy: *claps*",
            "13:48:16.800 COUPLE WEDDING #1 guests at the end: 1 of 4 on the map; left: SpiritSonic, sirqueen, DopeGod",
            "13:48:16.801 COUPLE WEDDING #1 ends: married");

    static final List<String> GUILD_WAR = List.of(
            "13:38:28.271 GUILD_WAR WAR DECLARED Legendz vs ZakumSlayerz (#5, AutoQaOne) for 7 days; megaphone sent",
            "13:38:28.273 BOT_CHAT sairrry: ZakumSlayerz still stuck in 3rd job lol",
            "13:38:28.273 BOT_GUILD JAB sairrry [Legendz] -> [ZakumSlayerz]: ZakumSlayerz still stuck in 3rd job lol",
            "13:38:28.362 BOT_CHAT bucckimchi: Buying> Scroll for Earring for INT 60% 2m no lowball @@@@@@@@",
            "13:38:34.273 BOT_CHAT brokennny: keep talking sairrry",
            "13:38:34.690 GUILD_WAR WAR TAUNT MrShadow [ZakumSlayerz] 2-2 holds cold cradle: sent",
            "13:38:36.100 GUILD_WAR KILL sairrry [Legendz] on 100040001",
            "13:38:37.100 GUILD_WAR KILL sairrry [Legendz] on 100040001",
            "13:38:43.481 BOT_CHAT MrShadow: legendz cant even hold one map lol",
            "13:38:44.496 GUILD_WAR WAR OVER Legendz vs ZakumSlayerz #5: ended (gm), score 2-2");

    static final List<String> KS_WAR = List.of(
            "13:17:04.604 RIVALRY KS WAR ImMochi calls 3 bot(s) on AutoQaOne (iimistys, Moonxx, RavenKawaii) for 180 s [forced: caps skipped]",
            "13:18:35.008 RIVALRY KS WAR over on 44 (stopped): 3 bot(s) went back");

    @Test
    void theOldWeddingCrowdWasOffTopic() {
        SceneFocus.Wedding w = SceneFocus.wedding(SceneFocus.parseQaLog(WEDDING_BEFORE));
        assertTrue(w.found());
        assertEquals("IamMystic", w.officiant(), "found from its set lines");
        assertEquals(11, w.guestLines(), "the couple's and the officiant's own lines are not guest lines");
        assertEquals(2, w.onTopic(), "gz!! and 'theres a wedding in amoria rn!'");
        assertTrue(w.share() < SceneFocus.WEDDING_MIN_ON_TOPIC);
        assertTrue(w.inOrder(), w.order());
        assertEquals(List.of(29L, 9L, 15L, 14L), w.gaps());
        assertFalse(w.guestsKnown(), "an older log has no guests-at-the-end line");
    }

    @Test
    void theRc6WeddingCrowdStaysOnTopicButGuestsLeft() {
        SceneFocus.Wedding w = SceneFocus.wedding(SceneFocus.parseQaLog(WEDDING_AFTER));
        assertEquals("KnuckleHawk", w.officiant());
        assertEquals(8, w.guestLines());
        assertEquals(8, w.onTopic());
        assertTrue(w.guestsKnown());
        assertEquals(4, w.guests());
        assertEquals(1, w.stayed());
        assertEquals(List.of("SpiritSonic", "sirqueen", "DopeGod"), w.left());
        assertEquals(30, w.maxGapSeconds());
    }

    @Test
    void weddingTopicWords() {
        assertTrue(SceneFocus.weddingTopic("who's catching the bouquet", List.of()));
        assertTrue(SceneFocus.weddingTopic("devil85 looks so good", List.of("devil85", "x")));
        assertTrue(SceneFocus.weddingTopic("*claps*", List.of()));
        assertFalse(SceneFocus.weddingTopic("selling my old bow", List.of("Skater1", "Inuyasha44")));
        assertFalse(SceneFocus.weddingTopic("almost to the next advance", List.of()));
    }

    @Test
    void guildWarSidesKillsJabsAndTalk() {
        SceneFocus.War w = SceneFocus.guildWar(SceneFocus.parseQaLog(GUILD_WAR));
        assertTrue(w.found());
        assertEquals(Map.of("Legendz", 2, "ZakumSlayerz", 0), w.kills());
        assertEquals(Map.of("Legendz", 1, "ZakumSlayerz", 1), w.jabs());
        assertEquals(1, w.warTalk().get("Legendz"), "the JAB only, its BOT_CHAT copy is not counted twice");
        assertEquals(2, w.warTalk().get("ZakumSlayerz"), "the taunt and MrShadow's line naming the other guild");
        assertTrue(w.killsLogged());
    }

    @Test
    void noCalledBotTookAMobInTheKsWar() {
        SceneFocus.KsWar k = SceneFocus.ksWar(SceneFocus.parseQaLog(KS_WAR));
        assertEquals(List.of("iimistys", "Moonxx", "RavenKawaii"), k.called());
        assertEquals(0, k.tookAny());
        List<String> took = new java.util.ArrayList<>(KS_WAR);
        took.add(1, "13:17:10.000 AFFINITY KS Moonxx takes Zombie Mushroom from AutoQaOne");
        took.add(1, "13:17:11.000 AFFINITY KS iimistys takes Zombie Mushroom from AutoQaOne");
        assertEquals(2, SceneFocus.ksWar(SceneFocus.parseQaLog(took)).tookAny());
    }

    @Test
    void parsesAQaLogAcrossMidnight() {
        List<AutoQaLine> l = SceneFocus.parseQaLog(List.of("# header", "23:59:59.000 STAGE a", "00:00:01.000 STAGE b",
                "not a line"));
        assertEquals(2, l.size());
        assertEquals(2000, l.get(1).timeMs() - l.get(0).timeMs());
    }
}
