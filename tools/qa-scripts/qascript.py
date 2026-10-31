"""Validator for auto-QA client scripts (qa-scripts/<id>.yaml). Ticket 166/170.

Two passes:
  1. the JSON Schema in docs/qa/client-scripts/schema.json (a small built-in interpreter of the subset the schema
     uses, so nothing beyond PyYAML is needed);
  2. the rules a schema cannot say: waits on signals only (every sleep needs a real reason, at most 30 s of sleep
     a script), timeouts on every wait, anchors and windows from docs/qa/client-scripts/anchors.yaml, references
     between steps, captures and checks, ${placeholders}, one vault alias per client and no secrets in the file,
     and a channels.ready wait before every channel click (the b222/166 "empty channel list" race).

Usage: tools/qa-scripts/validate [files...]   (default: every qa-scripts/*.yaml). Exit 1 on any error.
"""
import json
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
SCHEMA = REPO / "docs/qa/client-scripts/schema.json"
ANCHORS = REPO / "docs/qa/client-scripts/anchors.yaml"
SCRIPTS = REPO / "qa-scripts"

MAX_SLEEP_TOTAL_MS = 30_000
VAGUE_REASONS = {"wait", "settle", "just in case", "to be safe", "give it time", "let it load", "wait a bit"}
SECRET_WORDS = re.compile(r"(?i)\b(password|passwd|pwd|pic|secret|token|api[_-]?key)\b\s*[:=]")
PLACEHOLDER = re.compile(r"\$\{([a-z][a-z0-9_]*)(?:\.([a-z]+))?\}")
CLIENT_FIELDS = {"name", "id"}
# client pipe signals the QA module sends today (client-170-qa-module 502f9f5); the rest of the schema's names are proposed
BUILT_SIGNALS = {"worldselect.ready", "channels.ready", "charselect.ready", "field.ready"}
RETRYABLE = {"select_world", "select_channel", "click", "key"}
# step kinds the client-mcp driver cannot do yet (the Silvia worker's run_script must add them)
PROPOSED_STEPS = {"click_npc", "wheel", "type", "key_down", "key_up"}


# ── pass 1: the schema subset ─────────────────────────────────────────────────────────────────────

