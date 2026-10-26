package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1.6.0 (owner 2026-10-26): the social trace's running summary ({@code social-trace-latest.json}) from its closed
 * entries: how many per kind and topic, per stage the ok / fail / none / n/a counts and rates, the top fail and none
 * reasons, and up to {@link #EXAMPLES} example entries per stage problem. Pure.
 */
public final class SocialTraceSummary {

    static final int EXAMPLES = 10;
    static final int TOP = 10;
    static final List<String> STATUSES = List.of("ok", "fail", "none", "n/a");

    private SocialTraceSummary() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> build(List<Map<String, Object>> entries, Map<String, Object> meta, long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", SocialTraceJudge.iso(now));
        out.putAll(meta);
        out.put("entries", entries.size());
        Map<String, Integer> byKind = new TreeMap<>();
        Map<String, Integer> byTopic = new TreeMap<>();
        Map<String, Integer> sources = new TreeMap<>();
        Map<String, Map<String, Integer>> stageCounts = new LinkedHashMap<>();
        for (SocialTraceEntry.Stage s : SocialTraceEntry.Stage.values()) {
            Map<String, Integer> c = new LinkedHashMap<>();
            STATUSES.forEach(st -> c.put(st, 0));
            stageCounts.put(s.name(), c);
        }
        Map<String, Integer> failReasons = new LinkedHashMap<>();
        Map<String, Integer> noneReasons = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> decisions = new TreeMap<>();
        Map<String, List<Object>> examples = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            String kind = String.valueOf(e.get("kind"));
            String topic = String.valueOf(e.get("topic"));
            byKind.merge(kind, 1, Integer::sum);
            byTopic.merge(kind + " " + topic, 1, Integer::sum);
            Object st = e.get("stages");
            if (!(st instanceof Map<?, ?> stages)) {
                continue;
            }
            for (Map.Entry<?, ?> se : stages.entrySet()) {
                if (!(se.getValue() instanceof Map<?, ?> v)) {
                    continue;
                }
                String stage = String.valueOf(se.getKey());
                String status = String.valueOf(v.get("status"));
                String reason = String.valueOf(v.get("reason"));
                Map<String, Integer> c = stageCounts.get(stage);
                if (c != null) {
                    c.merge(status, 1, Integer::sum);
                }
                if ("COMPOSE".equals(stage) && "thread".equals(kind) && v.get("source") != null) {
                    sources.merge(String.valueOf(v.get("source")), 1, Integer::sum);
                }
                if ("DECIDE".equals(stage)) {
                    String d = v.get("decision") == null ? "none" : String.valueOf(v.get("decision"));
                    decisions.computeIfAbsent(kind + " " + topic, k -> new TreeMap<>()).merge(d, 1, Integer::sum);
                }
                if ("fail".equals(status) || "none".equals(status)) {
                    String key = stage + ": " + reasonKey(reason);
                    ("fail".equals(status) ? failReasons : noneReasons).merge(key, 1, Integer::sum);
                    List<Object> ex = examples.computeIfAbsent(stage + " " + status, k -> new ArrayList<>());
                    if (ex.size() < EXAMPLES) {
                        ex.add(example(e, reason));
                    }
                }
            }
        }
        out.put("byKind", byKind);
        out.put("byTopic", byTopic);
        out.put("threadSources", sources);
        Map<String, Object> stages = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> s : stageCounts.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>(s.getValue());
            int n = entries.size();
            m.put("okRate", rate(s.getValue().get("ok"), n));
            m.put("failRate", rate(s.getValue().get("fail"), n));
            m.put("noneRate", rate(s.getValue().get("none"), n));
            stages.put(s.getKey(), m);
        }
        out.put("stages", stages);
        out.put("decisions", decisions);
        out.put("topFail", top(failReasons));
        out.put("topNone", top(noneReasons));
        out.put("examples", examples);
        return out;
    }

    /** A reason with its particulars folded ("said twice: \"...\"" -> "said twice: \"…\"", numbers -> N). */
    static String reasonKey(String reason) {
        if (reason == null) {
            return "?";
        }
        String r = reason.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("\\d+(\\.\\d+)?", "N");
        int colon = r.indexOf(": ");
        // "bond unchanged (...): A->B 1.0->1.0" keeps its head; a reason whose detail is after a colon folds it
        if (colon > 0 && !r.startsWith("off topic") && r.length() - colon > 40) {
            r = r.substring(0, colon);
        }
        return r.length() > 160 ? r.substring(0, 160) : r;
    }

    private static Double rate(Integer count, int n) {
        return n == 0 || count == null ? null : Math.round(1000.0 * count / n) / 1000.0;
    }

    private static List<Object> top(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP, list.size()); i++) {
            out.add(Map.of("reason", list.get(i).getKey(), "count", list.get(i).getValue()));
        }
        return out;
    }

    private static Map<String, Object> example(Map<String, Object> e, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.get("id"));
        m.put("topic", e.get("topic"));
        Object a = e.get("a") instanceof Map<?, ?> am ? am.get("name") : null;
        Object b = e.get("b") instanceof Map<?, ?> bm ? bm.get("name") : null;
        m.put("pair", a + " + " + b);
        m.put("reason", reason);
        List<Object> lines = new ArrayList<>();
        if (e.get("lines") instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> lm) {
                    lines.add(lm.get("who") + ": " + lm.get("text"));
                }
            }
        }
        m.put("lines", lines);
        return m;
    }
}
