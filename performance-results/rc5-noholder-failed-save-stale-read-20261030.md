# RC5 no-holder read after failed authoritative chest save — 2026-10-30

## Finding

The Moonrise no-holder warning still corresponds to an intentional Master-backed `ForceReadChunk` fallback, not a backend-local disk read. A separate source-level integrity gap existed after that read reached Master: `ForceReadChunkHandler` waited for pending region writes to finish, but did not inspect `ChunkLockManager.failedWrites`. A failed save therefore drained the barrier and allowed Master to return its previous persisted chunk image as though it were current. This can produce a stale chest view when a newer authoritative save failed; it does not establish that this occurred in the Crafty1 log or caused the reported empty chests.

## Change

`ChunkLockManager.readWhenSafe` now rechecks pending writes and the failed-write marker while holding the same per-chunk lock used for save announcements and owner-authorized write submission. It queues the region read before releasing that lock, so a late save announcement cannot be ordered ahead of the read. If Master knows the last authoritative save failed, the current protocol has no typed read-failure response; the requester connection is closed instead of receiving an older chunk image. This is fail-closed containment, not snapshot recovery. RC5-01 remains a release-blocking P0 issue.

## Regression and test evidence

The isolated `MasterIntegrationTest.forcedReadFailsClosedInsteadOfReturningPreviousChestAfterFailedFinalSave` seeds a chest chunk with 17 diamonds, announces a replacement save containing 29, deterministically closes the cached Master region file to make that save fail, reopens the cache for reads without clearing the failure marker, and requests a forced no-holder read from another backend. Against the pre-change handler from Git, the test failed with **“Master returned the stale 17-diamond image after the authoritative 29-diamond save failed”** (baseline failure XML SHA-256 `C568099C438FEF99AF51F93D0E5D363F5F9F9124FC1433074A8D9671D3AFFABE`). With the fix restored, the focused test passed and verified that Master closes the requester instead. The test operates in a fresh temporary world and does not touch Crafty or live data.

`ChunkLockManagerTest.forcedReadEnqueueIsAtomicWithLateSaveAnnouncement` holds the simulated read-enqueue callback while another thread announces a save. The announcement cannot pass the shared per-chunk lock until read enqueue completes, covering the late-announcement check introduced with the guard.

Temurin Java 25.0.3.9 / Gradle 9.8:

- Focused failed-save regression: **1/1 passed**; focused read-enqueue race: **1/1 passed**, zero failures/errors/skips.
- Full `:MultiPaper-Master:test`: **122/122 passed**, 0 failures/errors/skips. This includes 46 Master integration tests, the new lock race test, all 8 Master restart tests, current chest handoff/failure tests, and the late ownership save-barrier regression.
- Full Master integration XML SHA-256 from the final 122-test passing suite: `A0DF7B6C2F4925FE7C12B8AA9245143A510F422452082F8F313B2186806F20F3`.
- Full `ChunkLockManagerTest` XML SHA-256: `E929179D305CA8578126C81BB22035ABDF8F9076BFD6AA9E0D9CA98D37B351AB`.
- Built Master JAR SHA-256: `A82F80C0B9AAAF19A1F0E3CB4190B185D9D096B6BA314F4B8CDB970AD034CF8B`.

Source hashes:

- `ChunkLockManager.java`: `1669983A1D3B55F2F900688342824D300B6F324E8C6A72FD1747AC7D29A20272`
- `ForceReadChunkHandler.java`: `D449AC7D85B0B780B894C2DC0CC735B2ECCBDEB0D00816DF799F3EFF3997A705`
- `ChunkLockManagerTest.java`: `9B607BB0A1D43DF2AC314DF43F84D482A444AD7633C8239D7FFD4F228DA7FD4F`
- `MasterIntegrationTest.java`: `F46409DF49BB951D2F505036D9085CF220E7C2488E6E9EC7EE783F2CBD731DCF`
- `TestServerClient.java`: `D954D256382CDA18489B759939F4DB9F8E5F5634AE57EBBC77860626AC82A063`

## Limits and next gates

- The newly referenced Crafty1 upload was unavailable as raw bytes. Its reported counts remain tied to historical log SHA-256 `920B665985CB2066D2199F0A6795C438D268FEADE91E8BE1246EAEA00CCA25D1`; no failed-save line, player action, chest coordinate, or chest NBT is correlated to the 273 reads.
- The regression tests a deterministic Master storage failure while the owner remains connected. It does not prove exact Paper/Moonrise behavior, a failed save followed by owner disconnect or Master restart, recovery of the newer 29-item snapshot, or all concurrent read/write schedules.
- Follow-up: `releaseAll` now retains the in-memory failed-write quarantine across owner disconnect, and two focused Java 25 regressions pass (stale forced read fails closed; disconnect retains the marker). Current source hashes: `ChunkLockManager.java` `5DFB72E7F142402A8A9877069EBD26A5DA2128DC40436D4FB94B1289D5190C2B`; `MasterIntegrationTest.java` `4E900659D56CFF9DE0AF9C3B35FE2B65CC2298561A5913A91D1B08154A81C262`. The marker is still not persisted over Master restart. A later slice must decide how to durably quarantine and recover such chunks without silently treating older Master data as current.
- A fresh post-follow-up full-suite attempt reached the pre-existing `PlayerDataTransactionManagerTest.randomizedTransfersPreserveAggregateItemsAcrossJournalCrashCuts` test, which spent several minutes in repeated durable fsyncs. I interrupted only that disposable Gradle run; it is incomplete and is not counted as a pass. The earlier 122/122 full-suite result above predates this follow-up. No Java test process remained afterward.
- This is one Master-module test run, not two clean reproducible release builds or a matching Paperclip/Master pair. No RC5 binary is approved for staging, installation, or publication from this result.
- RC5-01 remains P0 `TESTING`; no live corruption or recovery is claimed. No live SMP/Crafty process, world, player data, or configuration was accessed or modified.

