# 91: Move (and maybe resize) the bottom bar / HP and MP gauges (1.6.0, client)

Owner, 2026-10-25: "be able to resize or somehow move the hp and mp bar? or make that bottom bar moveable? if theres
no way its fine but just wondering."

## What we already have
`/root/work/client/src/src/resolution.cpp` (custom resolutions) already relocates the whole status bar: it gives
CUIStatusBar its own origin vector (`CWndMan::ms_pOrgStatusBar`, set at CUIStatusBar::CUIStatusBar 0x008CFD65 and
returned for OnCreate / FlashHPBar / FlashMPBar), keeps the quick slot on its own origin (`ms_pOrgQuickSlot`), and
corrects the quick-slot hit test (`CUIStatusBar::GetShortCutIndexByPos_hook`). So moving the bar is a matter of
changing that origin at run time, not new reverse engineering.

## Options (cheapest first)
1. **Movable bottom bar.** A grip (or a settings-window option) moves the whole bar; the offset is saved per
   character in CConfig like the inventory resize. Reuses the origin vector; check every child (chat box, menus,
   EXP bar, buttons, flash effects, tooltips) follows and clicks still land.
2. **Movable quick slot / HP-MP block separately.** The quick slot already has its own origin; splitting the HP/MP
   gauges from the rest of the bar needs a look at how CUIStatusBar draws its gauge layers.
3. **Bigger or floating HP/MP.** Resizing the stock gauge art is limited (fixed-width canvases). A floating, resizable
   HP/MP widget is easiest as an ImGui window on the D3D9 wrapper (ticket 90 item 2), reading HP/MP from the client.

## Done when
The bar (or the HP/MP block) can be moved and stays where the player left it after relog and resolution change;
nothing in the bar loses its click area; it works at every supported resolution; real-client check through Start-QA.
If a piece proves infeasible, write down why and stop there (owner: "if theres no way its fine").
