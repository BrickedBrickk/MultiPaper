# RC5 forced chunk ownership versus pending chest save — 2026-10-29

## Finding and reproduction

An isolated Master integration regression exposed a real persistence ordering bug in forced chunk ownership. Backend A owned a chunk containing chest NBT (11 diamonds), announced a later save containing 23 diamonds, and Backend B requested forced ownership while also owning a witness chunk. Before the fix, Master transferred ownership immediately. A's final region write was rejected by the owner fence; that rejection cancelled A's pending write barrier, so a waiting Master read could continue with the old chest image. The pre-fix test failed at the expected assertion: the final owner write was rejected.

This is an actual Master synchronization hazard and a plausible stale chest view under this sequence. It is **not evidence** that this race caused the live RC4 empty chests or the 273 no-holder warnings.

## Change

- `WriteLockManager.waitForLockAsync` exposes the existing per-chunk persistence barrier as a future; the callback API now composes through that same barrier.
- `RequestChunkOwnershipHandler` waits for all requested chunks' announced/active saves before transferring ownership, rechecks that the requester still owns a requested witness chunk, and only then replies success.
- Regression coverage verifies forced transfer remains pending until the old owner's final snapshot is written, then verifies the next owner reads byte-exact chest NBT and diamond x23. A lock-manager unit test verifies the async barrier lifecycle.

## Verification

- Red reproduction on Java 25 / Gradle 9.8: 1 test failed at the expected pre-fix final-write acceptance assertion.
- Green focused regression: `forcedOwnershipTransferMustNotReleaseFormerOwnersPendingChestSave` passed.
- Full serial offline Java 25 Master + MasterMessagingProtocol `--rerun-tasks`: **103 tests passed, 0 failures, 0 errors, 0 skipped** (Master 90; protocol 13). This includes existing ownership, persistence, Master restart, and concurrent chest reader tests.
- Two sequential Java 25.0.3.9 / Gradle 9.8 `clean build` passes for Master + MasterMessagingProtocol, run offline serially with `--no-build-cache`, each passed the same 103 tests with zero failures/errors/skips. The shaded Master JAR matched byte-for-byte: SHA-256 `9AB57334AAF12ED4C55DC79D54ECC361D238F82391A9D4E3346D429BC6F12E7A` on both passes; `jar tf` enumerated 4,556 entries successfully.
- `git diff --check` passed. No live Crafty/SMP process or world was accessed.
- JUnit XML SHA-256: MasterIntegrationTest `C64AF4C2C5DB7FCD771A79B3C9C8BE5535A214ACE503F1FFDBF33272A773ED58`; WriteLockManagerTest `CEF2688ED1FF2D7DA704C950305CDE3BDC203F60745478221042D83C50CCAA6C`; protocol MessageHandlerTest `B66588C26CDD4B38F874DB00E800DC68E80E03B62FA35C6040C2E957DD2A7721`.
- Current source hashes: WriteLockManager `64703D489E4B8CFCADEA54755F3D7E02DE7169EB1449F83A425BA456A888359B`; RequestChunkOwnershipHandler `53243B99539171C4C41BDC9140FFC1752FC1100BC57C1B2E504DBF3FA64D8365`.

## Limits and next gates

## Follow-up source audit: remaining late-announcement interleaving

A read-only review originally found a late-announcement gap after the first barrier-only fix. That historical gap is now covered by the atomic active-owner guard and red/green regression below. The reviewer also noted a request could remain unanswered if a barrier is announced but never completed/cancelled while the owner remains connected; that liveness concern remains open. Existing callers retain the Boolean API: container requests retry denial/error; the scheduled tick path falls back to sending ticks to current owners. No behavior runtime or tests for those caller effects were performed.

At that point only the registered-save subcase was verified; the statement below supersedes the earlier `FIX IN PROGRESS` status for the reproduced ownership-promotion race. The unresolved barrier liveness case and absent live incident attribution/recovery still keep RC5-01 P0 `TESTING`. This Master/protocol test evidence is not a full Paperclip release gate.

## Late-announcement regression and follow-up fix — 2026-10-29

The ordered integration test now buffers the old owner's `WillSaveChunkLater` frame without flushing it, then requests forced ownership from a backend that owns a witness chunk. The pre-fix test failed because the target remained actively owned by the old backend but ownership moved before Master received the announcement. The fixed test passed: after flushing the announcement, the old owner can write the final chest snapshot, unlock, and only then can the next owner claim and read the exact final NBT (diamond x23).

`ChunkSubscriptionManager.forceLockIfNoOtherOwner` acquires the distinct striped chunk locks in ascending order, checks every requested chunk before mutating any ownership, and force-locks the whole set only if no target is actively owned by another backend. This makes ownership promotion atomic relative to single-chunk lock, unlock, and save-registration handlers. The existing `chunkOwnershipCanBeRequestedForcedTransfer` test now verifies an active owner must release before the claim. Serial offline Java 25 / Gradle 9.8 Master and MasterMessagingProtocol tests passed **103/103** (90 Master, 13 protocol; zero failures/errors/skips). Two sequential clean offline no-build-cache `build` passes then passed their full tests and produced identical `multipaper-master-3.0.0.jar` SHA-256 `B7D7A445223E37B601BAB2755F950E745BABF3A79AB154212CA28323E329E6CE`. Logs: `D:\multipaper-tests\rc5-forced-handoff-clean-builds-20261029\pass1.log` and `pass2.log`. Current `MasterIntegrationTest` JUnit XML SHA-256: `A97A46DADB2B82C10CA302EA96762C9ED98CA25359EC33BD8C44653E1322FC96`. This verifies Master/protocol only, not full Paperclip.

Remaining liveness gap: a request may still wait indefinitely for a registered save whose owner neither writes, cancels, nor disconnects. No live data or servers were accessed. RC5-01 stays P0 `TESTING`.

## Active-owner conflict fast-denial regression — 2026-10-29

The handler now checks for another active owner before waiting on save barriers and returns a negative ownership reply immediately. The final atomic all-chunk guard remains in place after barrier waits, so ownership changes racing after the early check cannot cause partial claims. This avoids tying a losing force-claim request's response to completion of the other backend's save; the previous owner remains able to persist its final image.

The ordered `MasterIntegrationTest` now verifies two schedules in the same exact chest scenario: the old owner's `WillSaveChunkLater` is first held in its socket and the request is denied; then the announcement is flushed/registered while the save remains incomplete and the request is again denied without waiting. The old owner writes diamond x23, unlocks, and the new owner claims and reads the exact snapshot. Full offline Java 25 / Gradle 9.8 Master + protocol suites passed **103/103** (90 + 13; zero failures/errors/skips). Two sequential clean no-build-cache builds, each including all tests, produced identical `multipaper-master-3.0.0.jar` SHA-256 `016630EA339D8525C887B7F09F2CF6D466A2E8804CDEEF27B6F3BF81CD3C7886`. Current MasterIntegrationTest XML SHA-256 `547C00E4C94360F04790321BD91F704EAD963D6A387AA8C062D5621CA646AAF5`. Build logs: `D:\multipaper-tests\rc5-forced-handoff-fast-denial-clean-builds-20261029\pass1.log` and `pass2.log`. No live backend/world was accessed; this remains Master-only evidence.
