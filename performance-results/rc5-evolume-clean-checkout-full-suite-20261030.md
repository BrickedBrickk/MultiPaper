# RC5 exact-HEAD clean checkout and full module test run — 2026-10-30

## Fixture and command

- Verified fork branch: `feature/port-26.3`, exact HEAD `3bc6f46f3c6dc006540b5dc3b74b7033a35ccdb2`.
- Clean detached worktree: `E:\multipaper-tests\rc5-paperweight-e-volume-3bc6f46-20261030`; tracked source remained clean before build and after tests. The XML hash CSV was added afterward as local evidence. This checkout excludes unrelated dirty documentation/scripts in the main worktree.
- Runtime: Temurin Java `25.0.3.9`, Gradle `9.8`, `--no-daemon --no-configuration-cache --no-build-cache --max-workers=1`.
- `applyAllPatches` completed successfully in 10m39s, including `setupMacheResources`, all Paper/Purpur/MultiPaper patch application, and generated `multipaper-server` sources. This resolves the earlier E:/D: Paperweight setup stall for this clean checkout.
- Focused tests passed 22/22: `MultiPaperEntitiesHandlerDependencyTestSuite` 3, `EntityUpdateNBTPacketTestSuite` 6, `PlayerInventoryUpdatePacketTestSuite` 11, and `GameRuleUpdatePacketTestSuite` 2.
- Full serial tasks then passed: `:multipaper-master:test`, `:multipaper-mastermessagingprotocol:test`, `:multipaper-server:test`. Totals: **10,337 tests, 10,251 passed, 86 skipped, 0 failures, 0 errors**, across 156 XML reports (Master 99/99, protocol 13/13, server 10,225 tests / 86 skipped).

The full run includes Master populated-chest handoff/restart, failed-final-chest-write retention, concurrent region access, playerdata transaction recovery/queue tests, the boat non-Mob dependency regression, entity NBT, inventory owner-source packet, gamerule, and scheduled-tick packet tests. Skips are recorded in the XML; no skip is represented as a pass.

## Hashes and limits

- Byte-exact full Gradle console log retained locally at `E:\multipaper-tests\rc5-paperweight-e-volume-3bc6f46-20261030\rc5-full-module-suite-20261030.log`, 84,574 bytes, SHA-256 `78476F994ECD2C00958EFD1F8C23B03D46F7D510A2B8BE1E6C8ED8048B39B1FB`. The raw log is not committed because Gradle emitted a trailing space.
- Sorted XML SHA-256 manifest `performance-results/rc5-evolume-test-xml-sha256-20261030.csv`, 23,453 bytes, SHA-256 `A2FA024720CEDE137D28C1AC1AB5B25ED005F350C86FBB4D12D648E711E97C0A`.
- Test-built Master JAR SHA-256 `86F694BA1882E2D08DC841ECE62EA11B105663DAADEF3D7C1C8DF52B1531BE19` (matches the previously validated Master output).
- The test task built/generated sources and a Master test JAR; it did **not** produce a Paperclip binary or a matched release pair. This is one clean checkout/test pass, not two clean reproducible release builds or a runtime/player test.
- The passing synthetic chest tests do not attribute or recover the reported live empty chests. The packet-authority tests do not implement cross-owner InvSee++ click transactions or prove click/cursor item conservation. No live SMP/Crafty process, world, or player data was accessed.

## Disposition

Paperweight backend test setup is no longer blocked on this E: fixture. RC5-01 remains P0 `TESTING` and RC5-02 remains P0 `BLOCKED`; release publication remains blocked pending item-conserving cross-owner InvSee++, live chest incident attribution/recovery evidence, a current paired Paperclip/Master release build and remaining installation gates.
