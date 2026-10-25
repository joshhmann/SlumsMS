# fresh-v2 verdict on the 1.6.0 chat rules scope fix (2026-10-25)

84 held-back cases (sha256 9b15d675c9b5091ece314b6b66c4ea0b41777909db4f61b434ea20b755b2f33d), written and hashed before
any fix was scored; the rules agent never saw them. Rules only, offline (no model endpoint).
Before = 73bab43f4 (1.5.2 rules), after = 7f0e817f2 (branch 160-convo-scope).

| | before | after |
|---|---:|---:|
| intent | 70.2% | **85.7%** |
| tone | 94.0% | 94.0% |
| scam/abuse (con) | 90.5% | 90.5% |
| new wrong intents / new false scam flags | - | 0 / 0 |

Per set (intent): refusal 42 -> 92, quoted 25 -> 58, past 75 -> 92, scam-warning 75 -> 83, bargain 75 -> 75,
control-request 100 -> 100, tone 100 -> 100.

Still wrong after (19 lines, 2 marked ambiguous):
- Quoted speech said other ways: "was like", "he goes", "kept saying", "she said sorry" (020, 019, 024, 023).
- Warnings without the spec's frames: "beware of", "if someone asks ... its a scam", "dont fall for", "there are
  scammers ... ignore", "never give ur pw to a fake gm" (050, 057, 056, 052, 053): some still get trade intent or a
  false scam/rwt flag.
- Scams the rules miss (con false negatives): trade-first, "im a gm send items", "give me ur pass" (045-048).
- Past tense without "i/we": "got scammed once in 2019" (033).
- Harassment in banter: "lol ur so slow, noob" is an insult but con stays normal (073).

Verdict: the scope fix is a real gain on unseen chat (+15.5 points, no regressions, ordinary requests intact). The
remaining misses are mostly con (scam detection both ways) and quoted-speech variety. fresh-v2 is now spent: a
model comparison (gliclass shadow) or further rule work needs a new held-back set (fresh-v3).
