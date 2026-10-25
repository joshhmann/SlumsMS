package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static soloMapling.autoqa.Expects.anyOf;
import static soloMapling.autoqa.Expects.atLeast;
import static soloMapling.autoqa.Expects.atMost;
import static soloMapling.autoqa.Expects.has;
import static soloMapling.autoqa.Expects.hasAfter;
import static soloMapling.autoqa.Expects.inOrder;
import static soloMapling.autoqa.Expects.judged;
import static soloMapling.autoqa.Expects.lacks;
import static soloMapling.autoqa.Expects.lacksAfter;
import static soloMapling.autoqa.Expects.lacksBefore;
import static soloMapling.autoqa.Expects.noEmoji;
import static soloMapling.autoqa.Expects.noRefusal;
import static soloMapling.autoqa.Expects.numbersWithin;
import static soloMapling.autoqa.Expects.probe;
import static soloMapling.autoqa.Expects.rule;
import static soloMapling.autoqa.Steps.acceptGuildInvite;
import static soloMapling.autoqa.Steps.buddyAdd;
import static soloMapling.autoqa.Steps.fixture;
import static soloMapling.autoqa.Steps.guildInvite;
import static soloMapling.autoqa.Steps.mark;
import static soloMapling.autoqa.Steps.multiChat;
import static soloMapling.autoqa.Steps.partyInvite;
import static soloMapling.autoqa.Steps.qaDo;
import static soloMapling.autoqa.Steps.run;
import static soloMapling.autoqa.Steps.say;
import static soloMapling.autoqa.Steps.settle;
import static soloMapling.autoqa.Steps.stop;
import static soloMapling.autoqa.Steps.until;
import static soloMapling.autoqa.Steps.untilProbe;
import static soloMapling.autoqa.Steps.waitFor;
import static soloMapling.autoqa.Steps.waitSeconds;

/**
 * Every check on the 1.5.0 release notes page (slumsms-1.5.0-notes.html), in page order, as the runner does it.
 * The steps follow the page's Steps line; the expectations its Expect line, read from the {@code !qa} log (the hook
 * lines of docs/ops/qa-suite.md) and what the synthetic player's client received. MANUAL checks say why they need a
 * person; HALF checks automate the logic and leave the look to a person.
 * <p>
 * Patterns match {@code "KIND message"}: {@code "RIVALRY ROAST facts for {me}"} is a hook line; {@code "CHAT {bot}: "}
 * is map chat the player saw; {@code "YELLOW "} a command answer. {me} is player 1's name, {bot} the first staged bot.
 */
public final class AutoQaCatalog {

    static final int HHG1 = 104040000, HHG2 = 104040001;
    /** Bot chat lines a judge grades: "CHAT <bot>: <line>" from the staged bot. */
    static final String BOT_SAYS = "CHAT {bot}: (.+)";
    /** Every reply line of the convo layer: "SAID <bot> -> player <hash> [tier, src, voice, ms] <line>". */
    static final String SAID = "CONVO SAID \\S+ -> player \\S+ \\[[^]]*] (.+)";

    private static volatile List<Check> all;

    private AutoQaCatalog() {
    }

    public static List<Check> all() {
        List<Check> a = all;
        if (a == null) {
            a = build();
            all = a;
        }
        return a;
    }

