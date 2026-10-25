# 24: Retire the junction test clients

**Area:** Client  
**Depends on:** -  
**Status:** open, target 1.6.0. Owner decision 2026-10-25: cleaning OK, archive first.

## What

Codex's addon test clients (Integration/Addons/*/TestClient, LiveClient junction web) can go once the SlumsMS Client folder and launcher installs are the only clients in use.

## Done when

One real client folder; old ones archived.

## Decision (Owner decision 2026-10-25)

Owner OK'd cleaning the old addon clients under `Z:\MSv83\Integration\Addons`. Procedure: archive first (move to an archive folder), list before deleting, and a listing must be reviewed by Claude/owner before anything is deleted. Do NOT touch the desktop without that review. Target: 1.6.0.
