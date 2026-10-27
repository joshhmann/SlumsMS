# Website QA for 1.6.0: the owner's part (TEST)

Everything else is automatic (`tools/site/test164/qa.sh`). These need a person in game. Use the TEST client and the
TEST site, **http://192.168.0.164:8192/**, on the home network. Mark each Pass / Issue / Fail.

1. **`@weblogin`.** On the site, open Slums Market. You land on the sign-in page: under "Or sign in from the game"
   press **Get a code**. In game, on a TEST character, type `@weblogin` and the code. Within a few seconds the site
   takes you to the market and says you're signed in as that character.
2. **Sign up with an invite code.** In game as a GM: `!invite qa test`, then note the code. Sign out on the site
   (sign-in page, "Sign out"). Under "New here?" make an account with that code, a new username and a password.
   You land signed in.
3. **The code works once.** Sign out, then try the same code again with another username: "That invite code doesn't
   work."
4. **Wrong password.** Under "Sign in", type the new username with a wrong password: "Wrong username or password."
   Then the right one: you're signed in.
5. **Log into the game** with that new username and password from the TEST client. It works, with no new account
   made at the login screen.
6. **Watchlist and a price alert.** In Slums Market, open an item that's on sale in the FM. Add it to your watchlist,
   then add an alert "at or below" a price above its cheapest listing. Within 15 minutes, in game you get a yellow
   `[Market] Price alert: ...` line (or a note from "Slums Market" at your next login if you were offline). The
   site's "My market" shows the alert as fired.
7. **Buy/Sell board.** In Slums Market, post a WTB or WTS for a common item as your character. Within a minute it
   shows on the website board, and at the **Anonymous Merchant** in the FM Entrance (just right of where you arrive).
   Within a few minutes a bot may whisper you an offer (or leave a note). Take the post down on the site: it leaves
   the NPC's list.
8. **Secrets and the theme.** Signed in, find the three secrets: the Konami code (↑↑↓↓←→←→ B A), typing `slums`,
   and clicking the home picture 7 times fast. After the third, a fourth colour dot (Slums-chan) appears; pick it and
   the secrets join the home pictures. Signed out, a secret only says "Sign in to see it".
9. **Phone.** Open the TEST site on your phone: home, World, Slums Market and the sign-in page fit with no sideways
   scrolling, and dark mode looks right.
10. **Launcher sign-in.** In the TEST launcher, sign in (code, or username and password). It shows who you're signed
    in as. Sign out in the launcher: the site's sign-in that the launcher made no longer works.