class SchemaChecker:
    def __init__(self, schema):
        self.root = schema

    def resolve(self, ref):
        if not ref.startswith("#/"):
            raise ValueError("only local refs: " + ref)
        node = self.root
        for part in ref[2:].split("/"):
            node = node[part]
        return node

    def errors(self, inst, schema, path="$"):
        if "$ref" in schema:
            return self.errors(inst, self.resolve(schema["$ref"]), path)
        out = []
        t = schema.get("type")
        if t is not None and not _is_type(inst, t):
            return ["%s: expected %s, got %s" % (path, t, _type_name(inst))]
        if "const" in schema and inst != schema["const"]:
            out.append("%s: must be %r" % (path, schema["const"]))
        if "enum" in schema and inst not in schema["enum"]:
            out.append("%s: %r is not one of %s" % (path, inst, ", ".join(map(str, schema["enum"]))))
        if isinstance(inst, str):
            if "minLength" in schema and len(inst) < schema["minLength"]:
                out.append("%s: shorter than %d" % (path, schema["minLength"]))
            if "maxLength" in schema and len(inst) > schema["maxLength"]:
                out.append("%s: longer than %d" % (path, schema["maxLength"]))
            if "pattern" in schema and not re.search(schema["pattern"], inst):
                out.append("%s: %r does not match %s" % (path, inst, schema["pattern"]))
        if _is_number(inst):
            if "minimum" in schema and inst < schema["minimum"]:
                out.append("%s: %s is below %s" % (path, inst, schema["minimum"]))
            if "maximum" in schema and inst > schema["maximum"]:
                out.append("%s: %s is above %s" % (path, inst, schema["maximum"]))
        if isinstance(inst, list):
            if "minItems" in schema and len(inst) < schema["minItems"]:
                out.append("%s: needs at least %d items" % (path, schema["minItems"]))
            if "maxItems" in schema and len(inst) > schema["maxItems"]:
                out.append("%s: at most %d items" % (path, schema["maxItems"]))
            if schema.get("uniqueItems") and len({json.dumps(i, sort_keys=True) for i in inst}) != len(inst):
                out.append("%s: items repeat" % path)
            if "items" in schema:
                for i, item in enumerate(inst):
                    out += self.errors(item, schema["items"], "%s[%d]" % (path, i))
        if isinstance(inst, dict):
            for k in schema.get("required", []):
                if k not in inst:
                    out.append("%s: missing %s" % (path, k))
            if "minProperties" in schema and len(inst) < schema["minProperties"]:
                out.append("%s: needs at least %d entries" % (path, schema["minProperties"]))
            if "maxProperties" in schema and len(inst) > schema["maxProperties"]:
                out.append("%s: at most %d entries" % (path, schema["maxProperties"]))
            props = schema.get("properties", {})
            for k in inst:
                if not isinstance(k, str):
                    out.append("%s: key %r is not text (YAML reads on/off/yes/no as true/false: quote it)" % (path, k))
            for k, v in inst.items():
                if not isinstance(k, str):
                    continue
                if "propertyNames" in schema:
                    out += self.errors(k, schema["propertyNames"], "%s.<key %s>" % (path, k))
                if k in props:
                    out += self.errors(v, props[k], "%s.%s" % (path, k))
                elif schema.get("additionalProperties") is False:
                    out.append("%s: unknown key %s" % (path, k))
                elif isinstance(schema.get("additionalProperties"), dict):
                    out += self.errors(v, schema["additionalProperties"], "%s.%s" % (path, k))
        if "oneOf" in schema:
            out += self._one_of(inst, schema["oneOf"], path)
        return out

    def _one_of(self, inst, branches, path):
        results = [self.errors(inst, b, path) for b in branches]
        passing = [r for r in results if not r]
        if len(passing) == 1:
            return []
        if len(passing) > 1:
            return ["%s: matches more than one form" % path]
        # pick the branch whose discriminator (a const property) matches, for a readable error
        if isinstance(inst, dict):
            for b, r in zip(branches, results):
                b = self.resolve(b["$ref"]) if "$ref" in b else b
                for k, p in b.get("properties", {}).items():
                    if "const" in p and inst.get(k) == p["const"]:
                        return r
                    if "enum" in p and k in ("type",) and inst.get(k) in p["enum"]:
                        return r
            for b, r in zip(branches, results):
                b = self.resolve(b["$ref"]) if "$ref" in b else b
                own = [k for k in b.get("required", []) if k not in ("id",)]
                if own and all(k in inst for k in own):
                    return r
            for key in ("do", "type", "check"):
                if key in inst:
                    return ["%s: unknown %s %r" % (path, key, inst[key])]
        return ["%s: matches none of the allowed forms (closest: %s)" % (path, min(results, key=len)[0])]

    # YAML 1.1 reads a bare on/off/yes/no key as a boolean; the schema uses "at" for that reason


def _is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _is_type(v, t):
    if isinstance(t, list):
        return any(_is_type(v, x) for x in t)
    return {"object": isinstance(v, dict), "array": isinstance(v, list), "string": isinstance(v, str),
            "integer": isinstance(v, int) and not isinstance(v, bool), "number": _is_number(v),
            "boolean": isinstance(v, bool), "null": v is None}[t]


def _type_name(v):
    for t in ("boolean", "integer", "number", "string", "array", "object", "null"):
        if _is_type(v, t):
            return t
    return type(v).__name__


# ── pass 2: rules ─────────────────────────────────────────────────────────────────────────────────

def t_kind(step):
    return (step.get("signal") or {}).get("type")


def load_anchors(path=ANCHORS):
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    return data.get("anchors", {}), set(data.get("windows", []))


def secret_errors(raw_text):
    """Checked before anything else: a file that names a credential is refused whatever else is wrong."""
    out = []
    for line_no, line in enumerate(raw_text.splitlines(), 1):
        m = SECRET_WORDS.search(line)
        if m:
            out.append("line %d: looks like a credential (%s ...); use a vault alias" % (line_no, m.group(1)))
    return out


