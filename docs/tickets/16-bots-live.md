# 16: Bring the bot world live

**Area:** Rollout  
**Depends on:** 01-15 (owner choice)  
**Status:** closed 2026-10-25 (done in v1.3.0, bots live; live runs about 3,000 bots).

## What

persistence-on-v120 (or its successor): test boot at the chosen size, owner in-game check (chat, whispers, menus, buffs, attacks), migration 003, a dedicated bot template character, solomapling.enabled, backup, deploy, receipt. The new config keys may only be added together with the jar (unknown keys stop boot).

## Done when

Live runs bots with a written rollback.

## Model servers (checked reachable from 164 on 2026-10-19)

Add these to the live `config.yaml` **together with** the bot jar (unknown keys stop the boot):

```yaml
  BOT_LLM_BASE_URL: "http://192.168.0.17:8080/v1"      # llama.cpp, Qwen3.8-9B, 2 slots
  BOT_LLM_MODEL: "Qwen3.8-9B-Distill-Heretic-Uncensored-Q8_0"
  BOT_LLM_MAX_CONCURRENT: 2
  BOT_LLM_EXTRA_BODY:
      chat_template_kwargs: '{"enable_thinking": false}'
  BOT_DECIDE_URL: "http://192.168.0.17:8900"          # GLiNER2.5-Decide, POST /v1/systemone
  BOT_ROSTER_TIMEZONE: "America/Los_Angeles"
```

If 192.168.0.17 is down, bots fall back to scripted chat, so it is not a hard dependency. Check how the 9B copes with
two slots at full population; `BOT_LLM_NOTES_MODEL` can point memory notes at another model.
