# 110: The website as a phone app, with push notifications for alerts and offers

**Area:** Site (SlumsMS-site) / ops on 164
**Kind:** FEATURE
**Effort:** S (installable app) + M (push)
**Depends on:** the site on its real HTTPS address (1.6.0 launch), the bell (site-160-theme b87cc6601)
**Status:** PARKED (owner, 2026-10-27: "naww not a phone app, but just was proding if it benefited to just move to some framework or a stack"). Kept as a plan in case it comes back.

Source: the owner, 2026-10-27: "ohh so if we want to go and do a pwa", then "yeahh notifications..". Then the owner
clarified that they were asking about the stack, not for an app, so this is parked. The stack answer: keep the static
generator and the stdlib services for 1.6.0, add small pieces (htmx or Alpine) where a live feature needs them, and
move the backend services onto a small Python framework when the NX-meso exchange lands (1.6.1).

## What

1. **Installable (PWA).** A web app manifest (name, colours, the badge icon) and a small service worker, so phones
   offer "Add to Home Screen" and the site opens full-screen and fast. It works offline only for the public pages and
   the look, with a "you're offline" page.
2. **Push notifications.** A signed-in player can turn on notifications. When a price alert goes off or someone offers
   on their Buy/Sell post, their phone or PC gets a notification even with the site closed: "Red Hunter Boots dropped
   to 400k" or "Shroomy offered 3m on your WTS". The data is the same as the bell's (/api/market/me: alerts and
   offers).

## Shape

- **Subscriptions:** a "Turn on notifications" button (in the bell card) asks the browser and sends its push
  subscription to the market service. One account can have several devices. They live in the market service's SQLite,
  so a "turn off" or a sign-out-everywhere drops them.
- **Sending:** a small sender in the market service or next to it on 164, run when the outbox's alerts-fired.json or
  offers.json changes. It sends each new event once, rate-limited (e.g. at most 10 a day per account).
- **Crypto:** Web Push needs ECDH P-256, AES-GCM and VAPID (ES256), which the Python standard library doesn't have.
  Either use the `cryptography` package on 164 (OS package `python3-cryptography`), or a small vetted pure-Python
  implementation. That's the one exception to "standard library only"; decide which.
- **Keys:** a VAPID key pair made on 164 **by the owner**, root 600 (readable by the market service's group), never
  printed or copied, like the sign-in key. The public half goes to the page. Changing it means everyone re-subscribes.
- **Privacy:** a notification holds only the item, price and IGN, never account names, and nothing is sent to a
  third party except the browser's own push service (Google, Mozilla, Apple), which is how web push works. The service
  worker never caches signed-in pages or any /api/ answer.
- **iPhone:** web push only works once the site is added to the Home Screen (iOS 16.4+). The button explains this.

## What to find out / decide

- [ ] `cryptography` package vs pure Python (owner + release lead).
- [ ] Which events: alerts and offers to start with; later maybe "your post was rejected", events starting, a friend
      online.
- [ ] Quiet hours or a daily cap.
- [ ] The launcher already shows "N new price alerts"; should it show offers too (from /api/market/me offers_new)?

## Done when

On a phone (Android Chrome, and an iPhone with the site on the Home Screen), a signed-in player turns notifications
on, sets an alert, and gets a push when it fires and when a bot offers on their post. Turning off, or signing out
everywhere, stops them. It's tested on the live HTTPS site, since push and service workers need HTTPS (the TEST copy
is plain http).
