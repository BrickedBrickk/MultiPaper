# RC3 vs current-tip live block-entity routing — 2026-10-26

## Result

The exact published RC3 pair fails a focused live update test, while the exact current-tip candidate passes it using the same official backend plugin binaries and harness. This reproduces a release-relevant RC3 defect and provides a direct current-tip regression control.

| Runtime | Paperclip SHA-256 | Master SHA-256 | Owner world UUID | Peer world UUID | Remote chest |
|---|---|---|---|---|---|
| Published RC3 | `22B1EE9737A98BA85A52AE09B6B9C6EB890331F9B7A9443BBD3CDEA9357C6460` | `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186` | `f9508eaf-5889-4ba3-9b44-915b099cb069` | `df1b0600-4198-424a-92fb-aeb6fe020477` | Failed; peer reported target was not a block entity after retries. |
| Current tip (`2265a5a`) | `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098` | `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7` | `90a07ddf-7f84-4209-afca-01ed8192dbfc` | `90a07ddf-7f84-4209-afca-01ed8192dbfc` | Passed; peer received the chest with all five diamonds. |

Both tests used a fresh world, owner A for chunk `[5,5]`, peer B subscribed and reporting `currentChunk=true`, a chest at `(83,63,83)`, identical test script `scripts/Test-ExactCandidateSugarcaneFarm.ps1 -BlockEntityReplicationProbe`, and the same five official plugin artifacts. Their hashes and provenance are listed in `performance-results/official-plugin-artifact-validation-20261026.md`. The current-tip run also ran loopback Geyser. This was a disposable RCON/runtime probe, not human gameplay or an authenticated account test.

## Root cause

Confirmed by topology logs and source routing: RC3 gave the same named world different backend-local UUIDs. `MultiPaperChunkHandler` tags live update packets with the owner world's UUID; the receiver looks up the world by that UUID and drops the update when no world matches. The current-tip `0077-MultiPaper-share-world-uuid.patch` obtains a Master-backed shared world UUID, and the current-tip peers report the same UUID and replicate the update. No new source change was needed for this differential.

## Evidence locations

- RC3 fixture and result: `E:\multipaper-tests\system-temp\multipaper-rc3-official-block-entity-20261026\result.txt`; latest logs: `E:\multipaper-tests\system-temp\multipaper-rc3-official-block-entity-20261026\logs-20261025-194909`.
- Current-tip fixture and result: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-block-entity-20261026\result.txt`; logs: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-block-entity-20261026\logs-20261025-195156`.
- Exact official plugin hashes: `performance-results/official-plugin-artifact-validation-20261026.md`.

No production or Crafty services/worlds were accessed. The published RC3 assets were not changed.
