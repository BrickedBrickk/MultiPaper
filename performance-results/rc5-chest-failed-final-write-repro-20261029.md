# RC5 chest failed-final-save handoff regression — 2026-10-29

## Evidence intake

The latest Crafty1 log described in the request is not mounted at `C:\Users\elibo\Downloads\1latest.log`, in the checked Codex attachment directory, or in the project. The project's `logs/latest.log` is unrelated ViaProxy output and was excluded. I did not mutate any candidate evidence. No fresh upload hash or recount is claimed.

The retained, read-only Crafty1 input remains 110,461 bytes, SHA-256 `920B665985CB2066D2199F0A6795C438D268FEADE91E8BE1246EAEA00CCA25D1`. It is the source for the previously recorded 273 no-holder warnings at 20:09:52–20:09:53; 119 player movement warnings around 16:27; eight duplicate entity UUID attempts; 13 premature entity-NBT updates; ten entity-removal routing fallbacks; and three stale inventory replacement rejections. The available excerpts do not contain chest coordinates/NBT, save acknowledgements, or a player-action timeline that connects these events to an empty chest.

## Interpretation

`MultiPaper.readRegionFileAsync` takes the missing-local-holder branch to `forceReadChunk`, which sends `ForceReadChunkMessage`. Master `ForceReadChunkHandler` waits behind the per-chunk write barrier and reads the Master `RegionFileCache`. This is the intended Master-backed fallback, not a direct read from a backend-local Moonrise region file. The path can still return stale data if the newest save was never accepted, was rejected, or was misordered before Master reads it. The warning count alone is not evidence of corruption or of fresh chest state.

## Isolated regression

Added `failedFinalChestWriteMustNotReleaseChunkToWaitingOwner` in `MultiPaper-Master/src/test/java/puregero/multipaper/server/MasterIntegrationTest.java`.

1. Give backend A ownership of an isolated temporary world chunk and subscribe backend B as waiter.
2. Persist chest NBT containing 17 diamonds; announce a later save and request unlock/handoff.
3. Close the isolated cached region file to make A's final chest write fail deterministically. Assert the write is negatively acknowledged, A remains owner, and B receives no transfer.
4. Clear the isolated fault, retry the 29-diamond chest snapshot, and assert ownership transfers only after persistence succeeds.
5. Read the chunk through B and assert the exact 29-diamond count.

The first whole-suite attempts exposed a fixture assumption: an earlier ordered crash-recovery test deliberately disconnects the shared `server-a` client. The new test now reconnects its client before starting and sends a ping barrier after its lock request. No product defect was inferred from those setup timeouts.

## Verification

- Java 25.0.3.9 / Gradle 9.8: three sequential `:MultiPaper-Master:clean :MultiPaper-Master:build` runs, offline, no build cache, one worker, all succeeded.
- Final build test XML: **96 tests**, **0 failures**, **0 errors**, **0 skipped**. This includes the new failed-final-write handoff regression.
- The second and third clean shaded Master JARs match: SHA-256 `485893B4440E5BE2811BD6276036A991C6425C7E104B6D115E50F56DA5CCCCF9` (19,216,256 bytes).
- `ChunkLockManager.java` SHA-256: `5446BEB878012BBAA63BEC93F79ADE8C41A4D718AE70FEE097B0AAA78D537A40`.
- `MasterIntegrationTest.java` SHA-256: `5DF6C9945F385DC80489A1C5D2C602F02DE219032B22EA2E206A04EBAD911C6B`.
- Build logs (trailing display whitespace removed): `rc5-chest-failed-final-write-build-1-20261029.log` SHA-256 `856CE65A239A901FA43E143EAB4D139E30CACC78A36335C0E3174C162847AC7E`; build 2 `D197105A0421F9A71290B6AE3AD5FD13C6EE27DA903D6E359B6CF7870B8EF91D`; build 3 `7DB2BA5F83E0AC94B5A146B9564E1AD0E334506BFDF3EA2E2C16242083690924`.
- Existing isolated evidence covers 273 Master-backed no-holder reads with populated single/double chests, selected pending-save readers, bidirectional ownership handoffs, and backend/graceful Master restart schedules; see `rc5-current-source-chest-273-bidirectional-20261029.md`, `rc5-crafty1-no-holder-and-chest-master-restart-20261029.md`, and the prior Crafty1 integrity follow-up.

## Limits and status

These synthetic tests verify the tested persistence and handoff schedules only. They do not reproduce every Moonrise unload/save/power-loss interleaving, establish whether the live empty-chest reports were deletion versus stale/unloaded views, inspect or recover live worlds, or prove a newly uploaded log matches the retained hash. Chest contents disappearing remains P0 `TESTING`; InvSee++ duplication remains P0 `BLOCKED`. Do not restore live data automatically. No live SMP/Crafty server, process, world, or player data was accessed or changed.
