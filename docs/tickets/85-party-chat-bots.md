# 85: Party chat with player bots (1.6.0)

Source: owner, 1.6.0 notes page, 2026-10-25 ("Make party chat functional with playerbots").

Today bots answer map chat, whispers, buddy chat (BotBuddies) and guild chat (BotGuilds), but party chat
(MultiChatHandler, type party) reaches no bot logic. Goal: bots in a player's party read and answer party chat like
the other channels: replies routed by the same rules (name > clear reply > ...), PQ/boss coordination lines
("wait for me", "ready?", "go left"), and the say-do rule (a bot that says it will do something does it). Bot-only
parties may chat among themselves at a low rate. Include an auto-QA check (cv-* family) and a GM staging command.

Parent: ticket 99 (Companions epic).