def rule_errors(doc, raw_text, file_stem, anchors, windows):
    errors, warnings = [], []
    clients = doc.get("clients", {})
    aliases = set(clients)
    steps = doc.get("steps", [])
    needs = set(doc.get("needs", []))

    if file_stem is not None and doc.get("id") != file_stem:
        errors.append("id %r must match the file name %r" % (doc.get("id"), file_stem))
    if len(clients) > 1 and "two_clients" not in needs:
        errors.append("two clients: add two_clients to needs")
    vaults = [c.get("account", {}).get("vault") for c in clients.values()]
    if len(set(vaults)) != len(vaults):
        errors.append("each client needs its own vault account")

    ids, wait_ids, bound = {}, set(), set()
    sleep_total = 0
    channel_ready = {a: False for a in aliases}
    logged_in = {a: False for a in aliases}
    proposed_used, proposed_signals, proposed_steps = [], [], []
    held = {}

    def client_of(step, where):
        c = step.get("client")
        if c is None:
            if len(aliases) == 1:
                return next(iter(aliases))
            errors.append("%s: say which client (two clients)" % where)
            return None
        if c not in aliases:
            errors.append("%s: unknown client %r" % (where, c))
            return None
        return c

    def placeholders(text, where):
        for name, field in PLACEHOLDER.findall(text or ""):
            if field:
                if name not in aliases:
                    errors.append("%s: ${%s.%s}: no client %r" % (where, name, field, name))
                elif field not in CLIENT_FIELDS:
                    errors.append("%s: ${%s.%s}: a client has only %s" % (where, name, field, "/".join(sorted(CLIENT_FIELDS))))
            elif name not in bound:
                errors.append("%s: ${%s} is not bound by an earlier wait" % (where, name))
        if re.search(r"\$\{(?![a-z][a-z0-9_]*(\.[a-z]+)?\})", text or ""):
            errors.append("%s: malformed placeholder" % where)

    def check_regex(rx, where):
        try:
            return re.compile(PLACEHOLDER.sub("x", rx))
        except re.error as e:
            errors.append("%s: bad regex (%s)" % (where, e))
            return None

    def char_ref(c, where):
        if c not in aliases and c != "any":
            errors.append("%s: char %r is not a client" % (where, c))

    for i, s in enumerate(steps):
        where = "steps[%d]%s" % (i, " (%s)" % s["id"] if "id" in s else "")
        sid = s.get("id")
        if sid:
            if sid in ids:
                errors.append("%s: id %r used twice" % (where, sid))
            ids[sid] = i
        do = s.get("do")
        c = client_of(s, where)
        if do in PROPOSED_STEPS:
            proposed_steps.append(do)
        if do == "key_down" and c:
            if (c, s.get("key")) in held:
                errors.append("%s: %s is already held" % (where, s.get("key")))
            held[(c, s.get("key"))] = where
        if do == "key_up" and c:
            if held.pop((c, s.get("key")), None) is None:
                errors.append("%s: %s was not held" % (where, s.get("key")))
        if do == "wheel" and s.get("anchor") not in anchors:
            errors.append("%s: unknown anchor %r" % (where, s.get("anchor")))
        if do == "type":
            placeholders(s.get("text"), where)
        if do == "login":
            if c:
                logged_in[c] = True
                channel_ready[c] = False
        elif c and not logged_in.get(c) and do not in ("mark", "sleep"):
            errors.append("%s: %s before %s logs in" % (where, do, c))
        if do == "select_world" and c:
            channel_ready[c] = False
        if do == "select_channel" and c:
            if not channel_ready[c]:
                errors.append("%s: wait for channels.ready (client %s) before picking a channel" % (where, c))
            if s.get("channel") != clients[c].get("channel"):
                warnings.append("%s: channel %s differs from the client's channel %s" % (where, s.get("channel"), clients[c].get("channel")))
        if do == "click":
            a = s.get("anchor")
            if a not in anchors:
                errors.append("%s: unknown anchor %r (add it to docs/qa/client-scripts/anchors.yaml)" % (where, a))
            else:
                if anchors[a].get("status") != "calibrated":
                    proposed_used.append(a)
                if a.startswith("channel.") and a != "channel.confirm" and c and not channel_ready[c]:
                    errors.append("%s: wait for channels.ready before clicking %s" % (where, a))
        if do == "chat":
            if s.get("text", "").startswith("!"):
                errors.append("%s: a ! command is do: gm" % where)
            placeholders(s.get("text"), where)
        if do == "gm":
            placeholders(s.get("line"), where)
        if do == "sleep":
            sleep_total += s.get("ms", 0)
            reason = (s.get("reason") or "").strip().lower().rstrip(".")
            if reason in VAGUE_REASONS or len(reason) < 15:
                errors.append("%s: a timer needs a real reason (what has no signal and why)" % where)
        if do == "wait":
            wait_ids.add(sid)
            sig = s.get("signal", {})
            if "timeout_ms" not in s:
                errors.append("%s: a wait needs timeout_ms" % where)
            retry = s.get("retry")
            if retry:
                if t_kind(s) != "client_signal":
                    errors.append("%s: retry is for client signals only" % where)
                target = retry.get("step")
                if target not in ids or ids[target] >= i:
                    errors.append("%s: retry step %r is not an earlier step" % (where, target))
                elif steps[ids[target]].get("do") not in RETRYABLE:
                    errors.append("%s: retry may resend only %s" % (where, ", ".join(sorted(RETRYABLE))))
                elif retry.get("every_ms", 0) * retry.get("times", 0) >= s.get("timeout_ms", 0):
                    errors.append("%s: every_ms x times must fit inside timeout_ms" % where)
            if s.get("armed_at") and s["armed_at"] not in ids:
                errors.append("%s: armed_at %r is not an earlier step" % (where, s["armed_at"]))
            t = sig.get("type")
            name = sig.get("name")
            if t == "client_signal":
                if name not in BUILT_SIGNALS:
                    proposed_signals.append(name)
                if name == "channels.ready" and c:
                    channel_ready[c] = True
                if name in ("window.open", "window.closed"):
                    if sig.get("window") not in windows:
                        errors.append("%s: unknown window %r (anchors.yaml windows)" % (where, sig.get("window")))
                elif "window" in sig:
                    errors.append("%s: window belongs to window.open/window.closed" % where)
                if name == "chat.received" and "regex" not in sig:
                    errors.append("%s: chat.received needs regex" % where)
                if name != "chat.received" and "regex" in sig:
                    errors.append("%s: regex belongs to chat.received" % where)
                if name != "field.ready" and "map_id" in sig:
                    errors.append("%s: map_id belongs to field.ready" % where)
            rx = None
            if "regex" in sig:
                placeholders(sig.get("regex"), where)
                rx = check_regex(sig.get("regex", ""), where)
            if t == "server_line":
                char_ref(sig.get("char"), where)
            for var, group in (s.get("bind") or {}).items():
                if rx is None or group not in rx.groupindex:
                    errors.append("%s: bind %s: the regex has no group (?P<%s>...)" % (where, var, group))
                bound.add(var)

    if sleep_total > MAX_SLEEP_TOTAL_MS:
        errors.append("sleeps add up to %d ms (at most %d): wait on signals" % (sleep_total, MAX_SLEEP_TOTAL_MS))
    if proposed_used and "proposed_anchors" not in needs:
        errors.append("uses proposed anchors %s: add proposed_anchors to needs" % ", ".join(sorted(set(proposed_used))))
    elif proposed_used:
        warnings.append("proposed anchors (Client lane must calibrate): " + ", ".join(sorted(set(proposed_used))))

    for (c, k), where in held.items():
        errors.append("%s: %s is never released (key_up)" % (where, k))
    if proposed_steps and "proposed_steps" not in needs:
        errors.append("uses proposed steps %s: add proposed_steps to needs" % ", ".join(sorted(set(proposed_steps))))
    elif proposed_steps:
        warnings.append("proposed steps (the Silvia worker must add them): " + ", ".join(sorted(set(proposed_steps))))
    if proposed_signals and "proposed_signals" not in needs:
        errors.append("uses proposed client signals %s: add proposed_signals to needs" % ", ".join(sorted(set(proposed_signals))))
    elif proposed_signals:
        warnings.append("proposed client signals (QA module must add them): " + ", ".join(sorted(set(proposed_signals))))

    cap_ids = set()
    for j, cap in enumerate(doc.get("captures", []) or []):
        where = "captures[%d] (%s)" % (j, cap.get("id"))
        if cap.get("id") in cap_ids or cap.get("id") in ids:
            errors.append("%s: id used twice" % where)
        cap_ids.add(cap.get("id"))
        if "client" in cap:
            client_of(cap, where)
        elif len(aliases) > 1:
            errors.append("%s: say which client" % where)
        body = next(v for k, v in cap.items() if k not in ("id", "client", "note"))
        on = body.get("at") if isinstance(body, dict) else None
        if on:
            if "step" in on and on["step"] not in ids:
                errors.append("%s: at.step %r is not a step id" % (where, on["step"]))
            if "signal" in on and on["signal"] not in wait_ids:
                errors.append("%s: at.signal %r is not a wait step" % (where, on["signal"]))
        span = body.get("span") if isinstance(body, dict) else None
        for k in ("from", "to"):
            if span and k in span and span[k] not in ids:
                errors.append("%s: span.%s %r is not a step id" % (where, k, span[k]))

    exp = doc.get("expect", {})
    for k, r in enumerate(exp.get("reviewer", [])):
        for ref in r.get("in", []):
            if ref not in cap_ids:
                errors.append("expect.reviewer[%d]: %r is not a capture id" % (k, ref))
    for k, chk in enumerate(exp.get("checks", []) or []):
        where = "expect.checks[%d]" % k
        if "regex" in chk:
            placeholders(chk["regex"], where)
            check_regex(chk["regex"], where)
        if chk.get("check") == "server_line":
            char_ref(chk.get("char"), where)
        if "client" in chk and chk["client"] not in aliases:
            errors.append("%s: unknown client %r" % (where, chk["client"]))
        span = chk.get("span") or {}
        for key in ("from", "to"):
            if key in span and span[key] not in ids:
                errors.append("%s: span.%s %r is not a step id" % (where, key, span[key]))
        if chk.get("min") is not None and chk.get("max") is not None and chk["min"] > chk["max"]:
            errors.append("%s: min above max" % where)
    if not any(s.get("do") == "wait" for s in steps):
        warnings.append("no wait at all: nothing proves the steps landed")
    return errors, warnings


