# RC5 populated chest after abrupt Master restart — 2026-10-29

## Scope

Added `populatedChestChunkSurvivesAbruptMasterTerminationAfterAcknowledgedWrite` to `MultiPaper-Master/src/test/java/puregero/multipaper/server/MasterProcessRestartPersistenceTest.java`. It runs in JUnit's disposable temporary directory and uses dynamically selected Master/proxy ports. No Crafty process, live world, production backup, or player data is accessed.

## Test sequence and result

On Temurin Java 25.0.3.9 / Gradle 9.8, ran:

```powershell
./gradlew :MultiPaper-Master:test --tests puregero.multipaper.server.MasterProcessRestartPersistenceTest --offline --no-daemon --console plain --no-build-cache --max-workers=1
```

The test acquired a chunk as backend A, wrote a chest-bearing region chunk containing 41 diamonds through Master, required the write acknowledgement, and confirmed the stored chest count. It then closed the disposable client and forcibly terminated only the disposable Master process. A fresh Master started on the same temporary storage; backend B claimed the chunk and read it. The exact compressed chunk bytes matched the acknowledged snapshot and the chest NBT still contained all 41 diamonds. The recovered Master then shut down gracefully.

**Result: 7/7 tests passed, 0 failures, 0 errors, 0 skipped** for the complete `MasterProcessRestartPersistenceTest` class, including existing graceful chest restart, generic acknowledged-write crash restart, playerdata, entity transaction, and world UID cases. XML SHA-256: `23A41AC41450A04F87578BEA1C902D5A88BA5A42588B2737B7DB8E31D169AFCC`.

Regression source SHA-256: `CAC4D9DA0A8211582A457472A6B0D05AB30558302E327E409CFB909AA36A1782`. `git diff --check` passed.

## Limits

This demonstrates process-crash recovery after an acknowledged write on the current local Master storage implementation. It does not model power loss, unacknowledged async writes, a simultaneous owner transfer during the crash, full two-backend Paper/Master runtime, the Crafty empty-chest incident, or player-data recovery. It authorizes no live restoration and does not close RC5-01; keep P0 `TESTING` pending incident attribution and broader handoff/crash coverage.

## Full Master suite revalidation

After the focused restart-class run, the full `:MultiPaper-Master:test` suite was run sequentially on Temurin Java 25.0.3.9 / Gradle 9.8 with `--offline --no-daemon --console plain --no-build-cache --max-workers=1`. **94 tests across 10 XML suites passed; 0 failures, 0 errors, 0 skipped.** The full-run restart-class XML SHA-256 is `624B8D7201B0ED03710A668C3B21E89258E62440C81CAA6653F384014BFC5360`.

The runtime Master JAR at `MultiPaper-Master/build/libs/multipaper-master-3.0.0.jar` hashes to `466A094E84C711D5367333BB2A167A76F505D49CEFC1997C9CB2B492E49A1DD1`, matching the previously retained Master output. This change adds tests only; no runtime JAR changed, so it does not claim two new clean reproducible builds.
