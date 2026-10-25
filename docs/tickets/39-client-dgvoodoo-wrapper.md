# 39: Client graphics wrapper that survives virtual displays (dgVoodoo2)

**Area:** Client
**Depends on:** 1.3.0
**Status:** closed 2026-10-25 (folded into 90 item 2)

## Today

- The client ships `d3d8.dll` = d3d8to9 (DirectX 8 → 9). It creates its device on Windows' primary display.
- When a virtual display is primary (Parsec, Sunshine, Steam Link, Virtual Desktop), the client dies at startup with
  `error code: -2147467259 (Unspecified error)` after connecting to the login server.
- Workaround (verified by the owner, 2026-10-20): make the real monitor the main display before launching.

## What

- Try dgVoodoo2's `D3D8.dll` (DirectX 8 → 11) in place of d3d8to9, with a shipped `dgVoodoo.conf`: windowed, the
  dgVoodoo watermark off, adapter pinned to the real GPU where possible, no forced resolution change.
- Check `slums.dll` features that touch rendering (resolution mod, weather, overlays, ImGui if any) still work.
- Compare FPS and CPU in a busy bot map against d3d8to9.
- Ship as a client patch layer; keep d3d8to9 available as a fallback.

## Done when

The client starts and plays with a virtual display as primary (owner's Parsec setup) and on a normal desktop, with no
regressions in the slums.dll features, and the change is published as a client release.

Folded into ticket 90 item 2 (D3D9 wrapper + ImGui), one route for the Parsec fix and the settings-window host.
