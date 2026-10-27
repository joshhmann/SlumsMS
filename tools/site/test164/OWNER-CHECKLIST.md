# Website QA for 1.6.0: the owner's part (TEST)

Everything else is automatic (`tools/site/test164/qa.sh`). These need a person in game. Use the TEST client and the
TEST site, **http://192.168.0.164:8192/**, on the home network. Mark each Pass / Issue / Fail.

1. **Sign in with your username and password.** On the site, open Slums Market. You land on the sign-in page: sign
   in with a TEST account's game username and password. You land in the market, signed in.
2. **Sign up with an invite code.** In game as a GM: `!invite 1d qa test`, then note the code. Sign out on the site
   (sign-in page, "Sign out"). Under "New here?" make an account with that code, a new username and a password.
   You land signed in.
3. **The code works once.** Sign out, then try the same code again with another username: "That invite code doesn't
   work."
4. **Wrong password.** Under "Sign in", type the new username with a wrong password: "Wrong username or password."
   Then the right one: you're signed in.
5. **Log into the game** with that new username and password from the TEST client. It works. Then try a brand-new
   name at the game's login screen: it no longer makes an account.
6. **Forgot password.** Sign in on your phone too. Then on the computer, sign-in page → "Forgot your password?":
   type the username and a contact (e.g. your Discord name). The page says it's waiting. In game as a GM:
   `!resetpw approve <account>`. Within about 10 seconds the page asks for a new password; set one. You're signed in on
   the computer, the phone is signed out (reload Slums Market there), and the new password works in the TEST client.
7. **Watchlist and a price alert.** In Slums Market, open an item that's on sale in the FM. Add it to your watchlist,
   then add an alert "at or below" a price above its cheapest listing. Within 15 minutes, in game you get a yellow
   `[Market] Price alert: ...` line (or a note from "Slums Market" at your next login if you were offline). The
   site's "My market" shows the alert as fired.
8. **Buy/Sell board.** In Slums Market, post a WTB or WTS for a common item as your character. Within a minute it
   shows on the website board, and at the **Anonymous Merchant** in the FM Entrance (just right of where you arrive).
   Within a few minutes a bot may whisper you an offer (or leave a note). Take the post down on the site: it leaves
   the NPC's list.
9. **Secrets and the theme.** Signed in, find the six secrets: the Konami code (↑↑↓↓←→←→ B A), typing `slums`,
   typing `bustin`, clicking the home picture 7 times fast, clicking Slums-chan next to a page's heading (sign-in page,
   Rankings, Live world) 5 times fast, and clicking the sleeping Slums-chan on a missing page 3 times. After the first
   three, a fourth colour dot (Slums-chan) appears; pick it and the secrets you've found join the home pictures.
   Signed out, a secret only says "Sign in to see it".
10. **Phone.** Open the TEST site on your phone: home, World, Slums Market and the sign-in page fit with no sideways
   scrolling, and dark mode looks right.
11. **Launcher sign-in (optional in 1.6.0).** In the TEST launcher, sign in with your username and password, or sign
    up with a code. It shows who you're signed in as, and "Forgot password?" opens the site's page. Sign out in the launcher: that sign-in no longer works.
