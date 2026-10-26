# Exact current-tip End portal transfer — 2026-10-25

## Scope

- Exact Paperclip SHA-256 `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098` and Master SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.
- A disposable offline Minecraft 26.3 protocol client joined backend A through Master. Both backends ran the five canonical plugins plus the passive scheduled-tick diagnostic probe. This is a scripted mechanics check, not a human or online-mode test.
- `scripts/Verify-DisposableDimensionPortalTransfer.ps1` now supports both Nether-frame and End-portal tests. The End case replaced the probe player's floor block with a vanilla `minecraft:end_portal` block inside the isolated marked fixture `E:\multipaper-tests\system-temp\multipaper-current-tip-official-portal-20261026`.

## Result

The test player joined at `(-40.5,83,-207.5)`. The client received a respawn packet naming `minecraft:the_end`, then received a chunk at `(6,0)` in the End. Backend A logged the vanilla advancement `[The End?]`. Both backends reached readiness; no dimension-sync exception appeared. The End portal transfer completed in about 10 seconds after login. The player was disconnected during test cleanup, then both backends saved and stopped normally.

Logs: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-portal-20261026\portal-activation-20261025-204304`. The harness also confirmed the portal block and target dimension through RCON; all test listeners were later verified closed.

## Limits

This verifies automated vanilla End-portal travel and client dimension/chunk delivery on the exact current pair. It does not cover a human Survival journey, an online-mode Bedrock identity, End combat/return portal, vehicles, or cross-world entity persistence during a crash. No product defect was observed and no source patch was required.

## Strict cross-backend confirmation

The reusable verifier was tightened to require separate RCON confirmation from **both** backend A and backend B. A follow-up exact-pair run passed with output:

```text
PASS: vanilla end portal activated and EndProbeB transferred to minecraft:the_end.
PORTALBLOCK=minecraft:end_portal x=-37..-37 y=77..77 z=-205..-205; playerChunk=-3,-13; backendAEnd=True; backendBEnd=True; activation=#active has 1 [portalprobe]; clientRespawn=True
```

The client respawn payload named `minecraft:the_end`, and the verifier exited with code 0. Test log directory: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-portal-20261026\portal-activation-20261025-204620`. Before the test, the verifier removed the previous portal only if the block still matched `minecraft:end_portal`. It removed the new test portal in both backends during cleanup, then flushed and stopped the servers. Test ports 32751–32762 were clear afterward.

## Cleanup assertion — 2026-10-26

The retained disposable fixture had a probe player persisted in the End, so the runner now returns End-probe accounts to the loaded Overworld spawn before placing a new portal. The exact-pair run completed portal travel, issued cleanup through both backends, then independently queried each backend and required the portal block to be absent. Output:

```text
PASS: vanilla end portal activated and EndCleanupB transferred to minecraft:the_end.
PORTALBLOCK=minecraft:end_portal x=0..0 y=79..79 z=0..0; backendAEnd=True; backendBEnd=True; activation=#active has 1 [portalprobe]; clientRespawn=True; endPortalRemovedOnBoth=True
```

The script exited successfully after saving/stopping both fixture backends and closing all test ports. Logs: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-portal-20261026\portal-activation-20261025-205320`.
