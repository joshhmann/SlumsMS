package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * b241 (the owner's TEST playtest 10-31): the staging GM's chat gets each line at most once per stage, and once per
 * thing rather than once per bot. These are the lines from his run shape in Server/BotLog.txt, 02:06:05 to 02:07:40
 * (the bonus stage): seven "gives up on Horny Mushroom" lines from several bots and mob positions were what he called
 * "kept getting messages until everyone left".
 */
class StagingChatTest {

    /** Every line the chat relayed, in order. */
    static final class Out implements java.util.function.Consumer<String> {
        final List<String> lines = new ArrayList<>();

        @Override
        public void accept(String line) {
            lines.add(line);
        }
    }

    static StagingChat chat(Out out, AtomicInteger stage) {
        return new StagingChat(out, stage::get);
    }

    @Test
    void aBotGivingUpOnTheSameMobIsOneLineInTheChat() {
        Out out = new Out();
        AtomicInteger stage = new AtomicInteger(5);
        StagingChat chat = chat(out, stage);
        chat.accept("RUN stage 5 cleared in 51 s (0 wrong tries shown)");
        chat.accept("FIGHT VegetaBlack gives up on Green Mushroom at -172,-106 for 10 s (no way closer in 3 s)");
        chat.accept("FIGHT VegetaBlack gives up on Horny Mushroom at 202,-286 for 10 s (no way closer in 3 s)");
        chat.accept("FIGHT VegetaBlack gives up on Horny Mushroom at 128,-286 for 10 s (no way closer in 3 s)");
        chat.accept("FIGHT VegetaBlack gives up on Horny Mushroom at 61,-286 for 10 s (no way closer in 3 s)");
        chat.accept("FIGHT Dagger22 gives up on Horny Mushroom at 211,-466 for 10 s (no way closer in 3 s)");
        chat.accept("FIGHT VegetaBlack gives up on Horny Mushroom at 248,-106 for 10 s (no way closer in 3 s)");
        chat.finish();
        assertEquals(List.of(
                "RUN stage 5 cleared in 51 s (0 wrong tries shown)",
                "FIGHT VegetaBlack gives up on Green Mushroom at -172,-106 for 10 s (no way closer in 3 s)",
                "FIGHT VegetaBlack gives up on Horny Mushroom at 202,-286 for 10 s (no way closer in 3 s)",
                "4 repeat(s) held back this stage (the log has every line)"),
                out.lines, "one line per mob: five more reports of Horny Mushroom, from two bots, are held back");
    }

    @Test
    void everyBotStallingOnTheSamePassIsOneLine() {
        Out out = new Out();
        AtomicInteger stage = new AtomicInteger(5);
        StagingChat chat = chat(out, stage);
        chat.accept("STALL iiDrakez on 0/2147483647 x 4001008: +0 in 90 s (under 2 a minute): etc 46 free of 96, "
                + "0 on the floor within 600 px (0 it may take), at -210,-2775, level 49");
        chat.accept("STALL nachogun on 0/2147483647 x 4001008: +0 in 90 s (under 2 a minute): etc 60 free of 96, "
                + "0 on the floor within 600 px (0 it may take), at -210,-2775, level 31");
        chat.accept("STALL Homerj on 1/5 x 4001008: +1 in 90 s: etc 47 free of 96, 0 on the floor, at -210,-2775");
        chat.finish();
        assertEquals(2, out.lines.size(), "the first bot's STALL line, then the count of held-back repeats");
    }

    @Test
    void aDropIsOneLinePerItemAndOwnerWhateverTheCount() {
        Out out = new Out();
        AtomicInteger stage = new AtomicInteger(5);
        StagingChat chat = chat(out, stage);
        chat.accept("DROP Dagger22 drops 1 x 4001008 for Admin");
        chat.accept("DROP Dagger22 drops 2 x 4001008 for Admin");
        chat.accept("DROP Dagger22 drops 1 x 4001008 (extras)");
        chat.accept("DROP Dagger22 drops 1 x 4001009 (extras)");
        chat.accept("DROP Dagger22 drops 1 x 4001008 for Admin");
        chat.finish();
        assertEquals(List.of(
                "DROP Dagger22 drops 1 x 4001008 for Admin",
                "DROP Dagger22 drops 1 x 4001008 (extras)",
                "DROP Dagger22 drops 1 x 4001009 (extras)",
                "2 repeat(s) held back this stage (the log has every line)"),
                out.lines, "counts inside one (item, owner) pair are one line; another owner or item is its own");
    }

    @Test
    void theNextStageStartsWithItsOwnLinesAndSaysWhatItHeldBack() {
        Out out = new Out();
        AtomicInteger stage = new AtomicInteger(5);
        StagingChat chat = chat(out, stage);
        chat.accept("FIGHT Dagger22 gives up on Curse Eye at 543,-2177 for 10 s (no way closer in 3 s)");
        chat.accept("FIGHT Dagger22 gives up on Curse Eye at 500,-2177 for 10 s (no way closer in 3 s)");
        chat.accept("FIGHT Dagger22 gives up on Curse Eye at 460,-2177 for 10 s (no way closer in 3 s)");
        stage.set(1);
        chat.accept("RUN stage 1: bots in the lobby map");
        assertEquals(List.of(
                "FIGHT Dagger22 gives up on Curse Eye at 543,-2177 for 10 s (no way closer in 3 s)",
                "2 repeat(s) held back this stage (the log has every line)",
                "RUN stage 1: bots in the lobby map"), out.lines, "the stage change flushes, then resets");
        chat.accept("FIGHT Dagger22 gives up on Curse Eye at 400,-2177 for 10 s (no way closer in 3 s)");
        assertEquals(4, out.lines.size(), "a line held back in stage 5 is shown again in stage 1");
    }

    @Test
    void theRunEndSaysWhatTheLastStageHeldBack() {
        Out out = new Out();
        AtomicInteger stage = new AtomicInteger(5);
        StagingChat chat = chat(out, stage);
        chat.accept("RUN 1000000358 hunts the bonus for 78 s");
        chat.accept("RUN 1000000359 hunts the bonus for 114 s");
        chat.accept("RUN 1000000358 hunts the bonus for 78 s");
        chat.accept("NPC Dagger22 -> 9020002 (leave the bonus): \"Are you ready to leave this map?\"");
        chat.accept("NPC Dagger22 -> 9020002 (leave the bonus): \"Are you ready to leave this map?\"");
        chat.finish();
        assertEquals(List.of(
                "RUN 1000000358 hunts the bonus for 78 s",
                "RUN 1000000359 hunts the bonus for 114 s",
                "NPC Dagger22 -> 9020002 (leave the bonus): \"Are you ready to leave this map?\"",
                "2 repeat(s) held back this stage (the log has every line)"), out.lines);
    }

    /** The numbers that differ between two reports of one thing: a mob's position, a bag, a count. */
    @Test
    void aSignatureKeepsWhatTheLineIsAboutAndDropsItsNumbers() {
        assertEquals(StagingChat.signature("FIGHT A gives up on Horny Mushroom at 202,-286 for 10 s (no way closer in 3 s)"),
                StagingChat.signature("FIGHT A gives up on Horny Mushroom at 61,-286 for 10 s (no way closer in 3 s)"));
        assertEquals("FIGHT Horny Mushroom",
                StagingChat.signature("FIGHT A gives up on Horny Mushroom at 61,-286 for 10 s (no way closer in 3 s)"));
        assertEquals(StagingChat.signature(
                        "FIGHT A gives up on Horny Mushroom at 61,-286 for 10 s (no way closer in 3 s)"),
                StagingChat.signature("FIGHT B gives up on Horny Mushroom at 202,-286 for 10 s (no way closer in 3 s)"),
                "the bot is not part of what the line is about");
        assertEquals("STALL x 4001008", StagingChat.signature(
                "STALL A on 0/2147483647 x 4001008: +0 in 90 s (under 2 a minute): etc 96 free of 96, at -210,-2775"));
        assertEquals("DROP x 4001008 for Admin",
                StagingChat.signature("DROP A drops 3 x 4001008 for Admin"));
        assertEquals("DROP x 4001008 (extras)",
                StagingChat.signature("DROP A drops 2 x 4001008 (extras)"), "a drop to extras has no owner");
        assertEquals("TAKE BACK x 4001008",
                StagingChat.signature("TAKE BACK A picks its 4001008 back up (he left it)"));
        String progress = "RUN stage 4 cleared in 30 s (0 wrong tries shown)";
        assertEquals(progress, StagingChat.signature(progress), "progress lines are compared whole");
    }
}
