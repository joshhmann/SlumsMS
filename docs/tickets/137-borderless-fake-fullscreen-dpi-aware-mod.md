# 137: Borderless "fake fullscreen", DPI-aware mode, Alt+F4 confirm, alt-tab catch-up

**Area:** client
**Rank:** 9 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** windowed mode that looks fullscreen (title bar pushed off-screen) with fast alt-tab and no freezes.
  There's a DPI-aware option for crisp 2560x1440 on scaled displays, a "Are you sure you want to quit?" prompt on Alt+F4,
  and no burst of catch-up ticks after returning from alt-tab.
- **Evidence:** config comments "Fake fullscreen mode", "so the title bar is hidden above the screen, giving a fullscreen
  appearance", "while keeping the benefits of windowed mode (faster alt-tab, no freezes)", "Enable DPI-aware mode",
  "When true, allows native resolutions like 2560x1440 on scaled displays"; "WndProc: Alt+F4 -> WM_CLOSE", "close
  confirmation"; "[AltTabCatchUp] Installed one-tick resume guard".
- **How:** CreateWindowEx/SetWindowPos hooks, SetProcessDpiAware, a WM_CLOSE handler, and a guard on the main update
  loop that limits catch-up to one tick.
- **Size:** S. Not found in our client repo (only ImGui's own fullscreen code). Helps the Parsec and virtual-display case too.
