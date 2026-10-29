# RC5 failed chest write preserves last acknowledged NBT — 2026-10-29

## Scope

Strengthened the existing Master integration test `failedChunkWriteIsNotAcknowledgedAndPreservesThePreviousChunk` to use actual compressed chest chunk NBT instead of opaque text. The fixture first persisted a chest containing 17 diamonds, forced the cached region file closed, then attempted replacement NBT containing 29 diamonds. This is disposable Master storage only; no Crafty host, live SMP, player, or world data was accessed.

## Result

Java 25.0.3.9 / Gradle 9.8 command:

```text
:MultiPaper-Master:test --tests puregero.multipaper.server.MasterIntegrationTest
```

The complete ordered `MasterIntegrationTest` class passed **42/42 tests, 0 failures, 0 errors, 0 skipped**. JUnit XML SHA-256: `E2994F853EA3BDEBF881A0BE270DF55D4D57BABC6DD2A849F0E89C63E8B57269`. Test source SHA-256: `829C005C20B6FCC5B9AA51E1003915ACA86715D9691A0033304602F39BA825A3`. Fixture worktree: `C:\Users\elibo\AppData\Local\Temp\rc5-buildorder-f1dc4ce-20261029`; source bytes match the main checkout. `git diff --check` passed.

The failed write received a negative acknowledgement. Reading the Master region afterward returned byte-identical prior chest NBT, and the decoded chest item count remained exactly 17. This directly verifies preservation of the last acknowledged chest state during the simulated disk-write failure.

## Limits / disposition

This does not test backend disconnect during the failure, subsequent ownership handoff/restart, real Crafty write-rejection warnings, or the reported live empty chests. No source behavior or JAR changed. RC5-01 remains P0 `TESTING`; incident attribution and recovery remain unresolved. Never restore player data automatically.
