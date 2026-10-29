# RC5 randomized playerdata journal item-total regression

## Change and verification

Added `randomizedTransfersPreserveAggregateItemsAcrossJournalCrashCuts` to `MultiPaper-Master/src/test/java/puregero/multipaper/server/PlayerDataTransactionManagerTest.java`. Across eight fixed seeds, it performs 4,096 randomized transfers between two journal participants while keeping the combined item count at 64. One in eight operations injects an `IOException` after either the first or second participant-file replacement; the next Master journal read must recover the entire batch. Every step checks each participant's expected stack and the aggregate total.

The test was committed as `20769eede79ac863828196734930db23b77555d2` (`test: assert playerdata journal item conservation`) and pushed fast-forward to the verified `fork` remote, `BrickedBrickk/MultiPaper`, branch `feature/port-26.3`. The remote ref was re-read and resolves to the same commit. Only this test file was included; unrelated worktree changes remain unstaged/uncommitted.

Temurin Java 25.0.3+9, Gradle 9.8, isolated Gradle user home `D:\multipaper-tests\gradle-home-rc5-invsee-focused-20261029`, offline, one worker, no daemon. Focused `PlayerDataTransactionManagerTest` passed **7/7**. The full `:MultiPaper-Master:test` suite then passed **87/87 across 10 suites**, zero failures/errors/skips. All Master process-restart persistence tests passed. Updated focused XML SHA-256: `2355EA58E9B3C4901331BF078F7A1E0B3FEAB73E2075013F130222C1462C9E66`.

This test-only change did not alter the Master JAR. Its SHA-256 remains `85578CE7DE91A57944DEB12C811AC1C4C648638D62937BC6EBB1E325D0015578`. Updated test source SHA-256: `C30B0F030E752E172C58CAE61A45C47A722244FC019686F1E68C868138CD9FEE`.

## Limits

This validates Master WAL atomic roll-forward for supplied participant snapshots; it does not exercise InvSee++ menus, live NMS inventory/cursor snapshots, owner attestation, a two-backend click, or real item NBT. It is not an end-to-end item-conservation fix. Keep remote InvSee++ clicks fail-closed and RC5-02 P0 `BLOCKED`. No Paper build, live server, Crafty host, or world was accessed.
