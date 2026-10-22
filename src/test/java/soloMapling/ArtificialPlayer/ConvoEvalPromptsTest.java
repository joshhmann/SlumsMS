package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotConvo.ConvoContract;
import soloMapling.ArtificialPlayer.BotConvo.ConvoPersona;
import soloMapling.ArtificialPlayer.BotConvo.ConvoTune;
import soloMapling.ArtificialPlayer.BotConvo.RelationshipCard;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotChatRouting;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;
import soloMapling.server.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner report 2026-10-22: the offline conversation eval (tools/convo/eval). Builds, for every scenario in
 * {@code tools/convo/eval/scenarios.json}, the prompt the server sends with this code ("after") and the prompt the
 * 1.5.0-rc5 code sent ("before": the map's last four lines folded into the system prompt as "Recent chat around you",
 * no "(you)", no "You just said", the bot's own public line not a turn, temperature 1.0). With
 * {@code -Dconvo.eval.out=<file>} it writes them as JSON for {@code tools/convo/eval/run_eval.py}; always it checks
 * the "after" prompts carry what the bot needs.
 */
class ConvoEvalPromptsTest {

    @AfterEach
    void reset() {
        MapChatLog.resetForTest();
    }

    private static Character character(int id, String name, int level, int job) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(level);
        chr.setJob(Job.getById(job));
        return chr;
    }

    @Test
    void theEvalPromptsAreTheServersPrompts() throws Exception {
        Path scenarios = Path.of("tools/convo/eval/scenarios.json");
        List<?> all = (List<?>) Json.parse(Files.readString(scenarios, StandardCharsets.UTF_8));
        List<Object> out = new ArrayList<>();
        for (Object o : all) {
            out.add(build((Map<?, ?>) o));
            MapChatLog.resetForTest();
        }
        Map<?, ?> east = (Map<?, ?>) out.get(0);
        List<?> after = (List<?>) ((Map<?, ?>) east.get("after")).get("messages");
        String last = (String) ((Map<?, ?>) after.get(after.size() - 1)).get("content");
        assertTrue(last.contains("Kiki (you): anyone from the east coast?"), last);
        assertTrue(last.contains("You just said: \"anyone from the east coast?\". Josh answered: \"im from the east\"."),
                last);
        assertEquals("assistant", ((Map<?, ?>) after.get(after.size() - 2)).get("role"));
        String property = System.getProperty("convo.eval.out");
        if (property != null && !property.isBlank()) {
            Files.writeString(Path.of(property), Json.write(out), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> build(Map<?, ?> sc) {
        Map<?, ?> b = (Map<?, ?>) sc.get("bot");
        Map<?, ?> p = (Map<?, ?>) sc.get("player");
        int slot = ((Number) b.get("slot")).intValue();
        Character bot = character(BotStore.BOT_ID_BASE + slot, (String) b.get("name"), ((Number) b.get("level")).intValue(),
                ((Number) b.get("job")).intValue());
        Character player = character(7, (String) p.get("name"), ((Number) p.get("level")).intValue(),
                ((Number) p.get("job")).intValue());
        long now = System.currentTimeMillis();
        List<?> chat = (List<?>) sc.get("chat");
        String lastLine = null;
        List<String> oldRecent = new ArrayList<>();
        int others = 100;
        for (int i = 0; i < chat.size(); i++) {
            List<?> c = (List<?>) chat.get(i);
            String name = (String) c.get(0);
            String kind = (String) c.get(1);
            String text = (String) c.get(2);
            int id = "self".equals(kind) ? bot.getId() : name.equals(player.getName()) ? player.getId() : others++;
            MapChatLog.record(1, bot.getMapId(), id, name, text, !"player".equals(kind), now - (chat.size() - i) * 6_000L);
            oldRecent.add(name + ": " + text);
            if (id == player.getId()) {
                lastLine = text;
            }
        }
        String line = BotChatRouting.withoutName(lastLine, bot.getName());
        List<BotBrain.Turn> history = new ArrayList<>();
        for (Object h : (List<?>) sc.get("history")) {
            List<?> t = (List<?>) h;
            history.add("user".equals(t.get(0)) ? BotBrain.Turn.user((String) t.get(1))
                    : BotBrain.Turn.assistant((String) t.get(1)));
        }

        String activity = BotChatPrompt.activityPhrase((String) b.get("activity"));
        String map = (String) b.get("map");
        BotChatPrompt.Context context = new BotChatPrompt.Context(bot.getName(), bot.getLevel(),
                constants.game.GameConstants.getJobName(bot.getJob().getId()).toLowerCase(), map, activity, null, BotChatPrompt.mood(slot, activity, false, now),
                player.getName(), player.getLevel(), constants.game.GameConstants.getJobName(player.getJob().getId()).toLowerCase(), List.of(), List.of(), null);
        String card = RelationshipCard.build(new RelationshipCard.Data(player.getName(), AffinityState.NEUTRAL, 0,
                List.of(), null, null, map, List.of(), null, null, activity, null), now, ZoneId.systemDefault());
        String voice = ConvoPersona.of(slot, 0.4).prompt(context.job(), false) + ConvoTune.promptLines();
        BotPersona.Persona persona = BotPersona.generate(slot);

        // after: this code, as BotConversation.start/ask build it
        BotConversation.Heard heard = BotConversation.heard(bot, player, now);
        List<BotBrain.Turn> afterHistory = new ArrayList<>(history);
        if (heard.justSaid() != null && !BotConversation.alreadyInHistory(afterHistory, heard.justSaid())) {
            afterHistory.add(BotBrain.Turn.assistant(heard.justSaid()));
        }
        afterHistory.add(BotBrain.Turn.user(line));
        String afterSystem = BotChatPrompt.system(context, persona, 0.4, card, voice,
                BotChatPrompt.asksAboutTheWorld(line)) + " " + ConvoContract.instructions();
        List<BotBrain.Turn> after = BotChatPrompt.conversation(afterSystem, afterHistory, heard.transcript(),
                player.getName(), heard.justSaid());

        // before: 1.5.0-rc5 - the last four lines (2 min) as one sentence before the world facts, the line alone
        String full = BotChatPrompt.system(context, persona, 0.4, card, voice, true);
        List<String> four = oldRecent.subList(Math.max(0, oldRecent.size() - 4), oldRecent.size());
        int at = full.indexOf(BotChatPrompt.WORLD_FACTS);
        String beforeSystem = full.substring(0, at) + "Recent chat around you: " + String.join(" / ", four) + ". "
                + full.substring(at) + " " + ConvoContract.instructions();
        List<BotBrain.Turn> beforeHistory = new ArrayList<>(history);
        beforeHistory.add(BotBrain.Turn.user(line));
        List<BotBrain.Turn> before = BotChatPrompt.conversation(beforeSystem, beforeHistory);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sc.get("id"));
        m.put("kind", sc.get("kind"));
        m.put("expect", sc.get("expect"));
        m.put("line", line);
        m.put("before", Map.of("temperature", 1.0, "messages", wire(before)));
        m.put("after", Map.of("temperature", 0.7, "messages", wire(after)));
        return m;
    }

    private static List<Object> wire(List<BotBrain.Turn> turns) {
        List<Object> out = new ArrayList<>();
        for (BotBrain.Turn t : turns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", t.role().name().toLowerCase());
            m.put("content", t.content());
            out.add(m);
        }
        return out;
    }
}
