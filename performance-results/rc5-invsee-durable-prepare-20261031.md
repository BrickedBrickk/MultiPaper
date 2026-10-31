# RC5 InvSee durable-prepare journal slice — 2026-10-31

## Problem and baseline reproduction

The Master inventory coordinator previously reserved both players only in memory during `PREPARING`; its journal began at the COMMIT decision. A Master restart in that window therefore lost the transaction ID, request, and participant reservations. Owners that had already reserved live state could no longer distinguish a durable abort from an unknown transaction. The new focused regression failed before the change because no `.itx` prepare record existed after `begin()` returned.

## Change

`PlayerInventoryTransactionCoordinator.begin()` now writes the prepare record before publishing in-memory reservations. Journal-write failure propagates as `IOException` and leaves both participants unreserved. Journal format v3 stores `PREPARING`, `ABORTED`, or `COMMITTED`; v2 commit/abort records remain readable. Startup restores PREPARING reservations but deliberately discards old owner attestations, requiring each current owner/session to attest again. A confirmed abort replaces the prepare state with a durable abort tombstone; a commit replaces it with the full durable after-image decision. Prepared records cannot receive apply acknowledgements and are excluded from `loadCommitted()`.

This slice does not instantiate the coordinator in Master or connect it to owner protocol, backend tick-thread reservations, player inventory save fences, InvSee++ clicks/cursor state, apply/replay, or live item conservation. Keep the remote click gate closed.

## Tests

Exact focused Java 25 command:

```text
 .\gradlew.bat :multipaper-master:test --tests puregero.multipaper.server.PlayerInventoryTransactionJournalTest --tests puregero.multipaper.server.PlayerInventoryTransactionCoordinatorTest --no-daemon --no-configuration-cache --no-build-cache --max-workers=1
```

Result: **22/22 passed**, 0 skipped, 0 failures/errors: coordinator 9/9, journal 13/13. Coverage includes the reproduced missing-prepare baseline, durable begin-before-return, restart recovery with fresh attestations, durable abort/restart release, failed journal write without reservations, idempotent prepare/ID conflict, v2 commit-record read compatibility, randomized committed recovery, and existing commit/apply/abort tests.

- Coordinator SHA-256: `9B4B575E34E4A9B25AE2C8EEBA7F6DDBAF47B62F76785EE21FCD1B2187A40146`
- Journal SHA-256: `A17AE3D44334D6863EB136ADD38034F2BAB13FBB8B8EB09ECC557531A150A837`
- Coordinator test SHA-256: `20BDB0DD5E6360E3FA98F81B1F80EA68E64AC6126D4F79B0FE558030B2B0DFDA`
- Journal test SHA-256: `6F9A95BC7492C31E157F6FEA6BACBF10FAD64CA8DA1A5420C0D038C5431066D8`
- JUnit XML SHA-256: coordinator `0F523649AD7180C9BAF52CC3550CD627C67A57CD413BDA73076DEBE7E3EC8DFC`; journal `B5B339F1CC27E7524410793BEFB69F815377C5FCA22BFD340DCDF0DA6DCCB752`.

Two clean full-project test/build passes and full artifact-hash comparison are still required. The later two clean Master-module builds are documented separately; no installable release pair is qualified by this focused slice.
