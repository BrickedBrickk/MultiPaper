# RC5 InvSee++ player-operation queue cancellation fence — 2026-10-29

## Reproduction

The current `PlayerDataOperationQueue` returned its internal completion future directly from synchronous `submit` and `submitAll`. If a caller cancelled a queued operation's returned future while it waited behind an earlier async participant reservation, the queue map held a cancelled future. Later work chained through that completed-cancelled future and ran before the earlier reservation's owner acknowledgement. This is an ordering/concurrency defect in the transaction groundwork; it is not by itself a reproduced item-duplication event.

Two deterministic tests were added:

- `cancellingSinglePlayerWaiterDoesNotLetLaterWorkOvertakeEarlierReservation`
- `cancellingMultiPlayerWaiterDoesNotLetLaterWorkOvertakeEarlierReservation`

Before the fix, both failed with `later.isDone() == true` while the preceding acknowledgement was still pending. The other four tests in `PlayerDataOperationQueueTest` passed (2 failures, 4 passes).

## Fix and verification

Synchronous `submit` and `submitAll` now return `next.copy()` rather than the internal reservation future. Caller cancellation cancels only the caller's wait handle; queued work continues to hold its participant barrier. Existing `submitAllAsync` already used this pattern.

After restoring the two pre-existing queue ordering tests alongside the new coverage, the focused suite passed **8/8**. The full Master module passed **86 tests across 10 suites**, 0 failures, 0 errors, 0 skipped.

Two sequential clean Java 25.0.3+9 / Gradle 9.8 builds ran in the single-agent worktree, offline, with `--no-build-cache --max-workers=1`:

```powershell
.\gradlew.bat :MultiPaper-Master:clean :MultiPaper-Master:build --offline --no-daemon --no-build-cache --max-workers=1
```

Both final clean builds passed all 86 Master tests. The shaded Master JAR matched byte-for-byte in both passes: `MultiPaper-Master/build/libs/multipaper-master-3.0.0.jar`, 19,211,000 bytes, SHA-256 `85578CE7DE91A57944DEB12C811AC1C4C648638D62937BC6EBB1E325D0015578`. Focused XML SHA-256 after the final pass: `FFD8EAD3C173E6B7D52E612E63EEBEDDB98C20CFE5888FAC3ABEAB5D600F98DE`.

Source SHA-256: `B8949E3FB2A40E2829959C191D95B558C602E089870101689510993CF8D36995`. Regression source SHA-256: `7ED6A84F20AE793806D9729296B9D7E3C490CE6F5215C8904A79B232335F4B34`.

## Limits

No Paperclip was built from this source, and this does not implement or enable the missing owner-attested cross-backend InvSee++ click protocol. Remote edits remain fail-closed. No two-backend item-conservation test was added; RC5-02 remains P0 `BLOCKED`, and this Master JAR is not an RC5 release artifact. No live SMP, Crafty machine, or world was accessed.
