# 1.6.7 engine batch 2 (branch `167-engine-batch2`)

Four small, independent fixes of ours; nothing is ported from upstream. This file is the scope-and-proof card: after
the branch each statement below is true, and the named test is the one that shows it. Each fix is one commit, and each
new test is run before its change (and fails) and after (and passes).

## 1. P1-E1: one bad life entry doesn't kill a map load

After: `MapFactory.loadLifeFromWz` and `loadLifeFromDb` load each `life` entry on its own. An entry whose id does not
parse, whose mob/NPC id the game has no data for, or whose child nodes are broken is skipped with ONE warning line that
names the map id, the entry (life id, type) and the reason; every other entry of that map still spawns exactly as
before. A good entry is loaded by the same code as before, so nothing about a healthy map changes.

Proved by: `MapFactoryLifeSkipTest` (`src/test/java/server/maps/`). A map image with three life entries (two NPCs and
a middle monster id that has no Mob.wz image) is handed to the loader: before the change the NPE leaves the whole load;
after, the two NPCs are map objects and one warning names the map and the bad id.

## 2. P2-6: `!enableauth` really removes the login bypass

After: `LoginBypassCoordinator.unregisterLoginBypassEntry` builds the same key type `registerLoginBypassEntry` stored
(`Pair<Hwid, Integer>`), so `Pair.equals` matches and the entry is removed. `!enableauth` therefore does what it says:
the account's PIN/PIC bypass is gone at once and the next login asks for the code. Other accounts' entries stay.

Proved by: `LoginBypassCoordinatorTest` (`src/test/java/net/server/coordinator/login/`). Register for two accounts,
unregister one, then `canLoginBypass` is false for that one and still true for the other. Fails before (the removal
never matches).

## 3. legacy-sha-multibyte: legacy hashes cover the whole password

After: a NEW legacy hash (`sha512()`) covers every byte of the UTF-8 form, so a password with non-ASCII letters is no
longer hashed only in part. A legacy CHECK (the login screen's `check` and the site's `checkHashedOnly`) accepts a
stored value that matches EITHER the full-bytes digest OR the old truncated one (the exact old computation, kept as one
clearly named private method), and both return `Match.LEGACY`, so the existing upgrade-to-bcrypt path still runs on a
successful login. Bcrypt values, plain-text legacy values and ASCII passwords behave exactly as before (full and
truncated digests are equal for ASCII).

What existing accounts keep: every account that can log in today still can, with the same password. No stored value is
rewritten by this fix; a non-ASCII account's old truncated hash keeps matching until its next login upgrades it.

Proved by: `AccountPasswordsMultibyteTest` (`src/test/java/client/`): (a) a SHA-512 value made the old way for
`pässwörd` still matches (guards "no existing player loses access"; passes before and after); (b) a SHA-512 value made
from all the bytes matches (fails before); (c) the old truncated value does not match a different password whose own
truncated digest differs (for `pässwörd` that is `pässwöX`, the truncated digest of 7 bytes rather than 8) - a password
that shares the whole old byte prefix still matches, by construction, which is what keeping the old values working
buys; (d) ASCII passwords match both ways.

## 4. b375: the t-xc-5 probe and the trader brain respect the daily item-sell cap

The exchange refusing an item sell over `-Dslums.exchange.items.sellCapMesosPerDay` per account per UTC day stays
exactly as it is (`Exchange.sellCap`, code `ITEMS_CAP`). The two callers that ignored it now agree with it:

- (a) t-xc-5 (`MarketQa.judgeSell`) treats `ITEMS_CAP` like `COOLDOWN`: it skips that holder/trader and tries the next
  one, and the check fails only when no holder can list. Skipped traders are named in the evidence lines.
- (b) the trader brain (`MarketTraders.act`) asks `Exchange` how much room the account has left today (the same
  exposure `Exchange.sellCap` uses) before it proposes an item SELL, and drops a sell whose notional would be refused.
  A cap of 0 means no cap and changes nothing.

Proved by: two new methods in `MarketQaTest` plus `MarketTradersSellCapTest` (`src/test/java/soloMapling/Economy/
exchange/volatility/`): a spy exchange answering `ITEMS_CAP` for the first trader and a real answer for the second
makes the check pass on the second (before: `FAIL ... refused: ITEMS_CAP`); a trader whose account is at the cap
places no item sell (before: it places one and the exchange refuses it); with no cap, or room left under it, the
trader quotes as before. The exchange's own cap rule is not touched.

## Verification

- Per fix: the new test run before the change (recorded failure) and after (pass).
- Full suite: `mvn -q -o test -Dwz-path=/root/work/SlumsMS-152-maps/wz`; the counts from
  `target/surefire-reports/TEST-*.xml` are recorded in `REPORT-deepseek.md`.
- Out of scope: shops, packets, ops endpoints, mob skill rates, autoban, and the exchange's own cap rule.
