package soloMapling.qa;

import client.Character;
import soloMapling.Economy.MegaBuyDesk;
import soloMapling.Economy.MegaBuyGame;
import soloMapling.Economy.board.BoardWorld;
import soloMapling.Economy.board.GameBoardWorld;
import soloMapling.Economy.board.SiteBoard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 1.6.0 site board (owner, 2026-10-26): bd-1 plays the website's part on scratch folders (never the real inbox or
 * outbox). A bot next to the GM holds Gloves ATT 60%; the scenario writes a posts.json with the GM's WTB for them and
 * an alerts.json with an alert on the cheapest item in the FM right now, runs one board over them (offers after 1-3 s)
 * and checks: the post is on board.json, a bot offers (whisper, or a note if the whisper is held), the alert fires once
 * and is in alerts-fired.json, and no outbox file holds the account name. Ends with BD1 PASS or BD1 FAIL.
 */
final class BoardScenarios {
    static final int GLOVE_60 = 2040804;

    /** The board's game; tests substitute one. */
    static volatile Function<Character, BoardWorld> worldFor = gm -> new GameBoardWorld() {
        @Override
        public Map<Integer, Long> lowestListings() {
            Map<Integer, Long> low = new HashMap<>(super.lowestListings());
            if (low.isEmpty()) {
                low.put(GLOVE_60, 2_500_000L); // an empty FM: a staged listing so the alert has something to see
            }
            return low;
        }
    };

    private BoardScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Bd1Board());
    }

    static final class Bd1Board implements QaScenario {
        public String id() { return "bd-1"; }
        public String title() { return "Site board: a staged post gets a bot's offer, an alert fires once"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(QaLog.Topic.TRADE); }
        public int minutes(String[] args) { return 3; }

        public List<String> stage(QaContext ctx) {
            QaRun run = ctx.run();
            Character gm = ctx.gmNow() == null ? ctx.gm() : ctx.gmNow();
            List<Integer> bots = ctx.world().spawnIdleBots(gm, gm.getPosition(), 1);
            if (bots.isEmpty()) {
                throw new QaRefusal("Could not spawn a bot here.");
            }
            ctx.despawnAtStop(bots, "board seller");
            int botId = bots.get(0);
            ctx.log("bot " + botId + " given " + ctx.world().giveBotItem(botId, GLOVE_60, 1) + " x " + GLOVE_60);
            MegaBuyGame.qaAddBot(botId);
            GameBoardWorld.qaAddBot(botId);
            ctx.onStop("forget the staged board seller", () -> {
                MegaBuyGame.qaRemoveBot(botId);
                GameBoardWorld.qaRemoveBot(botId);
            });
            int account = gm.getAccountID();
            String accountName = gm.getClient() == null ? null : gm.getClient().getAccountName();
            String gmName = gm.getName();
            BoardWorld world = worldFor.apply(gm);
            Thread t = new Thread(() -> play(run, world, account, accountName, gmName), "qa-bd-1");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            return List.of("A bot next to you holds Gloves ATT 60%. A WTB for them goes on a scratch board as your post,",
                    "with a price alert on the cheapest FM item. Expect a whisper (or note) from the bot in 1-3 s, then a",
                    "yellow [Market] price alert. The log ends with BD1 PASS or BD1 FAIL.");
        }

        static void play(QaRun run, BoardWorld world, int account, String accountName, String gmName) {
            try {
                Path root = Files.createTempDirectory("slums-bd1-");
                Path in = root.resolve("inbox"), out = root.resolve("outbox");
                Files.createDirectories(in);
                String postId = "p" + hex12();
                run.note("BD1", "START dirs " + root + " post " + postId);
                Files.writeString(in.resolve("posts.json"), "{\"schema\":1,\"posts\":[{\"id\":\"" + postId
                        + "\",\"account\":" + account + ",\"character\":\"" + gmName + "\",\"kind\":\"WTB\",\"itemId\":"
                        + GLOVE_60 + ",\"qty\":1,\"price\":3000000,\"createdAt\":" + (world.now() - 1000) + "}]}",
                        StandardCharsets.UTF_8);
                Map.Entry<Integer, Long> cheapest = world.lowestListings().entrySet().stream()
                        .min(Map.Entry.comparingByValue()).orElse(Map.entry(GLOVE_60, 2_500_000L));
                String alertId = "a" + hex12();
                Files.writeString(in.resolve("alerts.json"), "{\"schema\":1,\"accounts\":{\"" + account
                        + "\":{\"watch\":[" + cheapest.getKey() + "],\"alerts\":[{\"id\":\"" + alertId + "\",\"itemId\":"
                        + cheapest.getKey() + ",\"dir\":\"below\",\"price\":" + cheapest.getValue()
                        + ",\"armed\":true}]}}}", StandardCharsets.UTF_8);
                java.util.concurrent.CopyOnWriteArrayList<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
                SiteBoard.Engine e = new SiteBoard.Engine(in, out, root.resolve("state.json"), world,
                        new MegaBuyDesk.Limits(0, 1000, 1000, 1000, 0)).forQa(1000, 3000, l -> {
                            lines.add(l);
                            run.note("BD1", l);
                        });
                e.poll();
                e.cycle();
                e.cycle(); // a second run must not fire the alert again
                long until = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < until && lines.stream().noneMatch(l -> l.startsWith("OFFER ")
                        || l.startsWith("DROP "))) {
                    Thread.sleep(250);
                }
                check(run, out, postId, alertId, accountName, lines);
            } catch (Exception ex) {
                run.note("BD1", "FAIL " + ex);
            }
        }

        static void check(QaRun run, Path out, String postId, String alertId, String accountName, List<String> lines)
                throws java.io.IOException {
            StringBuilder fail = new StringBuilder();
            String board = Files.readString(out.resolve("board.json"));
            if (!board.contains("\"" + postId + "\"")) {
                fail.append(" post-not-on-board");
            }
            String fired = Files.readString(out.resolve("alerts-fired.json"));
            if (!fired.contains("\"" + alertId + "\"")) {
                fail.append(" alert-not-in-alerts-fired");
            }
            long fires = lines.stream().filter(l -> l.startsWith("ALERT FIRED " + alertId + " ")).count();
            if (fires != 1) {
                fail.append(" alert-fired-").append(fires).append("-times");
            }
            if (lines.stream().noneMatch(l -> l.startsWith("OFFER "))) {
                fail.append(" no-offer");
            }
            for (String f : SiteBoard.OUTBOX_FILES) {
                if (accountName != null && !accountName.isBlank()
                        && Files.readString(out.resolve(f)).contains("\"" + accountName + "\"")) {
                    fail.append(" account-name-in-").append(f);
                }
            }
            run.note("BD1", fail.isEmpty() ? "PASS post, offer, alert once, no account names" : "FAIL" + fail);
        }

        private static String hex12() {
            return String.format("%012x", ThreadLocalRandom.current().nextLong() & 0xffffffffffffL);
        }
    }
}
