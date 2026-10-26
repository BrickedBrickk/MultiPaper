# Exact current-tip observer-to-piston control — 2026-10-26

## Fixture

- Disposable, loopback-only two-backend fixture from `scripts/Test-ExactCandidateSugarcaneFarm.ps1 -ObserverDirectPistonControl`.
- Paperclip SHA-256: `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`.
- Master SHA-256: `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.
- Both backends loaded the canonical five-plugin stack: Floodgate 2.2.5 build 141, ViaVersion 5.12.1, ViaBackwards 5.12.1, InvSee++ 0.31.22, and GrimAC 2.3.74. Geyser Standalone 2.12.0 build 1270 ran separately. The disposable scheduled-tick owner probe was installed for ownership assertions.
- World: `farm_rc4_1025_193206`; Backend A owned chunk `[5,5]`, Backend B subscribed. RCON/listeners were loopback-only.

## Result

The test alternated the observed target block between stone and air three times. Each observer update extended the adjacent piston, moved its cargo, and the piston retracted. Final moved-block state was visible in both backend views. The direct observer-to-piston redstone path and cross-owner block replication passed with the exact current-tip artifacts and canonical plugin stack.

This does not certify the separate sugar-cane farm's observer/redstone-wire geometry, natural growth-to-harvest cycle, item collection, or player-built Survival farms. The earlier farm-specific circuit did not trigger on either MultiPaper or stock Paper, so it remains an invalid/unresolved farm control rather than a confirmed MultiPaper defect.

Result and full per-command trace: `%TEMP%\multipaper-current-tip-observer-direct-control-20261026\result.txt`. Logs: `%TEMP%\multipaper-current-tip-observer-direct-control-20261026\logs-20261025-193208`. The runner saved and stopped test-owned processes; no production/Crafty access occurred.
