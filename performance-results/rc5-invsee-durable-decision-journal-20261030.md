# RC5 InvSee durable decision journal groundwork — 2026-10-30

## Implementation

Extended the two-owner transaction coordinator with a file-backed `PlayerInventoryTransactionJournal`, using the existing `FileLocker` atomic replacement/recovery utility. A commit record includes transaction UUID, both participant UUIDs, session UUIDs, nonnegative inventory revisions, exact before-image SHA-256 digests, request digest, full editor and target after-images, apply-ack mask, version, and a SHA-256 record checksum. The journal serializes commit and abort decisions under a shared per-directory process lock. An abort tombstone prevents transaction-ID reuse after restart. A durable commit cannot be aborted; owner ACKs are persisted idempotently. Corrupt records, invalid lengths, overlapping recovered reservations, or checksum mismatch fail closed. The coordinator reconstructs pending committed reservations on startup and retains both until both durable owner ACKs are present.

The production-facing constructor requires a journal. The no-argument coordinator is explicitly for in-memory tests. The journal/coordinator are **not instantiated by the running Master**, not connected to the existing `CommitPlayerDataTransactionHandler`, protocol capability negotiation, backend tick threads, InvSee++ plugin menus, cursor handling, live inventory after-images, or reconnect/apply messages. There is no RC5 click-path behavior change and no item conservation claim. RC5-02 remains P0 `BLOCKED`; the remote-click gate stays closed. The current Master endpoint still refuses cross-owner transactions until both live owners can explicitly prepare/attest.

Abort tombstones currently retain full request and after-image data and terminal records have no compaction/retention policy. Do not enable this journal in production until transaction lifetime, bounded retention, startup recovery ordering, single-coordinator ownership, disk-full/error handling, and owner recovery are integrated and reviewed.

## Regression and reproducibility results

Temurin Java 25.0.3.9 / Gradle 9.8; offline, `--no-build-cache`, one worker, separate detached worktrees at base `82c1b54750223ba96c5d7f91fd157ba4d7732fc8`, with the four current coordinator/journal source and test files overlaid. Each full `:multipaper-master:test` run passed **119/119**, zero failures/errors/skips. The focused coordinator/journal suites passed **17/17**.

The two clean Master artifacts are identical: 19,247,399 bytes, SHA-256 `7818BB00430CAB05DC113E7D08A5343D57360EF6A03D74AB952A5D7EC072B6B1`.

- Pass 1: `D:\multipaper-tests\worktrees\rc5-invsee-state-machine-pass1-20261030`
- Pass 2: `D:\multipaper-tests\worktrees\rc5-invsee-state-machine-pass2-20261030`
- Coordinator source: `B04A14B98FE7271393B7FB998B4A3D39D98F2307653FC06C85274F70EB0E8845`
- Journal source: `908D9472B7253D43E8E3FADE473B61B2CA882D6EA7AEF66D2C83994DE64D0A7D`
- Coordinator tests: `3532518B9059B078948D9F61D534A4D7C6B24F4E8ED292F965F8A482E40900F0`
- Journal tests: `DDA59856FE5D1FA41DE985FF6A0A7962BED0E901CAF839E79BF03688E8F71B6E`
- Focused JUnit XML hashes (pass 1 / pass 2): coordinator `4AD7622C91B16C60D4FC680FF0F9162697ACE6E7BFD36C2DA715E5B3A5A7F817` / `7A3D5ECC9430EEB6AF4A53778F4F52B0C44BE2C85EDB1A7EC0953269F6727C34`; journal `2F5CB9418E2A39FDED576C4ADA3E7DB67E461782119DFC479BCC4673B01FA849` / `17982D4C786BA351602F737E7796A0BF30CF8A108E988CA1C4BBF1317B1BC538`.

No Paper backend/protocol artifact was built, no InvSee runtime scenario was run, and no live SMP/Crafty process, world, or player data was accessed. This is not a releasable Master/Paperclip pair.

## Next required integration

Add an appended capability-gated protocol for exact owner prepare, commit-status recovery, apply-after-image, and durable apply ACK; marshal snapshot validation and mutation onto each owner’s player tick thread; use editor/target sessions, revisions, cursor and complete slot after-images; integrate the exact plugin click boundary; restore committed decisions before either owner accepts saves or new clicks; then add RC4/mixed-version, randomized item-total, simultaneous viewer, quick-move/drag, restart/owner-change fault-cut, and exact plugin two-backend runtime tests. Keep unsupported operations fail-closed until all those gates pass.
