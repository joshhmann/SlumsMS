# 133: Server notice with "Reply to GM", and merchant sale toasts

**Area:** client + server (NEEDS SERVER)
**Rank:** 5 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** GMs can pop a **SERVER NOTICE** in the overlay. Players can **reply once** (5-500 characters), and the
  reply reaches GMs. Separately, a small **[MERCHANT SALE]** toast appears when your hired merchant sells something.
- **Evidence:** "SERVER NOTICE", "Reply to GM...", "Please do not spam. You can reply once to this notice, or use @gm
  <message> in chat.", "Message too short (5+ chars).", "Reply sent. One reply per notice.", "Widget packet mode required
  to reply to GMs."; "[MERCHANT SALE]", "##MerchantNotif%zu".
- **How:** server-to-client notice packet with an id. The client sends the reply with that id, and the server stores it
  for GMs (we could forward replies to Discord). Merchant sale: the server already knows when a sale happens, so it sends
  a toast packet to the owner if online.
- **Why for us:** a cheap tester-feedback channel for a friends server. Merchant toasts make the bot economy feel alive
  ("a bot just bought your Ilbi").
- **Size:** S each. Client: toast and notice modules. Server: two small packets plus storage for replies.
