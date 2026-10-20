# 26: Bot chat follow-ups from the persistence work

**Area:** Real bots  
**Depends on:** 16  
**Status:** open

## What

Three small items deferred during the persistence series:
- bots greet a remembered player using their memory notes;
- merchant bots know their own stock when chatting (stock goes into the LLM prompt);
- WZ-dependent tests are skipped (not failed) when `wz/` is missing.

## Done when

Each item has a test; nothing chat-side regresses.
