---
name: notes-review
description: Write or update SlumsMS player patch notes for a release and put them in front of the owner as a review page. Use when a release's player-facing notes need drafting, updating after scope changes (features switched on/off, items moved to the next version), or reviewing before they go to the website - "patch notes", "release notes", "what's new", "what's coming in 1.x".
---

# Player patch notes: draft, check, review page

The notes are the website's release page (site repo `docs/site/releases/<v>.md`, owned by the Website session), so
the format and wording rules are the site's. Players read them; keep them warm, short and true.

## Draft
- Format: copy a past release (`/root/work/SlumsMS-site/docs/site/releases/1.5.0.md` for a big one, 1.5.5/1.5.6 for a
  small one): front matter (version, title, date, summary), an intro line, a "> **To update:**" box, "## At a glance
  {#glance .cards}" with emoji cards, "## Changes {#changes}" grouped by area with [New]/[Change]/[Fix] bullets, then
  "## Still settling in" (new things that will be tuned) and "## Coming in <next>" (from docs/roadmap/<next>.md,
  player-visible items only), and a 🍁 sign-off.
- Wording: never MapleStory, Nexon, Wizet, v83, GMS or MapleRoot ("the original game" is fine); bots are "regulars"
  and never AI/models; nothing internal (decision models, shadow logging, auto-QA, TEST, dashboards, tickets, files).
- Truth: every line must match what actually ships switched on. Verify each item in code on the release branch and
  against `docs/release/<v>.conf`; when unsure, run the release-readiness skill first. A feature that ships off goes
  in neither the changes nor the cards.
- End the draft with a `<!-- REVIEW ... -->` block: what was dropped and why, open questions. It never reaches players.

## Review page
Render and publish as an Artifact (same file path on updates keeps the link):
```
python3 .claude/skills/notes-review/scripts/render_notes.py <draft.md> <scratchpad>/notes-<v>-review.html "<the owner's open questions, one line>"
```
Then the Artifact tool on the HTML (first publish: an icon such as "notes"). Give the owner the link and the
questions as a short numbered list.

## After approval
Send the final markdown to the Website session ("Website Slums") to land as `docs/site/releases/<v>.md` on its branch
and rebuild TEST, and note it in the version notes.