## Durable quarantine follow-up — 2026-10-30

The previous report's follow-up line saying the marker was not restart-persistent is superseded. Current working-tree source now stores completed failed region writes in a durable per-chunk sidecar under the world `data/multipaper` directory. `ReadChunkHandler` and `ForceReadChunkHandler` both consult the marker; `ChunkLockManager.writeCompleted` clears it only after a successful retry. Four focused Java 25 tests passed, zero failures/errors/skips: actual failed write creates the durable marker, successful retry clears it, disconnect preserves it, and a fresh Master refuses ordinary and forced reads after process restart. The restart-process test seeded a 17-diamond chunk and created the persisted marker through the same store API after shutdown; the actual failed-save marker creation was separately verified in `MasterIntegrationTest`, so this was paired coverage rather than a single uninterrupted failure/crash/restart test.

Latest hashes: `ChunkLockManager.java` `5F16F9C2C1C1F40CDF15C6EB18DCB7D5D78AD3F47F3AB0484FACC1BB8B937FCE`; `FailedChunkWriteStore.java` `3A94AEF9CF7038858B1DC828FF51943D86DCC56CA8B63539A6A3A67278924D86`; `ReadChunkHandler.java` `7F3FB905DFC38AD54FD6F6A6BE8265F07AB7F49EAFAB1F1B1DA5612A95652DDC`; `ForceReadChunkHandler.java` `D449AC7D85B0B780B894C2DC0CC735B2ECCBDEB0D00816DF799F3EFF3997A705`; Master JAR `248EF5E0A67C393C96A428C085B67282D2288D1665A5F5370C07EBEAA4B8E36A`; integration XML `30CB8A9E279DE597B943D83E89E8F90D8EC12F89D9CF785028E2C94E0E05769B`; restart XML `7DF3CDC19812E87A370C56623BF552B78D68EB2480900C72533B4D16DA496F54`.

Limits remain: marker filesystem failure leaves only the in-memory guard, and abrupt Master loss before the asynchronous failed-write completion callback creates the sidecar is not covered. No exact Paper/Moonrise queued-write crash schedule or live chest attribution was tested. The interrupted full suite from the prior attempt is not a passing result. RC5-01 remains P0 TESTING; no installable/publishable candidate is qualified.

## Final current-source run after orphan-marker hardening — 2026-10-30

`FailedChunkWriteStore.isFailed` now treats orphan FileLocker `_new`/`_old` staging files as a quarantine, preventing an interrupted marker replacement from being silently discarded by normal FileLocker read recovery. `ChunkLockManagerTest.orphanedFailedWriteMarkerStageKeepsChunkQuarantined` passed, along with the existing failed-save, retry, disconnect, ordinary-read, forced-read, and fresh-Master restart chest regressions (focused selection **6/6**).

The final full current-source Java 25.0.3.9 / Gradle 9.8 Master suite passed **124/124 tests in 14 XML suites**, zero failures/errors/skips, in 3m11s. XML bundle/hash manifest is preserved at `D:\multipaper-tests\evidence\rc5-failed-chunk-quarantine-20261030\final-full-suite`; SHA256SUMS hash `7C960DB3198D88191C12B25731D84A13291A49F73A559BEEBF5CABE21493771A`. Two sequential clean uncached Master builds both succeeded and matched at SHA-256 `657D1FD6BCFCD9DD85EFCE191E4590111EB8DF177089D88245DE883E1B4F9644`; artifact copies `build3-multipaper-master-3.0.0.jar`, `build4-multipaper-master-3.0.0.jar`.

This qualifies only the Master module. It is not a Paper/Moonrise pair or a complete RC5 build/publish gate. Marker I/O failure and loss before the failure callback creates a durable record remain gaps; no live incident attribution/recovery is established. Keep RC5-01 P0 TESTING and do not install/publish from this worktree.

Final post-hardening source hashes: `ChunkLockManager.java` `5F16F9C2C1C1F40CDF15C6EB18DCB7D5D78AD3F47F3AB0484FACC1BB8B937FCE`; `FailedChunkWriteStore.java` `75C8EED33F29FD450E2BC2A128BA9FEB1AFE3BDF48218B7C4B5FD83660821D8D`; `ReadChunkHandler.java` `7F3FB905DFC38AD54FD6F6A6BE8265F07AB7F49EAFAB1F1B1DA5612A95652DDC`; `ForceReadChunkHandler.java` `D449AC7D85B0B780B894C2DC0CC735B2ECCBDEB0D00816DF799F3EFF3997A705`; `ChunkLockManagerTest.java` `AFEEB518909DC02E122AD248027C6EB5E4EF7F656E619AA5E58BB8A2637B326D`; `MasterIntegrationTest.java` `C8E49222F74469E12FF4235B61DCC11A7C7D49620AA4CB21A2544E807431FDFA`; `MasterProcessRestartPersistenceTest.java` `963D0BECB0C732D879F8BCCA637E34CD330BB4279CFDB3803DBA3C292679C767`.
