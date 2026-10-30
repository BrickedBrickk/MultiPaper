# RC5 InvSee owner-transaction state machine groundwork — 2026-10-30

## Scope

Added an isolated Master-side in-memory coordinator for two-player inventory transaction reservations. Each request identifies distinct editor/target UUIDs, live session UUIDs, nonnegative revisions, SHA-256 before-image digests, a transaction UUID, and a request digest. The synchronized state machine makes transaction IDs idempotent, refuses overlaps, requires both exact owner attestations before prepare, and prevents a commit decision before prepare. It keeps participants reserved after commit until both owner apply acknowledgements arrive. A pre-commit abort releases only after the caller confirms durable Master state contains no commit decision. Timeouts and caller failures do not expire reservations.

This is not connected to Master WAL persistence, backend owner/tick-thread handlers, messaging protocol, InvSee++ menus, cursor or inventory after-images, reconnect recovery, or the click path. `recordDurableCommitDecision` relies on its documented caller contract and does not itself persist a decision. It is groundwork only: P0 InvSee++ conservation remains BLOCKED and the remote click gate must stay fail-closed. It does not fix duplication.

## Focused and full tests

Temurin Java 25.0.3.9, Gradle 9.8, offline, single worker, no daemon/cache:

- `PlayerInventoryTransactionCoordinatorTest`: **9/9 passed**, including atomic concurrent reservation, idempotent replay/conflicting ID, stale attestation, both-owner prepare, commit-before-prepare denial, no timeout release, durable no-commit abort requirement, commit not abortable, and reservation release only after both apply acknowledgements.
- Full `:multipaper-master:test`: **111/111 passed**, zero failures/errors/skips in each of two independent clean detached worktrees at base `82c1b54750223ba96c5d7f91fd157ba4d7732fc8`, with exactly these two untracked source/test files overlaid. Existing chest persistence, owner fencing, Master restart, and playerdata WAL regressions passed in both runs.
- Both clean Master JAR outputs are byte-identical: **7827B42B28561E28062696DFBF54F14CF41A4BE76F95C025AD1414927369BB24**.
- Focused JUnit XML SHA-256: pass 1 `22EF9A5CF174A48C01794147E75E751B7E69702F43682B5E19DB5BCEC5DFBA00`; pass 2 `A0478FA67009A358754513C3AB133392ED245C390BB99B07E04E468E1F4B5EB2`. Master integration JUnit XML SHA-256: pass 1 `EFEE2E45B24FE84BB37E8A29EB207BC7369BD3111BA99E66E8787C5207AAF88B`; pass 2 `89C9F23C02D6027D092CC959B4B93FC8E87CE050AC4AFE228AD9B8FE8B42FE44`.

Build worktrees:

- `D:\multipaper-tests\worktrees\rc5-invsee-state-machine-pass1-20261030`
- `D:\multipaper-tests\worktrees\rc5-invsee-state-machine-pass2-20261030`

Current source SHA-256: `PlayerInventoryTransactionCoordinator.java` `9D72D14DBD76C06481236C569D815E7B5ED19AE9187108701BF237A15A41A7D4`; `PlayerInventoryTransactionCoordinatorTest.java` `5E25738BDFB76785F258C5961EB9055C1B0FBDCB5D20708A58C7DA49E0BB0B0D`.

## Limits and next work

No protocol or Paper backend artifact was built. No RC5 pair/release artifact was produced. No live SMP, Crafty process, world, or player data was accessed. Next required implementation is durable transaction decision/replay plus capability-gated owner prepare/apply handlers marshaled onto both player tick threads; then exact InvSee++ click/cursor and after-image tests, crash/reconnect/owner-change recovery, and an exact-plugin two-backend runtime. Keep the current gate closed until those pass.
