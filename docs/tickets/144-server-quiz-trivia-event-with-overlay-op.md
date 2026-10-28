# 144: Server quiz / trivia event with overlay opt-in

**Area:** client + server (NEEDS SERVER)
**Rank:** 16 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a GM or scheduled **quiz event**: a prompt asks you to join or skip, then questions appear with answer
  buttons, and you can quit the event at any time.
- **Evidence:** "Kuro Quiz", "KuroQuiz question", "Join##KuroQuizJoin", "Skip##KuroQuizOptOut", "Quit Event",
  "kuroquiz_action=opt_in", "Missing quiz question id.", "Answer sent.", "already answered". (Stock `Etc.wz/OXQuiz.img`
  also exists, so stock OX quiz data can be reused.)
- **How:** the server runs the event (questions, timers, scoring, prizes) and pushes question packets. The client
  shows a small ImGui card. It works server-wide without moving people to an OX map.
- **Size:** M. Bots can answer (some right, some wrong), so the event is never empty.
