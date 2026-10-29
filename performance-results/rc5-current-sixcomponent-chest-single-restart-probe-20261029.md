# RC5 current six-component single-chest probe — 2026-10-29

## Scope

Used the disposable six-component fixture `D:\multipaper-tests\system-temp\rc5-current-sixcomponent-sequential-shutdown-20261029-003000`, world `rc5chestprobe61029003000`, with Master diagnostics temporarily enabled to attribute rejected shutdown writes. No live Crafty process, host, port, log, or world was contacted or changed. The diagnostic code was removed from the working tree after capture. This was a one-chest sequential-shutdown check, not the separate 273-read regression.

## Results

- Synthetic client `StackSmoke` logged into the proxy and the standard command/status smoke passed. A chest in Backend A's loaded player-adjacent chunk at `(-38,79,-199)`, chunk `(-3,-13)`, was seeded as exactly 17 diamonds; the immediate block-entity read confirmed that count.
- Backend A then stopped while Master remained online. Backend B read the chest after A stopped and observed the exact 17-diamond stack. This is evidence that this chest's contents survived that ownership transition and were visible to B.
- Backend A nevertheless logged 8 failed chunk writes (16 matching lines including exception causes) during shutdown. Temporary Master diagnostics attribute all 8 to writes from `smoke-a` after that same connection had relinquished ownership: `hasOwner=false`, `previousOwner=smoke-a`. They were not writes from a concurrent different owner. This confirms a late former-owner write/release ordering case; it does not by itself show that the chest chunk was among rejected chunks or that the visible chest data was lost.
- Backend B stopped gracefully and restarted to `Done`; the post-restart exact chest assertion did not complete before the runner entered cleanup. Therefore this run is **inconclusive for backend-restart persistence** and fails the runner's strict no-rejected-write gate. Do not count it as a passing persistence test.
- Master remained in the fixture for subsequent restart and final state verification. All fixture processes exited. Test ports 32651, 32652, 32661, 32662, and 32672 had no listeners at final audit.

## Evidence hashes

Fixture logs are retained at `D:\multipaper-tests\system-temp\rc5-current-sixcomponent-sequential-shutdown-20261029-003000\six-component-smoke-20261029-003908`:

| File | SHA-256 |
|---|---|
| `backend-a.out` | `5DC8B8C5A136F4130CD975DC186ED716A974C228C58B4B7B9DEA2EF7D552E1DE` |
| `backend-b.out` | `3A8345DE0EBC0119680EEADA543A2EA41A061C1331EEDBA8A2CC4747AF9136EF` |
| `backend-b-restart.out` | `FA6622B1995EA8BA942AB948C6A1D97C2FBFDC210593489FE1D12F3BD496FF66` |
| `master.err` | `D7D4CA6C0C5AADE21BF1FE929FFD756420F6E57069D39C727125AB0DF2974034` |

## Interpretation

This is consistent with the existing owner fence rejecting late writes after ownership release. The chest remained readable during the tested A-to-B handoff, while other shutdown chunk writes were rejected. Existing exact-current bidirectional 273-read tests separately pass with exact single/double chest contents through backend and graceful Master restarts. The new simple probe does not supersede those tests and does not explain the reported live empty chests. Keep RC5-01 P0 `TESTING`; retain the fail-closed persistence guard. No automatic restoration or live-world access occurred.

After removing the temporary diagnostic lines, rebuilt `:MultiPaper-Master:shadowJar` with Temurin 25.0.3+9, `--rerun-tasks --no-daemon`; build succeeded and `MultiPaper-Master/build/libs/multipaper-master-3.0.0.jar` SHA-256 is `1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E`, matching the retained clean-built current-source artifact. The working-tree `ChunkSubscriptionManager.java` has no temporary diagnostic diff. This was a compile/package task, not a new unit-test run.

## Follow-up: mutate the chest after flush, then reject a late shutdown write

A second isolated run used the same disposable six-component fixture/world. The player spawn placed the chest at `(-34,88,-217)`, chunk `(-3,-14)`. Backend A seeded diamond x17, completed `save-all flush`, then changed that chest to diamond x29 and immediately stopped. The synthetic client had no subsequent chest action.

Master diagnostics recorded an ownerless rejection for the exact chest chunk `(-3,-14)`, with `sender=smoke-a previousOwner=smoke-a`; one other chunk `(-1,-14)` also had a late write rejected. After A stopped, Backend B read exactly diamond x29. B was then stopped, restarted, and read exactly x29 again. Thus, for this controlled timing, the rejected chest-chunk write was a late duplicate of a newer snapshot already accepted by Master; the latest chest contents were not lost across handoff or B restart. This materially narrows the earlier warning concern, but does not prove every shutdown write is redundant or attribute the live empty chests. The smoke intentionally reports four matching Paper error lines for two rejected writes, so its strict zero-rejection log gate remains FAIL even though chest conservation assertions pass.

Result text was emitted by the runner (no separate result file). Retained streams in the same fixture directory `six-component-smoke-20261029-004513`:

| File | SHA-256 |
|---|---|
| `backend-a.out` | `A25BA77FD867F0932C6949559B6BBF68BD34F5F5DBC31C8470C575C41E080CAA` |
| `backend-b.out` | `3D0D1C79DC6C383F3C0B8E6BAF41066353FC58CEDD54E506C1EF24FD91BBCDD6` |
| `backend-b-restart.out` | `0D4EC1F635DC70732CD7C2F55F1844B89E94A1B0443D6080755928A86BF1E4CB` |
| `master.err` | `5C1AE96C9F46857D21B68D8C17F1AF7D8081F8C6786B5D32D847DFCD58EF299A` |
| updated runner | `975043F7958B1083035200393086068C0EC065EC0C82AAD4B9971684D6297797` |

All fixture processes exited and reserved ports 32651/32652/32661/32662/32672 were closed. The diagnostic Master instrumentation was not added to source or release artifacts; the normal rebuilt Master remains hash `1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E`. RC5-01 stays P0 `TESTING` because the log warning has no chest identification and the reported live loss remains unverified.
