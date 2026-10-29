# RC5 failed chunk-write ledger disconnect cleanup — 2026-10-29

## Finding and regression

`ChunkLockManager.writeCompleted(..., successful=false)` records the failed chunk and its `ServerConnection` even when the owner has not sent `UnlockChunk`. `releaseAll` previously iterated only chunks present in `deferredUnlocks`; on connection loss with no deferred unlock, `failedWrites` retained the dead connection and chunk key indefinitely.

Added `MasterIntegrationTest.disconnectClearsFailedChunkWriteWithoutDeferredUnlock`. In a temporary world it acquires a chunk as `failed-write-owner`, announces a save, forces the isolated region handle closed, confirms the write is rejected and the failure ledger is populated, then disconnects the owner without unlocking and asserts the ledger entry is cleared after disconnect cleanup.

The regression was red with the old cleanup: `Disconnect cleanup must remove failed-write entries even when no unlock was deferred ==> expected false but was true`. It is green after `releaseAll` unions `deferredUnlocks.keySet()` and `failedWrites.keySet()` before cleanup. The query helper is package-private and used by the Master integration regression.

## Verification

- Focused Java 25.0.3.9 / Gradle 9.8 test passed **1/1**, 0 failures/errors/skips. Green log SHA-256 `87F4FFEF45A52F2321D430FA3FE90A7EC655511EDCA71F8BD5411D1108020642`.
- Red log SHA-256 `1793410917CB17E2498586473CDF216B10E7ECECC083EEF1E599212AAEA973E7`.
- Two sequential clean offline uncached `:MultiPaper-Master:clean :MultiPaper-Master:build` runs passed **97/97 tests**, 0 failures/errors/skips each, with one worker. Both Master JARs matched at SHA-256 `DCDACBD5D18C4B25E1D43626BE89B744BDAE3ABD5563618B95D582EF1C7B0FAD` (19,216,392 bytes). Build logs (trailing display whitespace removed): build 1 `6FAF34CA97EED88AEB83229180CB609D7AEFFCDE13A169DE3EE1670CF0085838`; build 2 `BE8014C76FEA32FE2A194318DFBA6D7C6A04DA0B5C90DD5CBC691336D2FB55CC`.
- `ChunkLockManager.java` SHA-256 `7BACF3618D82827D2FEDEF0F75B4BF776EF24C8823E8D257B914311D0BD32365`; `MasterIntegrationTest.java` SHA-256 `EAAF12617A9A596F56363EFABFA56A9DAD58F5253D08178FCE870B09E2065154`.
- One earlier focused attempt crashed before executing tests with a Java native allocation failure (6,792,472-byte request in HotSpot C2 compiler arena). Its raw JVM diagnostic was moved out of the repository to `C:\Users\elibo\AppData\Local\Temp\multipaper-rc5-gradle-oome-45028-20261029.log`, SHA-256 `677A807FFCEC2C47A1006680026F68D00DF9585B72B5E4D2819A00EB34011C05`. No other process was stopped. Retried with `-Xmx2G -XX:ActiveProcessorCount=4`; final focused and clean build runs passed.

## Limits

This prevents an in-memory ledger reference leak when a backend disconnects after a rejected chunk write without a deferred unlock. It does not recover a failed, uncommitted chunk snapshot or attribute the live empty-chest report. The chest gate remains P0 `TESTING`; InvSee++ conservation remains P0 `BLOCKED`. No live SMP/Crafty process, world, or player data was accessed.
