# RC5-13 delayed entity recovery test retry — 2026-10-29

## Attempts and corrected isolated run

An initial Java 25 run in the D: checkout stalled before test compilation while Paperweight traversed files during `applyPaperMinecraftResourcePatches`. In a fresh checkout, invoking the subproject test before `applyAllPatches` failed main compilation because upstream Minecraft/Paper dependencies had not been generated. After applying patches, filtering by the underlying test class selected no tests because this project requires its `EntityUpdateNBTPacketTestSuite`. Neither invalid invocation executed an assertion; these are not product failures.

The isolated C: worktree was created at exact HEAD `dfe0f0bdec39115580f0f4b8df7d66cba93f6f21`. Its initial Git checkout stalled and left a zero-byte lock. After confirming no Git processes remained, the lock was preserved as `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\.git\worktrees\rc5-entity-test-dfe0-20261029\index.lock.stale-20261029-1002`; the isolated worktree was restored to its recorded HEAD. Its worktree lock was then cleared without deleting files. The pre-existing modified C: worktree was never changed.

Ran the repository's required two-invocation Paperweight sequence with explicit Temurin 25.0.3+9 / Gradle 9.8:

```text
applyAllPatches --offline --no-build-cache --no-daemon --console plain --max-workers=1
:multipaper-server:test --tests puregero.multipaper.externalserverprotocol.EntityUpdateNBTPacketTestSuite --offline --no-build-cache --no-daemon --console plain --max-workers=1
```


`applyAllPatches` succeeded, applying the MultiPaper server patch set in the disposable worktree. The focused suite then passed **6/6 tests, 0 failures, 0 errors, 0 skipped**. This includes the previously pending `earlyUpdateCanBeRecoveredByLaterChunkLoadWithoutDuplicateUuid` helper regression. JUnit XML SHA-256: `5E037DD4B66923E74E8DC1EF27AF6B9950A191A08A4CAE02D2F19EF961EAEB69`; modified test source SHA-256: `C95390146326E5E22E42ED91D2BA6D6956E959823632E7906C4B0FE4399C4DB0`. The test worktree remains registered and detached at the pinned HEAD with only that copied test file modified.

## Scope and limits
This is helper-level coverage of early rejection, later canonical registration, repeated UUID update and exactly-one in-memory UUID assertion. It does not exercise a real two-backend packet schedule, passenger graph, owner handoff, restart, or prove the real 13 warnings recovered. No production source changed; the result is not an end-to-end entity fix. Keep RC5-13 P1 `REPRODUCED`, not `VERIFIED`. No live SMP, Crafty process, or world was contacted.
