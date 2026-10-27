# RC5 inventory conservation regression coverage — 2026-10-27

## Change

Added `repeatedRandomRemovalConservesExactItemCountsAcrossPlayerCompartments` to `multipaper-server/src/test/java/puregero/multipaper/MultiPaperInventoryHandlerTest.java`. With fixed seed `0x4d756c7469506170`, 256 generated inventories contain diamonds, emerald distractors, and empty slots across player-owned compartments. Each scenario removes a random diamond count up to 64 and asserts exact diamond conservation, full consumption of the removal request, and no change to emerald totals.

This exercises the low-level stale-inventory compensation removal helper. It does not simulate `PlayerInventoryUpdatePacket`, real concurrent GUI viewers, shift-click, drag distribution, restart persistence, or real-player traffic; it is supplemental coverage, not proof that all reported duplication is fixed.

## Verification

- Worktree: detached `483294876697a614ac0e55cfa327bf90c285cb88`, `C:\Users\elibo\AppData\Local\Temp\rc5-inventory-property-20261027`. Java: Temurin 25.0.3+9; Gradle 9.8; `--no-build-cache --no-daemon --max-workers=2`.
- After `applyAllPatches`, focused `:multipaper-server:test --tests puregero.multipaper.MultiPaperInventoryHandlerTestSuite` passed: 36 tests, 0 failures/errors/skips. The suite includes the changed test class. A direct individual-class filter is unsupported by this project’s `**/**TestSuite.class` include rule and found no tests; the suite invocation is the valid focused command.
- Two sequential clean runs of `build createPaperclipJar createBundlerJar :multipaper-master:shadowJar` both passed. Each full test report: 188 XML suites, 10,746 tests, 0 failures, 0 errors, 88 skipped. The added property test appears in both reports.
- The five outputs matched exactly between the two clean builds:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Paperclip | 64,333,230 | `E0F9F3739A2B0AA07090FA684495C9C09919B8D61F435F9DE5B5C6ADB897626B` |
| Bundler | 107,645,242 | `C770FEB2CCD342953C502B5F2EB05D234646A7D075AAC339006F5B854A5E62B1` |
| Server | 29,306,861 | `E4453B2C0B66A48F2F054DB54D95D654721CA6C2085DC4A77B771C41E075F533` |
| Master | 19,181,432 | `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7` |
| Master messaging | 110,381 | `0C1F9690E33C02865AF0ADB4E396D277CD3A41F0AEC2FD5B2FB7692F1F59B97A` |

These are verification-build outputs, not RC5 candidates. The server JAR differs from the staged RC4 server only in its manifest (`Implementation-Version`/`Git-Commit`: current docs HEAD `4832948` instead of product commit `2265a5a`); all other server JAR entries compare byte-for-byte equal. Paperclip’s changed payload is its server patch reconstructed from that manifest difference, and the bundler includes that server JAR. No production Java source or release binary was changed.

## Limitations and next work

- The additional exact-candidate shift-click protocol attempt on 2026-10-27 did observe a cross-backend owner pair after the probe was routed through the Master, but GrimAC disconnected the idle synthetic target while the probe awaited `GO`. The target was seeded with eight test diamonds; no click or post-click conservation assertion occurred. Do not infer success or failure from it; see `rc5-investigation-triage-20261027.md`.
- No new production defect was reproduced in this test pass. Do not publish RC5 or relabel these outputs as RC5. Continue with controlled cross-owner GUI transaction scenarios, including two simultaneous viewers, shift-click and drag, then restart/persistence cases if an exact pair can be established. Leave human gameplay, Bedrock, old-client behavior, broad farms, and intended-hardware capacity gates explicit until measured.
- An initial test on D: encountered the previously documented Paperweight `SetupMinecraftSources` stall at Windows `FileDescriptor.close0`; that disposable Gradle run was interrupted. Patch preparation and builds succeeded from C: temp. No live service, Crafty host, world, or player data was accessed or modified.