# ── entry points ──────────────────────────────────────────────────────────────────────────────────

def validate_text(text, file_stem=None, schema=None, anchors=None):
    """(errors, warnings) for one script's YAML text."""
    schema = schema if schema is not None else json.loads(SCHEMA.read_text(encoding="utf-8"))
    anchor_map, windows = anchors if anchors is not None else load_anchors()
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as e:
        return secret_errors(text) + ["not YAML: %s" % e], []
    if not isinstance(doc, dict):
        return ["the file must be a mapping"], []
    errors = secret_errors(text) + SchemaChecker(schema).errors(doc, schema)
    if errors:
        return errors, []
    errors, warnings = rule_errors(doc, text, file_stem, anchor_map, windows)
    return secret_errors(text) + errors, warnings


def validate_file(path, schema=None, anchors=None):
    p = Path(path)
    return validate_text(p.read_text(encoding="utf-8"), p.stem, schema, anchors)


def main(argv):
    files = [Path(a) for a in argv] or sorted(SCRIPTS.glob("*.yaml"))
    if not files:
        print("no scripts")
        return 1
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    anchors = load_anchors()
    bad = 0
    for f in files:
        errors, warnings = validate_file(f, schema, anchors)
        print("%s %s" % ("FAIL" if errors else "ok  ", f))
        for e in errors:
            print("   error: " + e)
        for w in warnings:
            print("   warn:  " + w)
        bad += bool(errors)
    print("%d script(s), %d failed" % (len(files), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
