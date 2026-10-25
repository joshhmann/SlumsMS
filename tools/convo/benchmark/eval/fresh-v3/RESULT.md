# fresh-v3: rules vs GLiNER vs gliclass vs Jev (2026-10-25)

96 held-back cases (sha256 674ed53d563c788daf07929820e7242d327036ed1ca44f472aeb97cb03257f53, 8 sets of 12 incl. a new
indirect-request set), written and hashed before any run. Rules = branch 160-convo-scope (after the scope fix).
Thresholds unchanged (intent 0.4 / tone 0.8 / con 0.4), 400 ms budget, one request per second.

| | rules only | + GLiNER (:8900) | + gliclass (:8911) | + Jev (hosted, jev-1.14.0) |
|---|---:|---:|---:|---:|
| model's own intent (raw) | - | 57.3% | 54.2% | **83.3%** |
| combined intent | 71.9% | 71.9% | 75.0% | **79.2%** |
| tone | **95.8%** | 93.8% | 94.8% | 90.6% |
| scam/abuse (con) | 85.4% | 77.1% | 83.3% | **88.5%** |
| new wrong intents vs rules | 0 | 2 | 2 | 1 |
| new false scam/abuse flags | 0 | 11 | 5 | 2 |
| p50 / p95 ms | - | 103 / 116 | 112 / 182 | 118 / 156 (from 187 over the internet) |
| within 400 ms | - | 96/96 | 84/96 | 96/96 |

Per set, combined intent (rules / GLiNER / gliclass / Jev): indirect-request 42/50/75/92, control-request
75/83/83/92, refusal 75/67/75/67, quoted 67/58/58/67, past 58/58/58/58, bargain 75 all, scam-warning 92 all,
tone 92/92/83/92.

Caveats: gliclass had 11 URLErrors (the .17 services were being reworked by another agent during the run); rerun
it before judging it. Jev's raw 83.3% > combined 79.2%: the rules override some of its right answers. Jev is a
hosted third-party API: production use would send player chat to TypeSafe and depend on their uptime and pricing
(owner decision).

fresh-v3 is spent.