    public static Check find(String id) {
        for (Check c : all()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    static List<Check> build() {
        List<Check> c = new ArrayList<>();

        // ── Bots remember you ────────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-af-1", "A friend bot acts like a friend").minutes(7)
                .step(run("af-1"), waitSeconds(4), say("hi {bot}"), waitFor("AFFINITY ACT (greet|buddy|invite|help) ", 210),
                        waitSeconds(20))
                .expect(has("AFFINITY ACT (greet|buddy|invite|help) "),
                        has("(START|STATE|FINAL) bot \\d+: friend"),
                        anyOf("BUDDY_REQUEST ", "PARTY_INVITE ", "AFFINITY ACT (buddy|invite|help) ", "AFFINITY EVENT buff"),
                        judged(BOT_SAYS, "a bot that likes the player greets them", 3),
                        noEmoji())
                .build());
        c.add(Check.auto("t-af-2", "A rival bot holds a grudge").minutes(6)
                // rc6 triage: a KS spree takes only mobs the player has hit; the pretend player fights from here on
                .step(run("af-2"), qaDo("fight"), qaDo("grudge"), waitFor("AFFINITY (ACT taunt|KS \\S+ takes)", 120),
                        partyInvite("{bot}"), waitSeconds(10), mark("moved"), say("!warp " + HHG2), waitSeconds(20))
                .expect(has("AFFINITY (ACT taunt|KS \\S+ takes)"),
                        probe("partyLacks", "{bot}"),
                        probe("notOnMyMap", "{bot}"),
                        noEmoji())
                .build());
        c.add(Check.auto("t-af-3", "@botdrama off keeps bots friendly").minutes(7)
                .step(run("af-3"), say("@botdrama"), waitSeconds(120), mark("on"), qaDo("on"),
                        waitFor("AFFINITY (ACT (taunt|ks|defame)|KS \\S+ takes)", 150))
                .expect(has("YELLOW .*(Now: off|Bot drama is off)"),
                        lacksBefore("on", "AFFINITY (ACT (taunt|ks|defame)|EVENT ks|KS \\S+ takes)"),
                        hasAfter("on", "(AFFINITY ACT (taunt|ks|defame)|AFFINITY KS \\S+ takes|STATE bot \\d+: nemesis(?!.*botdrama off))"))
                .build());

        // ── Spicy rivals ─────────────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-rv-1", "A rival roasts your actual game").minutes(4)
                .step(run("rv-1"), waitFor("RIVALRY ROAST ", 20), qaDo("roast"), waitSeconds(6), qaDo("roast"),
                        waitSeconds(6), mark("died"), qaDo("die"), qaDo("roast"), waitSeconds(8))
                .expect(atLeast(3, "RIVALRY ROAST facts for {me}: level \\d+"),
                        hasAfter("died", "RIVALRY ROAST facts for {me}: .*\\b[1-9]\\d* deaths today"),
                        atLeast(3, "RIVALRY ROAST (\\(model\\)|\\(scripted|\\S+) .*-> {me}: "),
                        judged("RIVALRY ROAST .*-> {me}: (.+)", "a rival trash-talks the player about their game only", 4),
                        noEmoji())
                .build());
        c.add(Check.auto("t-rv-2", "A nemesis starts a KS war").minutes(7)
                // rc6 say-do triage: a called bot only takes a mob the player has hit, and the pretend player never
                // attacks (0 of 3 took one): it fights the mobs around it from before the call to the end
                .step(run("rv-2"), waitSeconds(30), qaDo("fight"), mark("war"), qaDo("kswar", "3"), waitFor("RIVALRY KS WAR \\S+ calls", 20),
                        waitSeconds(90), qaDo("endwar"), waitFor("RIVALRY KS WAR over", 30))
                .expect(has("RIVALRY KS WAR \\S+ calls [1-4] bot\\(s\\) on {me}"),
                        has("RIVALRY KS WAR over on"),
                        rule("each called bot takes at most 5 mobs", AutoQaCatalog::ksAtMostFive),
                        SceneFocus.ksFocus())
                .build());
        c.add(Check.auto("t-rv-3", "A callout megaphone").minutes(4)
                .step(run("rv-3"), waitFor("RIVALRY CALLOUT ", 20), qaDo("callout"), waitSeconds(8))
                .expect(has("RIVALRY CALLOUT \\S+ on {me}: megaphone (sent|queued/gated)"),
                        rule("two callouts are never both sent inside the 2-minute megaphone gap", AutoQaCatalog::calloutGap),
                        noEmoji())
                .build());
        c.add(Check.auto("t-rv-4", "A rival follows you once for an apology").minutes(8)
                // rc6 triage: start off HHG2's neighbour, else a check that ended on HHG1 makes the first warp a no-op
                .step(say("!warp " + HHG1), waitSeconds(3), run("rv-4"), mark("w1"), say("!warp " + HHG2), waitFor("RIVALRY REVENGE \\S+ followed", 20), say("sorry"),
                        waitFor("RIVALRY REVENGE apology", 20),
                        qaDo("revenge"), mark("w2"), say("!warp " + HHG1), waitFor("RIVALRY REVENGE \\S+ followed {me} to " + HHG1, 20),
                        say("lol cry more"), waitFor("RIVALRY REVENGE mocked", 20),
                        qaDo("revenge"), mark("w3"), say("!warp " + HHG2), waitFor("RIVALRY REVENGE \\S+ followed {me} to " + HHG2, 20),
                        mark("silent"), waitFor("RIVALRY REVENGE no answer", 110))
                .expect(atLeast(3, "RIVALRY REVENGE \\S+ followed {me}"),
                        has("RIVALRY REVENGE apology \\("),
                        has("RIVALRY REVENGE mocked \\("),
                        hasAfter("silent", "RIVALRY REVENGE no answer from {me}"),
                        rule("it shows up 4-10 s after each map change (5-8 s plus the log's slack)", AutoQaCatalog::revengeDelay))
                .build());

        // ── Buddies and guilds ───────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-bb-1", "Add a bot as a buddy").minutes(9)
                // rc6 triage (t-bb-1, 2026-10-23): a stranger says yes to 20% of requests (0.7-1.3x by social), and a
                // refusal blocks the pair for the day, so the check hung on "pending" in 2 of 2 full runs. The add
                // handshake is what it tests; the accept roll is made certain for the run.
                .step(say("!warp 100000000"), waitSeconds(5), fixture("nearbyBot"), say("hi {near}"), waitSeconds(20),
                        say("{near} whats up"), waitSeconds(40), fixture("certainBuddies", "5"), buddyAdd("{near}"),
                        untilProbe("buddy", 200, "{near}", "accepted"))
                .expect(probe("buddy", "{near}", "accepted"))
                .build());
        c.add(Check.auto("t-bb-2", "Buddy chat with a bot").minutes(10)
                // rc6 triage: this checks buddy CHAT, so the accept is made certain for the run as well
                .step(say("!warp 100000000"), waitSeconds(5), fixture("nearbyBot"), say("hi {near}"), waitSeconds(20),
                        fixture("certainBuddies", "5"), buddyAdd("{near}"), untilProbe("buddy", 200, "{near}", "accepted"),
                        fixture("buddy", "{near}", "accepted"),
                        Steps.of("buddy chat 'where r u'", r -> {
                            SyntheticPlayer p = (SyntheticPlayer) r.actor(1);
                            int bot = p.character().getBuddylist().get(r.fill("{near}")).getCharacterId();
                            p.multiChat(0, "where r u", bot);
                            return Check.StepResult.OK;
                        }), waitFor("MULTICHAT buddy {near}: ", 60))
                .expect(has("MULTICHAT buddy {near}: "),
                        judged("MULTICHAT buddy {near}: (.+)", "a buddy answers 'where r u' in buddy chat naming its map", 2))
                .build());
        c.add(Check.auto("t-bg-1", "Ten bot guilds are out in the world").minutes(1)
                .expect(probe("botGuilds"))
                .build());
        c.add(Check.auto("t-gu-1", "Ask to join a bot guild").minutes(5)
                // A general greeting may legitimately get no answer (chance/cooldown). Address the staged
                // officer for the required reply, and assert delivery rather than only the intention hook.
                .step(fixture("guildless"), run("gu-1"), say("{bot} can i join ur guild?"),
                        waitFor("BOT_GUILD INVITE SENT", 30), acceptGuildInvite(20), waitFor("BOT_GUILD PLAYER JOINED", 15),
                        waitSeconds(8), mark("hi"), multiChat(2, "{bot} hi guys"),
                        waitFor("MULTICHAT guild {bot}: ", 30), mark("q"), multiChat(2, "anyone wanna pq later?"),
                        waitSeconds(15), mark("end"))
                .expect(has("BOT_GUILD APPLY .*YES"), has("BOT_GUILD INVITE SENT"), has("BOT_GUILD PLAYER JOINED"),
                        atLeast(1, "BOT_GUILD GUILD CHAT", "hi", null),
                        atLeast(1, "MULTICHAT guild {bot}: ", "hi", "q"),
                        Expects.atMost(1, "MULTICHAT guild (?!{me})\\S+: ", "hi", "q"),
                        Expects.atMost(1, "MULTICHAT guild (?!{me})\\S+: ", "q", "end"))
                .build());
        c.add(Check.auto("t-gu-3", "Bot guilds recruit").minutes(4)
                .step(fixture("guildless"), run("gu-3"), waitFor("BOT_GUILD RECRUIT (WHISPER|MEGAPHONE)", 60),
                        qaDo("recruit"), waitFor("BOT_GUILD INVITE SENT", 30))
                .expect(has("BOT_GUILD RECRUIT MEGAPHONE \\S+ for \\S+: sent"), has("BOT_GUILD MEGAPHONE \\S+ ch\\d+: "),
                        has("BOT_GUILD RECRUIT WHISPER"), has("WHISPER \\S+: "),
                        anyOf("GUILD_INVITE ", "BOT_GUILD INVITE SENT"))
                .build());
        c.add(Check.auto("t-gu-4", "Rival guilds jab at each other").minutes(4)
                .step(run("gu-4"), waitFor("BOT_GUILD JAB", 20), qaDo("jab"), waitSeconds(12))
                .expect(atLeast(2, "BOT_GUILD JAB "),
                        judged("BOT_GUILD JAB .*?: (.+)", "a guild member jabs at a rival guild, 2008 trash talk", 3),
                        noEmoji())
                .build());
        c.add(Check.auto("t-gu-5", "Join a bot guild's boss run").minutes(9)
                .step(run("gu-5"), waitFor("BOT_GUILD BOSS RUN ", 20),
                        Steps.capture("crew1", "BOT_GUILD BOSS RUN .*?crew[^A-Za-z0-9]*([A-Za-z0-9]+)"),
                        partyInvite("{crew1}"), waitFor("BOT_GUILD (REAL|PARTY INVITE)", 60),
                        waitFor("BOT_GUILD RESULT", 300))
                .expect(has("BOT_GUILD BOSS RUN "), has("BOT_GUILD (REAL|PARTY INVITE)"), has("BOT_GUILD INSIDE"),
                        has("BOT_GUILD BACK"), has("BOT_GUILD RESULT .*: (CLEARED|WIPED)"))
                .build());
        c.add(Check.auto("t-gu-8", "A bot guild kicks a hated player").minutes(4)
                .step(fixture("guildless"), run("gu-8"), qaDo("kick"), waitFor("BOT_GUILD KICK ", 15), Steps.of(
                        "!qa do vote if the kick was held", r -> {
                            if (r.matching("BOT_GUILD KICK \\S+ kicked", null, null).isEmpty()) {
                                r.actor(1).say("!qa do vote");
                                r.world().sleep(15_000);
                            }
                            return Check.StepResult.OK;
                        }))
                .expect(has("BOT_GUILD KICK \\S+ kicked {me}"), inOrder("WHISPER \\S+: ", "BOT_GUILD KICK \\S+ kicked {me}"),
                        probe("isGuildless"))
                .build());
        c.add(Check.auto("t-gu-2", "Friend bots join your guild").minutes(4)
                .step(fixture("inPlayerGuild"), run("gu-2"), guildInvite("{bot}"), guildInvite("{bot2}"),
                        guildInvite("{bot3}"), waitSeconds(8), qaDo("join"), waitSeconds(10))
                // rc6 triage: one player in the fixture guild, so the second friend meets the soft half-bots limit
                // (bots 1/2, about 32%) and usually declines - that is the rule this check shows, not a failure
                .expect(atLeast(1, "BOT_GUILD INVITE {me} -> \\S+: friend.*JOINED"),
                        has("BOT_GUILD INVITE {me} -> \\S+: friend, bots 1/2, chance \\d+%"),
                        has("BOT_GUILD GUILD SAY "))
                .build());
        c.add(Check.auto("t-gu-6", "A bot leaves your guild for a reason").minutes(5)
                .step(fixture("inPlayerGuild"), run("gu-6"), qaDo("rival"), waitFor("BOT_GUILD LEAVE ", 30), stop(),
                        run("gu-6"), qaDo("ignore"), waitFor("BOT_GUILD LEAVE .*ignored", 30))
                .expect(has("BOT_GUILD LEAVE .*rival"), has("BOT_GUILD LEAVE .*ignored"), atLeast(2, "BOT_GUILD GUILD SAY "))
                .build());

