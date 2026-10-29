# RC5 randomized playerdata journal item-total regression

## Change and verification

Added `randomizedTransfersPreserveAggregateItemsAcrossJournalCrashCuts` to `MultiPaper-Master/src/test/java/puregero/multipaper/server/PlayerDataTransactionManagerTest.java`. Across eight fixed seeds, it performs 4,096 randomized transfers between two journal participants while keeping the combined item count at 64. One in eight operations injects an `IOException` after either the first or second participant-file replacement; the next Master journal read must recover the entire batch. Every step checks each participant's expected stack and the aggregate total.

Added `randomizedStaleContenderCannotDuplicatePlayerdataTransfer`: 512 randomized rounds create two distinct transfer after-images from the same two-player before-image. `commitIfCurrent` must accept the first and reject the second stale contender; readback after every round must equal the accepted state and preserve the aggregate total of 64.

The test was committed as `20769eede79ac863828196734930db23b77555d2` (`test: assert playerdata journal item conservation`) and pushed fast-forward to the verified `fork` remote, `BrickedBrickk/MultiPaper`, branch `feature/port-26.3`. The remote ref was re-read and resolves to the same commit. Only this test file was included; unrelated worktree changes remain unstaged/uncommitted.

The eight-seed/4,096-transfer expansion and updated evidence were committed as `241cedb1ee039f6ffc2ffe2356d03fa0f4c181b6` and pushed fast-forward; the remote branch was re-read at that SHA. Only the transaction test and its report were included.

Temurin Java 25.0.3+9, Gradle 9.8, isolated Gradle user home `D:\multipaper-tests\gradle-home-rc5-invsee-focused-20261029`, offline, one worker, no daemon. The full `:MultiPaper-Master:test` suite passed **88/88 across 10 suites**, zero failures/errors/skips, including all Master process-restart persistence tests and both randomized conservation tests. Updated `PlayerDataTransactionManagerTest` XML SHA-256: `EE5783E76AAB861FF23FA53C26ACCFFD76663322CCC15223261C19036AFDC103`.

This test-only change did not alter the Master JAR. Its SHA-256 remains `85578CE7DE91A57944DEB12C811AC1C4C648638D62937BC6EBB1E325D0015578`. Updated test source SHA-256: `11C534450E273906061EAFE9FBB76878C18ECCE8F0C7E2948AC39336DF7CD46D`.

## Limits

This validates Master WAL atomic roll-forward for supplied participant snapshots; it does not exercise InvSee++ menus, live NMS inventory/cursor snapshots, owner attestation, a two-backend click, or real item NBT. It is not an end-to-end item-conservation fix. Keep remote InvSee++ clicks fail-closed and RC5-02 P0 `BLOCKED`. No Paper build, live server, Crafty host, or world was accessed.
