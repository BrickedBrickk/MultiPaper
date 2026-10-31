# RC5 InvSee journal randomized recovery regression — exact current HEAD — 2026-10-30

## Scope

Ran the locally authored test-only extension against clean detached source HEAD `a87d9562a8d52e28837afa01c80c86b9c0c1555e` in `E:\multipaper-tests\rc5-current-a87-pass1`. Only `PlayerInventoryTransactionJournalTest.java` was copied into that fixture; production sources were unchanged. Java was explicitly Temurin 25.0.3.9, Gradle 9.8, one worker, no daemon/configuration/build cache, and tasks were serialized.

## Result

Command: `:multipaper-master:test --tests puregero.multipaper.server.PlayerInventoryTransactionJournalTest --rerun-tasks --no-daemon --no-configuration-cache --no-build-cache --max-workers=1`

**9/9 tests passed**, zero failures/errors/skips; Gradle reported `BUILD SUCCESSFUL` in 15 seconds. The new deterministic seeded regression executes 32 transaction iterations, alternating which participant ACKs first, reconstructing the coordinator after the durable decision and first ACK, retaining both reservations until both durable ACKs, and verifying exact persisted after-images after restarts. Test source SHA-256: `4D77016AE46A929F46D7B4C3E5B9F1A905103CBCB95F7C7860D76253FCA7169D`. JUnit XML SHA-256: `8EA645FD550229AA0FAC0A14B2249634B7CD2AEAB3BE1E6265F90DC5C28F5D1D`. Gradle log SHA-256: `E1FEA226CACA8B6B49477D8718DA1812E6B08CBEB73BFE7FE4E62D66FCD886B8`.

The run’s product source was exactly current pushed HEAD; the test source was an uncommitted local change, so this is focused regression evidence, not a clean-HEAD suite result. The detached worktree is now dirty only by this copied test. No process remained running after Gradle exited.

## Limits

This tests Master-side journal serialization/reconstruction, not live inventory state or item totals. The journal/coordinator still has no production caller, owner prepare/apply protocol, tick-thread snapshot/mutation fence, InvSee++ click/cursor integration, reconnect recovery, or exact plugin two-backend runtime. It does not reproduce or fix the reported duplication. Keep RC5-02 P0 `BLOCKED` and remote cross-owner edits fail-closed. No live server/world/player data was accessed.