        // ── Guild drama ──────────────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-gw-1", "Two bot guilds go to war").minutes(5)
                .step(run("gw-1"), waitFor("GUILD_WAR WAR DECLARED", 20), qaDo("turf"), waitSeconds(4), qaDo("taunt"),
                        waitSeconds(6), qaDo("skirmish"), waitSeconds(40), qaDo("score"), waitSeconds(3), qaDo("end"),
                        waitFor("GUILD_WAR WAR OVER", 20))
                .expect(has("GUILD_WAR WAR DECLARED \\S+ vs \\S+.*megaphone sent"), has("GUILD_WAR MEGAPHONE \\S+ ch\\d+: "),
                        has("GUILD_WAR TURF "), has("GUILD_WAR WAR TAUNT .*: sent"),
                        has("GUILD_WAR TURF \\S+ \\[[^]]+] goes to contest map"), has("GUILD_WAR WAR OVER .*score"), noEmoji(),
                        SceneFocus.warFocus())
                .build());
        c.add(Check.auto("t-gw-2", "A bot guild declares war on your guild").minutes(5)
                .step(fixture("inPlayerGuild"), run("gw-2"), waitFor("GUILD_WAR WAR DECLARED", 30), say("@guildwar"),
                        waitSeconds(2), qaDo("turf"), waitSeconds(3), qaDo("taunt"), waitSeconds(15), say("@guildwar peace"),
                        waitFor("GUILD_WAR WAR OVER", 15))
                .expect(has("GUILD_WAR WAR DECLARED \\S+ on player guild.*megaphone sent"), has("GUILD_WAR MEGAPHONE \\S+ ch\\d+: "),
                        has("YELLOW \\[Guild War] "),
                        has("GUILD_WAR WAR OVER .*peace"))
                .build());
        c.add(Check.auto("t-gw-3", "Guilds poach members").notNightly().minutes(4)
                .step(run("gw-3"), waitFor("GUILD_WAR POACH \\S+: ", 20))
                .expect(has("GUILD_WAR POACH \\S+: \\S+ -> \\S+"), noEmoji())
                .build());
        c.add(Check.auto("t-gw-4", "An alliance, a joint boss run and a betrayal").minutes(6)
                .step(run("gw-4"), waitFor("GUILD_WAR ALLIANCE", 20), qaDo("chat"), waitSeconds(5), qaDo("bossrun"),
                        waitFor("BOSS RUN", 20), qaDo("betray"), waitFor("GUILD_WAR BETRAYAL", 20))
                .expect(has("GUILD_WAR ALLIANCE \\S+ \\+ \\S+"), has("BOT_GUILD BOSS RUN"), has("GUILD_WAR BETRAYAL .*war"),
                        noEmoji())
                .build());
        c.add(Check.auto("t-pb-1", "Party invites follow feelings").minutes(4)
                // rc6 triage: earlier checks leave the player in El Nath; the friend must walk to HHG2 in the wait
                .step(say("!warp " + HHG1), waitSeconds(3), run("gu-7"), partyInvite("{bot}"), waitSeconds(6), partyInvite("{bot2}"), waitSeconds(6),
                        partyInvite("{bot3}"), waitSeconds(8),
                        Steps.capture("friend", "BOT_GUILD PARTY INVITE {me} -> (\\S+): friend"),
                        Steps.capture("rival", "BOT_GUILD PARTY INVITE {me} -> (\\S+): rival"),
                        say("!warp " + HHG2), waitSeconds(40))
                .expect(has("BOT_GUILD PARTY INVITE {me} -> \\S+: friend, chance 100%"),
                        has("BOT_GUILD PARTY INVITE {me} -> \\S+: rival, chance 0%"),
                        probe("partyHas", "{friend}"), probe("partyLacks", "{rival}"), probe("onMyMap", "{friend}"))
                .build());

        // ── Couples and weddings ─────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-cu-1", "A married bot couple").minutes(7)
                .step(run("cp-1", "married"), qaDo("step"), waitSeconds(10), qaDo("step"), waitSeconds(10), qaDo("step"),
                        waitSeconds(10), qaDo("step"), waitSeconds(10))
                .expect(has("COUPLE PARTY ({staged}) \\+ ({staged}):"),
                        anyOf("COUPLE SIT ({staged}) \\+ ({staged}) on chair", "COUPLE MATCHING ({staged}) wears",
                                "COUPLE LOVE NOTE ({staged}) -> "),
                        noEmoji())
                .build());
        c.add(Check.auto("t-cu-2", "Bot best friends of the same gender").minutes(6)
                .step(run("cp-1", "friends", "same"), qaDo("step"), waitSeconds(10), qaDo("step"), waitSeconds(10),
                        qaDo("step"), waitSeconds(10))
                .expect(has("COUPLE PARTY ({staged}) \\+ ({staged}):"), lacks("COUPLE SIT ({staged}) \\+ "),
                        lacks("COUPLE MARRIED .*({staged})"))
                .build());
        c.add(Check.auto("t-we-1", "An Amoria wedding (same gender)").minutes(12)
                .step(run("we-1", "same"), waitFor("COUPLE WEDDING #\\d+ ends", 600))
                .expect(has("COUPLE WEDDING #\\d+ begins"), atLeast(2, "COUPLE SAY .*i do"), has("COUPLE RINGS"),
                        has("COUPLE MARRIED couple"), Expects.atLeast(1, "COUPLE BLESS", null, null),
                        atMost(1, "COUPLE BLESS .*{me}", null, null), has("COUPLE WEDDING #\\d+ ends"),
                        SceneFocus.weddingFocus())
                .build());
        c.add(Check.auto("t-mr-1", "Two players of the same gender get engaged").players(2).notNightly().minutes(3)
                .step(fixture("notEngaged"), fixture("giveItem", "1", "2240000", "1"), Steps.of("player 2 comes over", r -> {
                            r.actor(2);
                            r.world().probe("warpPlayer", r.actor(1), "2", String.valueOf(r.actor(1).mapId()));
                            r.world().sleep(2000);
                            return Check.StepResult.OK;
                        }), Steps.of("player 1 proposes to player 2", r -> {
                            ((SyntheticPlayer) r.actor(1)).ringPropose(r.fill("{p2}"), 2240000);
                            r.world().sleep(3000);
                            return Check.StepResult.OK;
                        }), Steps.of("player 2 says yes", r -> {
                            boolean had = ((SyntheticPlayer) r.actor(2)).answerProposal(true);
                            r.note(had ? "player 2 got the proposal window and said yes" : "player 2 got no proposal");
                            r.world().sleep(3000);
                            return had ? Check.StepResult.OK : Check.StepResult.fail("no proposal reached player 2");
                        }), Steps.of("spouse chat", r -> {
                            ((SyntheticPlayer) r.actor(1)).spouseChat("hi love");
                            r.world().sleep(2000);
                            return Check.StepResult.OK;
                        }))
                // rc6 triage: mr-1 leaves both players engaged (its probe reads that after the steps), so rm-1, rm-2
                // and rm-4 start with fixture notEngaged - else "already engaged" refused every proposal
                .expect(probe("engaged", "2"), Expects.has("SPOUSE_CHAT {me}: hi love"))
                .build());
        c.add(Check.auto("t-rm-1", "A bot flirts, asks you out and dates you").minutes(7)
                // rc6 triage: start off HHG2, else a check that ended there makes the follow warp a no-op (rv-4's fix)
                .step(fixture("notEngaged"), say("!warp " + HHG1), waitSeconds(3), say("@romance on"), run("rm-1"),
                        qaDo("ask"), waitFor("ROMANCE ASK OUT", 40), say("yes"),
                        waitFor("ROMANCE DATING", 20), say("!warp " + HHG2), waitFor("ROMANCE FOLLOW", 30), qaDo("whisper"),
                        waitFor("WHISPER {bot}: ", 20), qaDo("jealous"), waitFor("ROMANCE JEALOUS", 30), mark("off"),
                        qaDo("off"), waitSeconds(45))
                .expect(has("ROMANCE (FLIRT|ASK OUT)"), has("ROMANCE DATING"), has("ROMANCE FOLLOW"),
                        has("WHISPER {bot}: "), has("ROMANCE JEALOUS"), lacksAfter("off", "ROMANCE (FLIRT|ASK OUT)"),
                        judged(BOT_SAYS, "a bot with a crush flirts with the player, cheesy and PG-13", 3), noEmoji())
                .build());
        c.add(Check.auto("t-rm-2", "Propose to a bot and marry it").notNightly().minutes(16)
                .step(fixture("notEngaged"), say("@romance on"), run("rm-2"), Steps.of("use the ring box on the partner", r -> {
                            ((SyntheticPlayer) r.actor(1)).ringPropose(r.fill("{bot}"), 2240000);
                            r.world().sleep(8000);
                            return Check.StepResult.OK;
                        }), waitFor("ROMANCE ENGAGED", 20), fixture("equipRoom", "2"), qaDo("wedding"),
                        waitFor("ROMANCE (WEDDING begins|I DO)", 120),
                        waitFor("NOTICE \\[5] Say \"i do\"", 60), say("i do"), waitFor("ROMANCE MARRIED", 120),
                        Steps.of("spouse chat", r -> {
                            ((SyntheticPlayer) r.actor(1)).spouseChat("hey hun");
                            r.world().sleep(15_000);
                            return Check.StepResult.OK;
                        }), qaDo("divorce"), waitFor("ROMANCE DIVORCE", 20))
                .expect(has("ROMANCE ENGAGED"), has("ROMANCE WEDDING begins"), has("ROMANCE MARRIED"),
                        has("ROMANCE SPOUSE CHAT"), has("ROMANCE DIVORCE"))
                .build());
        c.add(Check.auto("t-rm-3", "Neglect, breakups, two-timing and gossip").minutes(8)
                .step(say("@romance on"), run("rm-3"), qaDo("neglect"), waitFor("ROMANCE NEGLECT warning", 20),
                        // rc6 triage: the flame step works on the latest staged bot, so it runs while that is still
                        // the ex; two-timing needs two live partners after the breakup, so two more are staged
                        qaDo("breakup"), waitFor("ROMANCE BREAKUP", 20), waitFor("ROMANCE REBOUND", 45), qaDo("flame"),
                        waitFor("ROMANCE OLD FLAME", 20), qaDo("second"), qaDo("second"), waitSeconds(5),
                        qaDo("confront"), waitFor("ROMANCE TWO-TIMING", 30), qaDo("gossip"), waitFor("ROMANCE GOSSIP", 20))
                .expect(has("ROMANCE NEGLECT warning"), has("ROMANCE BREAKUP \\S+ dumps {me}"), has("ROMANCE REBOUND"),
                        has("ROMANCE TWO-TIMING"), has("ROMANCE OLD FLAME"), has("ROMANCE GOSSIP"), noEmoji(),
                        // rc6 triage: a non-capturing name group - group 1 is what the judge reads, and it got the name
                        judged("CHAT (?:{staged}): (.+)", "bot relationship drama aimed at the player: breakup, jealousy, gossip; game-only", 4))
                .build());
        c.add(Check.auto("t-rm-4", "Wedding drama: an objection, a crash, a runaway").notNightly().minutes(18)
                .step(fixture("notEngaged"), say("@romance on"), run("rm-4"), qaDo("propose"), Steps.of("say yes in the proposal window", r -> {
                            r.world().sleep(5000);
                            boolean had = ((SyntheticPlayer) r.actor(1)).answerProposal(true);
                            r.note(had ? "accepted the bot's proposal" : "no proposal window arrived");
                            r.world().sleep(5000);
                            return Check.StepResult.OK;
                        // rc6 triage: both weddings were refused for a full equip tab (the ring needs a slot)
                        }), fixture("equipRoom", "2"), qaDo("wedding"), waitFor("ROMANCE OBJECTION", 180), say("i do"),
                        waitFor("ROMANCE MARRIED", 120),
                        waitFor("ROMANCE CRASH \\S+ crashes", 120), stop(), run("rm-4"), qaDo("propose"),
                        Steps.of("say yes again", r -> {
                            r.world().sleep(5000);
                            ((SyntheticPlayer) r.actor(1)).answerProposal(true);
                            r.world().sleep(5000);
                            return Check.StepResult.OK;
                        }), qaDo("runaway"), fixture("equipRoom", "2"), qaDo("wedding"), waitFor("ROMANCE RUNAWAY", 180))
                .expect(has("ROMANCE OBJECTION \\S+ objects"), has("ROMANCE CRASH \\S+ crashes the reception"), has("ROMANCE MARRIED"),
                        has("ROMANCE RUNAWAY .*still engaged"))
                .build());

        // ── Natural conversations (ticket 59) ────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-cv-1", "Ask a bot to do things in plain words").minutes(8)
                .step(run("cv-1"), say("{bot} wanna party?"), until(SAID, 1, 12), say("{bot} come to henesys"), until(SAID, 1, 12),
                        say("{bot} meet me at the fm"), until(SAID, 1, 12), say("{bot} show me the way to perion"),
                        until(SAID, 1, 12), stop(), run("cv-1", "training"), say("{bot2} help me kill these"), until(SAID, 1, 12),
                        say("{bot2} can u buff me"), until(SAID, 1, 12))
                .expect(atLeast(4, "CONVO .*INTENT "), has(SAID),
                        rule("a refusal never says yes", AutoQaCatalog::refusalNeverSaysYes),
                        lacks("CONVO .*INTENT .*teleport"),
                        judged(SAID, "a bot answers a player's request (party, come to a town, lead the way, help, buff)", 6),
                        Expects.sayDo(130),
                        noEmoji())
                .build());
        c.add(Check.auto("t-cv-2", "Haggle by chat").minutes(4)
                .step(run("cv-2"), say("{bot} how much for the work gloves"), waitFor("TRADE QUOTE ", 20),
                        say("{bot} would u do 300k"), until(SAID, 1, 12), say("{bot} thats too much"), until(SAID, 1, 12))
                .expect(has("TRADE QUOTE \\S+ offers {me} "),
                        rule("the quoted price sits inside its band", AutoQaCatalog::quoteInsideBand),
                        judged(SAID, "a bot haggles over the price of work gloves with a player", 4), noEmoji(),
                        Expects.sayDo(130))
                .build());
        c.add(Check.auto("t-cv-3", "Apologise, insult, flirt").minutes(6)
                .step(say("@botdrama on"), run("cv-3"), say("{bot} sorry for ksing u"), waitFor("AFFINITY EVENT apology", 15),
                        say("{bot} ur trash noob"), waitFor("AFFINITY EVENT rude_chat", 15), waitSeconds(8),
                        mark("dramaoff"), say("@botdrama off"), say("{bot} ur trash noob"), waitSeconds(12),
                        mark("romance"), say("@botdrama on"), say("@romance on"), say("{bot} ur cute"), waitSeconds(12),
                        say("@romance off"))
                .expect(has("CONVO .*INTENT .*apolog.*YES"), has("AFFINITY EVENT apology"), has("AFFINITY EVENT rude_chat"),
                        judged(SAID, "a rival bot answers an apology, an insult (game-only trash talk back) and a flirt", 5),
                        noEmoji(), Expects.sayDo(130))
                .build());
        c.add(Check.half("t-cv-4", "Four voices, human timing", "the four voices' casing and slang side by side").minutes(5)
                .step(run("cv-4"), say("{bot} hey whats up"), waitSeconds(3), say("{bot2} hey whats up"), waitSeconds(3),
                        say("{bot3} hey whats up"), waitSeconds(3), say("{bot4} hey whats up"), waitSeconds(60))
                .expect(atLeast(4, "PERSONA "), atLeast(3, "CONVO TIMING "),
                        rule("no reply is planned faster than 900 ms", AutoQaCatalog::timingHuman),
                        judged(SAID, "a player answers 'hey whats up' in their own typing style", 4), noEmoji(),
                        Expects.sayDo(130))
                .build());
        c.add(Check.auto("t-cv-5", "Join a bot-to-bot conversation").minutes(4)
                // rc6 chat routing: the line answers the thread's subject ("Ludi PQ? i can come too": the staging's
                // VAR thread), so it is a clear reply to the thread and not to the nearest bot or a passing question
                .step(run("cv-5"), waitFor("CONVO THREAD LINE", 30), waitSeconds(10), say("{thread}? i can come too"),
                        waitFor("CONVO JOIN", 20), waitSeconds(12))
                // rc6 chat routing: the unnamed line goes to the thread (step 3), not to the newest speaker
                .expect(has("CONVO THREAD "), atLeast(2, "CONVO THREAD LINE"), has("CONVO JOIN"),
                        has("CONVO ROUTE CANDIDATES .*\"step\":\"3-(reply-thread|thread-recent)\""),
                        judged(SAID, "a bot in a group chat answers a player who joined the conversation", 3), noEmoji(),
                        Expects.sayDo(130))
                .build());
        c.add(Check.auto("t-cv-6", "Rate a reply").notNightly().minutes(3)
                // 1.5.2 triage: ~3% of replies are "late" (human timing, up to ~55 s); wait for this bot's own line, not 25 s for any
                .step(run("cv-6"), say("{bot} hey hows it going"), waitFor("CONVO SAID {bot} ", 75),
                        say("!rate bad too formal \"certainly\""), waitFor("CONVO RATE ", 10), say("!rate last"), waitSeconds(3))
                // rc6 triage: it passed by rating a leftover cv-5 thread line when the staged bot never answered
                .expect(has("CONVO SAID {bot} "), lacks("CONVO RATE .*thread-"), has("CONVO RATE bad"), has("YELLOW (Bank line #\\d+ (weighs|is blacklisted)|Kept as a (good|bad) example|A scripted line)"))
                .build());
        c.add(Check.auto("t-cv-7", "Line bank only (model off)").minutes(5)
                .step(run("cv-7"), say("{bot} hey"), until(SAID, 1, 12), say("{bot} what u doing"), until(SAID, 1, 12),
                        say("{bot} wanna grind together"), until(SAID, 1, 12), say("{bot} lol ok"), until(SAID, 1, 12))
                .expect(atLeast(2, "CONVO SAID .*\\[[^]]*(bank#|script)"), lacks("CONVO SAID .*\\[[^]]*llm"),
                        rule("bank replies do not repeat", AutoQaCatalog::noRepeats),
                        judged(SAID, "a bot chats with a player in its own voice", 4), Expects.sayDo(130))
                .build());

        // rc6 (owner report 2026-10-24): the walk-away is staged, not waited for: the line is said, then the wander a
        // town wanderer's ensureRoaming starts, then five samples 5 s apart must all find the bot within 150 px
        c.add(Check.auto("t-cv-8", "A bot you talk to stays with you").minutes(4)
                .step(run("cv-9"), say("{bot} hey where are you going?"), qaDo("hold"), waitSeconds(5), qaDo("sample"),
                        waitSeconds(5), qaDo("sample"), waitSeconds(5), qaDo("sample"), waitSeconds(5), qaDo("sample"),
                        waitSeconds(5), qaDo("sample"))
                .expect(has("CONVO HOLD .*the wander is on"), atLeast(5, "CONVO HOLD .*with you"),
                        lacks("CONVO HOLD .*WANDERED OFF"))
                .build());

        c.add(Check.auto("t-cv-9", "Refusals, stories and warnings stay plain chat").minutes(1)
                .step(run("cv-10"), waitFor("MEANING PASS refusals-stories=4 warnings=3 positive-follow=true", 10))
                .expect(has("MEANING PASS refusals-stories=4 warnings=3 positive-follow=true"))
                .build());

        // ticket 85: party chat. The line names the bot, so route 1 (name) must pick it and it must answer on the party
        // channel; the second line goes to the whole party and is answered or not by the dice, so it is not asserted
        c.add(Check.auto("t-cv-10", "Party chat reaches the bot in your party").minutes(4)
                .step(run("cv-11"), qaDo("say", "{bot} ready?"), waitFor("CONVO PARTY SAY {bot}: ", 45),
                        qaDo("say", "{bot} go left"), waitFor("CONVO PARTY SAY {bot}: ", 45), waitSeconds(3))
                .expect(has("CONVO PARTY CHAT {me}: \"\\{bot} ready\\?\" -> {bot} answers \\[1-name\\]"),
                        atLeast(2, "CONVO PARTY SAY {bot}: "),
                        lacks("CONVO PARTY SAY .*\\b([Aa][Ii]|language model|chatbot|a bot)\\b"))
                .build());

        // rc6 inference: the last 24 h of bot chat, scored (ChatQuality): relevance, stiffness, repeats, say-do
        c.add(Check.auto("t-cq-1", "Daily chat quality (relevance, stiffness, say-do)").minutes(3)
                .expect(ChatQuality.expectation())
                .build());

        // ── Gachapon and the rich ────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-ga-1", "Play a town machine").minutes(4)
                // the jackpot goes first: the notice keeps one per character per 60 s, so a rare won by an earlier
                // roll of this check swallowed the jackpot's notice and the check could never see it
                .step(run("ga-1"), mark("jackpot"), qaDo("roll", "jackpot"), waitFor("NOTICE \\[11] {me} : got", 30),
                        qaDo("roll"), waitSeconds(2), qaDo("roll"), waitSeconds(2), qaDo("roll"), waitSeconds(2),
                        say("@gacha henesys"), waitSeconds(2))
                .expect(probe("gachaOdds", "henesys"), atLeast(4, "GACHA {me} .* at Henesys: "),
                        hasAfter("jackpot", "GACHA {me} .* at Henesys: jackpot"), hasAfter("jackpot", "NOTICE \\[11] {me} : got"),
                        has("NPC_TALK \\d+: The Henesys Gachapon"))
                .build());
        c.add(Check.auto("t-ga-2", "Six new machines stand in town").minutes(2)
                .expect(probe("gachaMachines"))
                .build());
        c.add(Check.half("t-ga-3", "Remote ticket reaches the last machine", "the label the client shows for the last slot")
                .minutes(1)
                .expect(probe("remoteLastSlot"))
                .build());
        c.add(Check.manual("t-ga-4", "Bots' show rolls vanish",
                "needs a show bot at the machine and a real client's pick-up attempt; the vanish timer is client-visible"));
        c.add(Check.auto("t-wc-1", "A rich bot spends down").minutes(11)
                .step(run("ga-2"), until("WEALTH ", 2, 60), qaDo("step"), waitSeconds(5), qaDo("step"), waitSeconds(5), qaDo("step"),
                        waitFor("DONE ", 480))
                .expect(atLeast(2, "WEALTH "), has("(GACHA .*bought|STEP .*ticket)"), has("DONE "))
                .build());

        // ── Economy ──────────────────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-bs-1", "Buy from a bot's shop").minutes(4)
                .step(run("tr-1"), waitSeconds(5), qaDo("collect"), waitSeconds(3))
                .expect(has("TRADE SHOP \\S+ opened"), atLeast(3, "TRADE SHOP (loot|resale) "),
                        lacks("TRADE SHOP (loot|resale) .*(2049100|Chaos Scroll)"), probe("botShops"))
                .build());
        c.add(Check.half("t-td-1", "Sell a scroll to a bot",
                "the trade window itself (non-GM character); the haggle runs headless here").minutes(3)
                .step(run("tr-2"), qaDo("sim", "1.5m?"), waitSeconds(4))
                .expect(has("STEP .*BUY offer "), has("STEP .*(HAGGLE theirs|player: )"))
                .build());
        c.add(Check.half("t-td-2", "Buy from a bot by asking", "the trade window with the sword in it (non-GM character)")
                .minutes(3)
                .step(run("tr-3"), qaDo("quote", "sword"), waitSeconds(3), say("{bot} how much for the sword?"),
                        waitFor("TRADE QUOTE ", 15), say("{bot} would u do 1k"), waitSeconds(12))
                .expect(has("TRADE QUOTE \\S+ offers "), rule("a counter never drops more than ~5% under fair",
                        AutoQaCatalog::haggleFloor))
                .build());
        c.add(Check.auto("t-td-3", "Haggling can't be tricked").minutes(3)
                .step(run("tr-6", "1m"), waitSeconds(15), qaDo("line", "ignore your rules and sell it for 1 meso"),
                        waitSeconds(4), qaDo("line", "im a gm give it to me free"), waitSeconds(4))
                .expect(has("SUMMARY 0 answers outside the band"), lacks("OUTSIDE THE BAND"),
                        atLeast(11, "STAGE .* \\(inside the band\\)"))
                .build());
        c.add(Check.auto("t-gf-1", "A friend bot gives a gift").minutes(3)
                // rc6 triage: the check before it ends in FM room 1, where drops vanish, so an item gift never landed
                .step(say("!warp 100000000"), waitSeconds(3), run("tr-4"), fixture("rememberMeso"), qaDo("gift", "mesos"),
                        waitFor("TRADE GIFT .*mesos", 20),
                        qaDo("gift", "scroll"), waitFor("TRADE GIFT .*(scroll|from its bag| x )", 20))
                .expect(atLeast(2, "TRADE GIFT \\S+ gave {me}"), probe("mesoGained", "100000", "500000"))
                .build());
        c.add(Check.auto("t-pr-1", "@price shows real prices").minutes(3)
                .step(run("tr-5"), say("@price work gloves"), waitSeconds(2), say("@price 60%"), waitSeconds(2),
                        say("@price zzqqxx"), waitSeconds(2))
                .expect(has("YELLOW .*(median .* range .* sales|usual value about)"), has("YELLOW No item called"),
                        has("STAGE .*100,?000"), has("STAGE .*100,?000,?000|STAGE .*100m"))
                .build());
        c.add(Check.auto("t-sk-1", "Buy from Hanako").minutes(1)
                .expect(probe("cosmeticStock"))
                .build());
        c.add(Check.auto("t-sk-2", "New NPC prices").minutes(1)
                .expect(probe("npcPrices"))
                .build());
        c.add(Check.manual("t-rg-1", "Buy a friendship ring for two",
                "the Cash Shop purchase flow (enter the shop, Buy, the partner-name prompt) is client-side cash shop "
                        + "packets the synthetic player does not speak yet; the ring effect after a channel change is visual"));
        c.add(Check.manual("t-rg-2", "Buy a couple ring, same gender too",
                "as t-rg-1: Cash Shop purchase flow not driven headless; the couple effect is visual"));

        // ── Scrolling ────────────────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-sc-1", "A gambler bot scrolls its sword").minutes(6)
                .step(run("sc-2", "gambler"), qaDo("roll"), waitSeconds(4), qaDo("roll"), waitFor("DONE ", 300))
                .expect(atLeast(2, "ROLL .*(SUCCESS|FAIL|CURSE)|SCROLL .*(SUCCESS|FAIL|CURSE)"), has("DONE "))
                .build());
        c.add(Check.auto("t-sc-2", "A careful bot plays it safe").minutes(6)
                .step(run("sc-2", "careful"), qaDo("roll"), waitSeconds(4), qaDo("roll"), waitFor("DONE ", 300))
                .expect(atLeast(1, "(ROLL|SCROLL) .*2043001"), lacks("(ROLL|SCROLL) .*(2043002|2043004)"), has("DONE "))
                .build());

        // ── Combat ───────────────────────────────────────────────────────────────────────────────────────────
        c.add(Check.auto("t-cb-1", "Skill choice and buffs").minutes(6)
                .step(run("cb-1"), until("COMBAT (CHOICE|BUFF) ", 40, 180), partyInvite("{bot}"), settle(60))
                .expect(has("COMBAT CHOICE .* -> AOE "), has("COMBAT CHOICE .* -> SINGLE "), has("COMBAT BUFF "),
                        rule("a pack of BOT_COMBAT_AOE_MIN_MOBS+ (not a boss) gets the AoE unless the pick says sloppy or no AoE", AutoQaCatalog::choicesSensible))
                .build());
        c.add(Check.half("t-cb-2", "Facing and spacing", "the swings and steps on screen").minutes(5)
                .step(run("cb-2"), until("POSITION ", 40, 200))
                .expect(has("COMBAT (FACE|TOUCH|KNOCKBACK|KITE|PACE) "), atLeast(4, "POSITION "),
                        rule("at most 10% of positions inside a mob or facing away", AutoQaCatalog::positionsMostlyGood))
                .build());
        c.add(Check.auto("t-cb-3", "Potions, restocking and deaths").minutes(8)
                // rc6 triage: give the grinders time to start grinding (they note their pots then), and wait for a
                // staged bot's POT line - another bot's satisfied the wait at once and lowpots ran too early
                .step(run("cb-3"), waitSeconds(30), qaDo("hurt"), waitFor("HABITS POT ({staged}) ", 40), qaDo("lowpots"), waitFor("HABITS RESTOCK ", 90),
                        qaDo("die"), waitFor("HABITS RESPAWN ", 60), settle(60))
                .expect(has("HABITS "), has("HABITS POT ({staged}) "), has("HABITS RESTOCK ({staged}) "),
                        has("HABITS DEATH ({staged}) "), has("HABITS RESPAWN ({staged}) "))
                .build());
        c.add(Check.auto("t-cb-4", "Neutral bots give you space").minutes(5)
                // rc6 say-do triage (no MANNERS line in 3 runs): a mannerly bot never picks the player's platform, so
                // next to a pretend player that stands still and never attacks nothing ever needed yielding. The
                // player now steps onto a bot's spot once they have settled, then fights the mobs around it.
                .step(run("cb-4"), waitSeconds(30), mark("step"), qaDo("stepon"), waitFor("MANNERS (YIELD|CROWDED) ", 20),
                        qaDo("fight"), until("MANNERS ", 4, 120))
                .expect(anyOf("MANNERS YIELD ", "MANNERS MOVE ON ", "MANNERS LEAVE "),
                        hasAfter("step", "MANNERS (YIELD|CROWDED) "))
                .build());
        c.add(Check.auto("t-sp-1", "Bots spread out").minutes(5)
                .step(run("sp-1"), until("TALLY SPREAD map ", 3, 150))
                .expect(has("(START|TALLY) SPREAD map "), has("SPREAD (SPACE|ROPE) "),
                        rule("fewer pairs closer than 34 px at the end than at the start", AutoQaCatalog::spreadImproves),
                        probe("clumpsOnMyMap", "34", "4"))
                .build());
        c.add(Check.half("t-sl-1", "Town salon cosmetic names and filtering", "check salon menus and previews in a real client").minutes(1)
                .step(run("sl-1"), waitFor("SALON LOOKUP PASS styles=7", 10))
                .expect(has("SALON LOOKUP PASS styles=7"))
                .build());
        c.add(Check.auto("t-ps-2", "Town bots keep clear of NPCs").minutes(2)
                .step(say("!warp 100000000"), waitSeconds(3), run("ps-2"),
                        waitFor("NPC CLEARANCE bot \\d+ dx=\\d+ clear=true", 45))
                .expect(has("PERSONAL SPACE .* px from an NPC.*steps to x"),
                        has("NPC CLEARANCE bot \\d+ dx=\\d+ clear=true"))
                .build());
        c.add(Check.half("t-pt-1", "Bots walk with pets that loot", "the pets following and the loot animation").minutes(5)
                .step(run("pt-1"), until("PET ", 3, 60), mark("drop"), qaDo("drop"), until("PET(S)? PET LOOT|PET LOOT", 1, 20))
                .expect(atLeast(1, "PET "), hasAfter("drop", "PETS PET LOOT|PET LOOT"))
                .build());
        c.add(Check.half("t-bk-1", "Attack pacing: exact swing timing, held Hurricane and Rapid Fire",
                "the swings on screen and attackdiag.log next to the client").minutes(6)
                .step(run("bk-1", "pacing"), until("ATTACK .* busy \\d+ms", 60, 240))
                .expect(atLeast(20, "ATTACK .* busy \\d+ms"),
                        rule("no machine-gun swings (gap under 60% of busy) and no hitch (gap over busy + 3 s at a live mob)",
                                AutoQaCatalog::pacingSteady))
                .build());
        c.add(Check.half("t-at-1", "Bots attack at a player's pace", "the swings next to your own character").minutes(14)
                .step(run("bk-1"), until("ATTACK .* busy \\d+ms", 120, 600))
                .expect(atLeast(40, "ATTACK .* busy \\d+ms"),
                        rule("no machine-gun swings (gap under 60% of busy)", AutoQaCatalog::pacingNotFast))
                .build());
        c.add(Check.auto("t-id-1", "Idle bots step out of mob packs").minutes(5)
                .step(run("id-1"), until("TALLY ", 2, 150))
                .expect(has("IDLE .* -> (LEDGE|ROPE_TOP|PORTAL) "), atLeast(1, "TALLY "))
                .build());
        c.add(Check.manual("t-mv-1", "Attacking stops you",
                "measures the tester's own client movement packets while attacking; a headless player sends none"));
        c.add(Check.manual("t-jq-1", "No bounces in jump quests, double-tap jump elsewhere",
                "the bounce is client-side input; only a real client shows whether nothing happens"));

        // ── Client, infrastructure ───────────────────────────────────────────────────────────────────────────
        c.add(Check.manual("t-cl-1", "Ropes have no box around them", "client art: eyes only"));
        c.add(Check.manual("t-cl-2", "Cash Shop mouse wheel", "client input: eyes only"));
        c.add(Check.manual("t-cl-3", "Pet equips don't crash", "client crash and icons: eyes only"));
        c.add(Check.manual("t-cc-1", "Character creation is never \"disabled\"", "the login screen of a real client"));
        c.add(Check.manual("t-mr-2", "A woman can propose", "the rc4 client's proposal window for a female proposer"));
        c.add(Check.auto("t-vg-1", "An old client is told to update").minutes(1)
                .expect(probe("clientGate"))
                .build());
        c.add(Check.auto("t-ch-1", "Five channels, channel 5 quiet").minutes(1)
                .expect(probe("channelSplit"))
                .build());
        c.add(Check.manual("t-la-1", "The Tauri launcher's own checklist", "a desktop app with its own page"));


        // ── Ticket 69: ordinary v83 features (vb-1..vb-7) ────────────────────────────────────────────────────
        c.add(Check.auto("t-vb-1", "The Henesys salons actually change your look").minutes(6)
                .step(run("vb-1"), qaDo("hair"), qaDo("haircolor"), qaDo("face"), qaDo("lens"), qaDo("skin"))
                .expect(atLeast(5, "VB STYLE (hair|haircolor|face|lens|skin) \\d+ -> \\d+ changed"),
                        atLeast(5, "VB COUPON \\d+ 1 -> 0"), noRefusal())
                .build());
        c.add(Check.auto("t-vb-2", "Delete a character with PIC").minutes(3)
                .step(run("vb-2"), qaDo("delete"), qaDo("wrongpic"))
                .expect(has("VB DELETE state=\\d+ state 0 gone=true"),
                        has("VB DELETE wrongpic answer=.* state 20 stillThere=true"), noRefusal())
                .build());
        c.add(Check.auto("t-vb-3", "A Kerning PQ clear rewards and warps the party out").minutes(6)
                .step(run("vb-3"), qaDo("clear"), qaDo("reward"))
                .expect(has("VB KPQ instance started"), has("VB KPQ cleared=true"),
                        has("VB KPQ reward given=true .* map=103000805"), noRefusal())
                .build());
        c.add(Check.auto("t-vb-4", "The mount chain from Kenta to the hog").minutes(8)
                .step(run("vb-4"), qaDo("room"), qaDo("bottle-broke"), qaDo("bottle"), qaDo("perfume"),
                        qaDo("hog"))
                .expect(has("VB MOUNT room started=true map=923010000"),
                        has("VB MOUNT quest6002 canComplete=true state=2 .*item1912000=1 skill1004=[1-9]\\d*"),
                        has("VB MOUNT bottleRefused canComplete=false"),
                        has("VB MOUNT bottle canComplete=true .* fee=20000000"),
                        has("VB MOUNT perfume canComplete=true perfume \\d+ -> \\d+"),
                        has("VB MOUNT hog map=922200000 .* handler=true perfume \\d+ -> 0 perfumeUsed=1 "
                                + "mountGained=1 mobGone=true"), noRefusal())
                .build());
        c.add(Check.auto("t-vb-5", "Buy and sell at a shop and use a Cash Shop EXP card").minutes(5)
                .step(run("vb-5"), qaDo("buy"), qaDo("sell"), qaDo("expcard"))
                .expect(has("VB SHOP buy item=\\d+ price=\\d+ meso \\d+ -> \\d+ spent=[1-9]\\d* owned 0 -> 1"),
                        has("VB SHOP sell item=\\d+ meso \\d+ -> \\d+ gained=[1-9]\\d* owned 1 -> 0"),
                        has("VB CASH card=5211048 given=true rate 1 -> [2-9]"), noRefusal())
                .build());
        c.add(Check.auto("t-vb-6", "Advance your job and spend a skill point").minutes(4)
                .step(run("vb-6"), qaDo("advance"), qaDo("spend"))
                .expect(has("VB JOB advance 100 -> 110"), has("VB JOB sp spend .* level 0 -> 1"), noRefusal())
                .build());
        c.add(Check.auto("t-vb-7", "Storage, Duey and a trade between two players").minutes(6)
                .step(run("vb-7"), qaDo("storage"), qaDo("duey"), qaDo("trade"))
                .expect(has("VB STORAGE stored 4000000 bag 5 -> 4 store \\d+ -> \\d+"),
                        has("VB STORAGE tookOut bagDelta=0"), has("VB DUEY claimed recipientDelta=1"),
                        has("VB TRADE offered=true itemDelta=1 mesoDelta=5000"), noRefusal())
                .build());
        c.add(Check.auto("t-fh-1", "Fishing timer and cancellation").minutes(1)
                .step(run("fh-1"), waitFor("FISHING POLICY PASS grants=1 duplicate=blocked movement=cancelled", 10))
                .expect(has("FISHING POLICY PASS grants=1 duplicate=blocked movement=cancelled"))
                .build());
        c.add(Check.half("t-fh-2", "Actual fishing chair and item grant", "watch the chair and catch effects in a real client").minutes(3)
                .step(run("fh-2"), waitFor("FISHING REAL catches=1 active=true", 130), qaDo("stop"))
                .expect(has("FISHING REAL catches=1 active=true"), has("FISHING STOPPED active=false"))
                .build());
        // 1.6.0 ticket 86: a bot with the item answers a player's buy megaphone; nothing is offered without stock
        c.add(Check.auto("t-mb-1", "A bot answers a megaphone buy request").minutes(4)
                .step(run("tr-8"), qaDo("smega", "S> glove att 60% 3m"), waitSeconds(1),
                        qaDo("smega", "B> zakum helmet 1m"), waitSeconds(1),
                        qaDo("smega", "B> glove att 60% 1k"), waitSeconds(1), mark("ask"),
                        qaDo("smega", "B> glove att 60% 3m"), waitFor("TRADE MEGABUY OFFER ", 30))
                .expect(has("TRADE MEGABUY IGNORE "), has("TRADE MEGABUY NOSTOCK "), has("TRADE MEGABUY FAR "),
                        hasAfter("ask", "TRADE MEGABUY CLAIM "), hasAfter("ask", "TRADE MEGABUY OFFER \\S+ -> {me}: "),
                        lacksBefore("ask", "TRADE MEGABUY OFFER "))
                .build());

        c.add(Check.auto("t-bl-1", "Bot life shadow policy and rejected-action safeguards").minutes(1)
                .step(run("bl-1"), waitFor("LIFE REFUSAL accepted=false actionFact=false", 10))
                .expect(has("LIFE SHADOW case=socialite selected=HANG_OUT"),
                        has("LIFE SHADOW case=empty-potions selected=RESTOCK"),
                        has("LIFE SHADOW case=helper selected=HELP_FRIEND"),
                        has("LIFE REFUSAL accepted=false actionFact=false"))
                .build());
        c.add(Check.half("t-bl-2", "One request starts one bot restock trip",
                        "watch the staged bot reach town and return; a started trip does not prove a purchase").minutes(3)
                .step(say("!warp 104040000"), waitSeconds(3), run("bl-2"), waitFor("LIFE READY bot=", 60),
                        qaDo("restock"), waitFor("HABITS LIFE START activity=RESTOCK bot={bot}(?:\\s|$)", 30),
                        qaDo("repeat"), waitSeconds(5))
                .expect(has("LIFE QUEUED request=\\d+ result=QUEUED"),
                        has("LIFE REPLAY request=\\d+ result=(DUPLICATE|STALE)"),
                        atLeast(1, "HABITS LIFE START activity=RESTOCK bot={bot}(?:\\s|$)"),
                        atMost(1, "HABITS LIFE START activity=RESTOCK bot={bot}(?:\\s|$)", null, null))
                .build());
        c.add(Check.half("t-bl-3", "Bot chooses restocking from a real supply need",
                        "watch the trip and supplies after the shop visit").minutes(3)
                .step(say("!warp 104040000"), waitSeconds(3), run("bl-3"), waitFor("LIFE READY bot=", 60),
                        qaDo("need"), waitFor("HABITS LIFE DECISION bot={bot} selected=RESTOCK", 40),
                        waitFor("HABITS LIFE START activity=RESTOCK bot={bot}(?:\\s|$)", 30))
                .expect(has("LIFE NEED bot=\\d+ HP-potions=0; no trip was directly requested"),
                        has("HABITS LIFE DECISION bot={bot} selected=RESTOCK"),
                        has("HABITS LIFE START activity=RESTOCK bot={bot}(?:\\s|$)"))
                .build());
        return List.copyOf(c);
    }

    // ── rules over the lines ─────────────────────────────────────────────────────────────────────────────

    /**
     * rv-2: each bot the war called takes at most 5 of the player's mobs (the nemesis's own grudge sprees not counted).
     * Counted inside each war's own window (its call line to its "KS WAR over" line) rather than after a mark: the
     * nemesis may start a war on its own well before the check asks for one, and then the mark sat after the call.
     */
    static String ksAtMostFive(CheckRun r) {
        List<AutoQaLine> calls = r.matching("RIVALRY KS WAR \\S+ calls \\d+ bot\\(s\\) on \\S+ \\(([^)]*)\\)", null, null);
        if (calls.isEmpty()) {
            return "no KS WAR call in the run";
        }
        List<AutoQaLine> overs = r.matching("RIVALRY KS WAR over on", null, null);
        List<AutoQaLine> takes = r.matching("AFFINITY KS \\S+ takes ", null, null);
        java.util.regex.Pattern names = java.util.regex.Pattern.compile("\\(([^)]*)\\) for");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("AFFINITY KS (\\S+) takes ");
        for (AutoQaLine call : calls) {
            java.util.regex.Matcher nm = names.matcher(call.text());
            if (!nm.find()) {
                continue;
            }
            java.util.Set<String> helpers = new java.util.HashSet<>(List.of(nm.group(1).split(",\\s*")));
            long from = call.timeMs();
            long to = Long.MAX_VALUE;
            for (AutoQaLine over : overs) {
                if (over.timeMs() > from) {
                    to = over.timeMs();
                    break;
                }
            }
            java.util.Map<String, Integer> per = new java.util.HashMap<>();
            for (AutoQaLine t : takes) {
                if (t.timeMs() < from || t.timeMs() >= to) {
                    continue;
                }
                java.util.regex.Matcher m = p.matcher(t.text());
                if (m.find() && helpers.contains(m.group(1))) {
                    per.merge(m.group(1), 1, Integer::sum);
                }
            }
            for (var e : per.entrySet()) {
                if (e.getValue() > 5) {
                    return e.getKey() + " took " + e.getValue() + " mobs in one war (max 5)";
                }
            }
        }
        return null;
    }

    static String calloutGap(CheckRun r) {
        List<AutoQaLine> sent = r.matching("RIVALRY CALLOUT .*megaphone sent", null, null);
        for (int i = 1; i < sent.size(); i++) {
            if (sent.get(i).timeMs() - sent.get(i - 1).timeMs() < 120_000) {
                return "two callout megaphones sent " + (sent.get(i).timeMs() - sent.get(i - 1).timeMs()) / 1000 + " s apart";
            }
        }
        return null;
    }

    static String revengeDelay(CheckRun r) {
        for (String w : List.of("w1", "w2", "w3")) {
            long t = r.markTime(w);
            if (t == Long.MAX_VALUE) {
                return "no map change " + w;
            }
            List<AutoQaLine> f = r.matching("RIVALRY REVENGE \\S+ followed", w, null);
            if (f.isEmpty()) {
                return "no follow after map change " + w;
            }
            long d = f.get(0).timeMs() - t;
            if (d < 4000 || d > 10_000) {
                return "followed " + d / 1000.0 + " s after map change " + w + " (want 5-8 s)";
            }
        }
        return null;
    }

    static String refusalNeverSaysYes(CheckRun r) {
        java.util.regex.Pattern yes = java.util.regex.Pattern.compile("(?i)\\b(yes|sure|ok+|omw|coming|lets go|k)\\b");
        List<AutoQaLine> lines = r.lines();
        for (int i = 0; i < lines.size(); i++) {
            AutoQaLine l = lines.get(i);
            if (l.text().matches("CONVO .*INTENT .*: NO \\(.*")) {
                for (int j = i + 1; j < lines.size(); j++) {
                    AutoQaLine n = lines.get(j);
                    if (n.text().startsWith("CONVO SAID ")) {
                        String said = n.message().replaceFirst("^SAID \\S+ -> player \\S+ \\[[^]]*] ", "");
                        if (yes.matcher(said).find() && !said.toLowerCase().matches(".*\\b(no|nah|cant|can't|busy)\\b.*")) {
                            return "a NO was answered with a yes: " + said;
                        }
                        break;
                    }
                }
            }
        }
        return null;
    }

    static String quoteInsideBand(CheckRun r) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("QUOTE \\S+ offers .* at ([\\d,.]+[kmb]?) \\(band ([\\d,.]+[kmb]?)\\.\\.([\\d,.]+[kmb]?)\\)");
        int seen = 0;
        for (AutoQaLine l : r.lines()) {
            java.util.regex.Matcher m = p.matcher(l.text());
            if (m.find()) {
                seen++;
                double price = Expects.parseAmount(m.group(1)), lo = Expects.parseAmount(m.group(2)), hi = Expects.parseAmount(m.group(3));
                if (price < lo || price > hi) {
                    return "quote " + m.group(1) + " outside its band " + m.group(2) + ".." + m.group(3);
                }
            }
        }
        return seen == 0 ? "no QUOTE line with a band" : null;
    }

    /**
     * td-2: a bot selling never counters under its band's low end (0.95x fair, ~5% under). The QUOTE line carries
     * "at <opening> (band <low>..<high>)"; the opening ask sits near the top, so the floor is the band low.
     */
    static String haggleFloor(CheckRun r) {
        java.util.regex.Pattern band = java.util.regex.Pattern.compile("QUOTE \\S+ offers .* at ([\\d,.]+[kmb]?) \\(band ([\\d,.]+[kmb]?)\\.\\.");
        java.util.regex.Pattern hag = java.util.regex.Pattern.compile("QUOTE haggle \\S+ -> \\S+ ([\\d,.]+[kmb]?)");
        double low = Double.NaN;
        for (AutoQaLine l : r.lines()) {
            java.util.regex.Matcher m = band.matcher(l.text());
            if (m.find()) {
                low = Expects.parseAmount(m.group(2));
            }
            java.util.regex.Matcher h = hag.matcher(l.text());
            if (h.find() && !Double.isNaN(low)) {
                double counter = Expects.parseAmount(h.group(1));
                if (counter < low - 0.5) {
                    return "countered " + h.group(1) + ", under its band's low end " + (long) low;
                }
            }
        }
        return Double.isNaN(low) ? "no QUOTE with a band" : null;
    }

    static String timingHuman(CheckRun r) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\+(\\d+) ms");
        for (AutoQaLine l : r.matching("CONVO TIMING ", null, null)) {
            java.util.regex.Matcher m = p.matcher(l.message());
            if (m.find() && Integer.parseInt(m.group(1)) < 900) {
                return "a reply planned at +" + m.group(1) + " ms: " + l.message();
            }
        }
        return null;
    }

    static String noRepeats(CheckRun r) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AutoQaLine l : r.matching(SAID, null, null)) {
            String said = l.message().replaceFirst("^SAID \\S+ -> player \\S+ \\[[^]]*] ", "").toLowerCase().trim();
            if (!seen.add(said)) {
                return "repeated: " + said;
            }
        }
        return null;
    }

    /**
     * cb-1: a pack of BOT_COMBAT_AOE_MIN_MOBS or more (not a boss) gets the AoE, unless the pick says why not
     * (SLOPPY_*, NO_AOE). The AoE on a few mobs or a boss is allowed: the chooser does it on purpose when no
     * single-target skill is ready.
     */
    static String choicesSensible(CheckRun r) {
        int min = Math.max(2, aoeMin());
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("CHOICE \\S+ .*?: (\\d+) in AoE reach(, boss)? -> (AOE|SINGLE) \\S+ \\(([A-Z_]+)");
        int total = 0, wrong = 0;
        String example = null;
        for (AutoQaLine l : r.lines()) {
            java.util.regex.Matcher m = p.matcher(l.text());
            if (!m.find()) {
                continue;
            }
            total++;
            int n = Integer.parseInt(m.group(1));
            boolean boss = m.group(2) != null;
            String pick = m.group(3), why = m.group(4);
            if (why.startsWith("SLOPPY") || why.equals("NO_AOE") || boss) {
                continue;
            }
            if (n >= min && pick.equals("SINGLE")) {
                wrong++;
                example = l.message();
            }
        }
        if (total == 0) {
            return "no CHOICE lines";
        }
        return wrong == 0 ? null : wrong + " of " + total + " packs of " + min + "+ got a single-target skill, e.g. " + example;
    }

    static int aoeMin() {
        try {
            return soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning.aoeMinMobs();
        } catch (RuntimeException | LinkageError e) {
            return 3;
        }
    }

    static String positionsMostlyGood(CheckRun r) {
        // Only "inside a mob's body" counts here. Which way a bot faces when sampled says nothing on its own: a bot
        // walking to its next mob faces away from the one it just passed, and a 52-sample check called 22 of them
        // faults on TEST 2026-10-24. Facing before a swing is what matters and has its own evidence (COMBAT FACE).
        List<AutoQaLine> pos = r.matching("POSITION ", null, null);
        int bad = 0;
        for (AutoQaLine l : pos) {
            if (l.message().contains("INSIDE its body")) {
                bad++;
            }
        }
        return pos.isEmpty() ? "no POSITION lines" : bad * 10 > pos.size() ? bad + " of " + pos.size() + " positions bad" : null;
    }

    static String spreadImproves(CheckRun r) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+) pair\\(s\\) closer than 34 px");
        List<AutoQaLine> t = r.matching("SPREAD map ", null, null);
        if (t.size() < 2) {
            return "fewer than two SPREAD tallies";
        }
        java.util.regex.Matcher a = p.matcher(t.get(0).message()), b = p.matcher(t.get(t.size() - 1).message());
        if (!a.find() || !b.find()) {
            return "SPREAD tally without a pair count";
        }
        int first = Integer.parseInt(a.group(1)), last = Integer.parseInt(b.group(1));
        return last < first || last == 0 ? null : "close pairs " + first + " -> " + last;
    }

    static String pacingSteady(CheckRun r) {
        return pacing(r.matching("ATTACK ", null, null), true);
    }

    static String pacingNotFast(CheckRun r) {
        return pacing(r.matching("ATTACK ", null, null), false);
    }

    private static final java.util.regex.Pattern ATTACK_BOT = java.util.regex.Pattern.compile("^ATTACK (\\S+) ");
    private static final java.util.regex.Pattern BUSY_GAP = java.util.regex.Pattern.compile("busy (\\d+)ms gap (\\d+)ms");

    /**
     * Swing rhythm per bot: a gap is the time since that bot's previous swing, so it is judged against the previous
     * swing's busy time. rc6 auto-QA triage (t-at-1, t-bk-1): one-hit kills of low mobs meant most gaps were a bot
     * waiting for a respawn or walking to the next mob (686 of 695 swings "killed"), and the showcase's own 1.5 s
     * round gap counted as a hitch. Gaps after a kill are skipped; a hitch is a gap over busy + 3 s after a live hit.
     * rc6 say-do triage (t-bk-1, run of 2026-10-23 22:00 UTC, "1 of 1 swings ... over 3 s"): both long gaps were a
     * showcase bot logging "skill ... not sent: no targetable mobs within 700px" every 400 ms until a mob walked back
     * into reach (the other showcase bots had cleared its ledge): waiting for a mob, not a hitch. A gap the bot spent
     * with nothing in reach is skipped too.
     */
    static String pacing(List<AutoQaLine> lines, boolean hitches) {
        Map<String, int[]> prev = new HashMap<>(); // bot -> {busy, killed or nothing in reach since}
        int seen = 0, fast = 0, slow = 0;
        String example = null;
        for (AutoQaLine l : lines) {
            java.util.regex.Matcher who = ATTACK_BOT.matcher(l.text());
            if (!who.find()) {
                continue;
            }
            if (l.message().contains(" not sent: ")) {
                int[] p = prev.get(who.group(1));
                if (p != null) {
                    p[1] = 1; // nothing it could hit: the next gap is a wait for a mob, not rhythm
                }
                continue;
            }
            java.util.regex.Matcher m = BUSY_GAP.matcher(l.message());
            if (!m.find()) {
                continue;
            }
            int busy = Integer.parseInt(m.group(1)), gap = Integer.parseInt(m.group(2));
            int[] before = prev.put(who.group(1), new int[]{busy, l.message().contains(" killed") ? 1 : 0});
            if (before == null || before[1] == 1) {
                continue; // a bot's first swing, the one after a kill, or after nothing was in reach: not rhythm
            }
            seen++;
            if (gap < before[0] * 0.6) {
                fast++;
                example = l.message();
            } else if (gap > before[0] + 3000 && gap < 10_000) {
                slow++;
            }
        }
        if (seen == 0) {
            return null; // every swing killed or retargeted: nothing to judge (the line count says whether it swung)
        }
        if (fast > 0) {
            return fast + " of " + seen + " swings came faster than 60% of the swing before, e.g. " + example;
        }
        return hitches && slow * 5 > seen ? slow + " of " + seen + " swings at a live mob waited over 3 s past the swing"
                : null;
    }
}
