# RC5 chest persistence investigation — 2026-10-27

## Safety and candidate

- All tests used only the marked disposable fixture `D:\multipaper-tests\system-temp\rc5-concurrent-invsee-valid-20261027`; its world was newly generated for this investigation. No live RC4 server, Crafty PC, production world, or player data was accessed. No restoration or deletion was performed.
- The fixture used the RC4 Paperclip SHA-256 `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`, Master SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`, Java Temurin 25.0.3+9, two backends, and only InvSee++ 0.31.22 (`01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9`). The user report's real-world cause remains unknown.

## Observations

- Seeded backend B with a single chest at `(16008,100,16008)` containing 32 diamonds and a double chest at `(16010,100,16008)` / `(16011,100,16008)` containing 17 emeralds and 23 gold ingots. While both backends could query the chunks, both saw the same item NBT. A complete orderly Master/A/B shutdown and restart retained these items.
- A single-backend control chest at `(17608,100,17608)` in chunk `(1100,1100)`, seeded with 32 diamonds, survived force-unload/reload on its sole owning backend.
- In the paired fixture, both backend force-load tickets for chunk `(1000,1000)` were removed. Later RCON queries on restarted B returned empty `Items` for both original single and double chest block entities at `(16008,100,16008)` and `(16010,100,16008)`. The same disposable Master's region chunk (file `region/r.31.31.mca`, local chunk index 264) was decoded; its block-entity list had no chest entries, despite the chest block states remaining. This is evidence of persisted block-entity/item loss in this isolated paired run, not merely an empty client view.
- A second control at `(17624,100,17608)`, chunk `(1101,1100)`, contained 41 diamonds. It was read on A after B had loaded/subscribed to the chunk, and it still contained 41 diamonds after a B-only restart. This rules out a general failure to persist every chest on backend restart.
- The force-unload sequence did not establish which server submitted the last chunk snapshot or the exact ordering between ownership transfer, save, unsubscribe, and Master write. In an attempted paired control, backend B remained loaded after its force-load ticket was removed, so that control's unload result was inconclusive. Root cause is therefore not yet proven.
- Source review found that `MultiPaperChunkHandler.onChunkUnload` sends an unsubscribe asynchronously, while the Master `WriteChunkHandler` persists `WriteChunkMessage` data without checking that the sender still owns the chunk. `ChunkLockManager` serializes writes but does not reject a stale owner's payload. This is a plausible stale-snapshot overwrite path, not a confirmed causal explanation. No production patch was made because the write/ownership ordering has not yet been isolated into a deterministic regression.

## Repro files and retention

- Fixture marker, exact binaries, runtime logs, RCON transcript captures, scripts, and disposable world remain under `D:\multipaper-tests\system-temp\rc5-concurrent-invsee-valid-20261027`.
- The diagnostic Master region file is retained there. The parser `inspect-chunk.py` currently targets chunk `(1000,1000)` and reports the decoded block entities. The disposable backend-B restart ended after reporting the 41-item control intact and both original chest inventories empty. Backend A was gracefully flushed/stopped. Confirmed no listeners remained on test ports `33251`–`33262` and no fixture Java process remained.
- These files contain only synthetic test data and test credentials. Do not use this fixture as a recovery source for any real world.

## RC5 blocker and next verification

Treat widespread chest inventory loss as a release blocker. Do not publish RC5 until a deterministic isolated regression covers single and double chests with both backend ownership orders, unload/reload, save ordering, owner transfer, and repeated independent backend restarts, and a fix preserves the expected NBT after each transition. Preserve original world files and evidence; never automatically restore or overwrite production data. The reports that some real players' chests appeared cleared cannot be classified as deletion or stale display without their controlled future observation; this test does not authorize accessing those live worlds.

Player `Mona` visibility remains an untested RC5 blocker: no live login/session or controlled visibility trace was available in this investigation. Verify tracking, player-list, spawn/despawn, metadata, reconnect, chunk-boundary, and dimension-transfer behavior with synthetic players in a disposable fixture before changing source.
