# RC5 InvSee prepare-session reconnect recovery — 2026-10-31

## Finding and change

The durable inventory journal restores an undecided `PREPARING` transaction and requires both owners to attest again. Before this change, `PlayerInventoryTransactionCoordinator.attest()` required the original session UUID and revision, so a participant reconnecting after a Master restart was always rejected as `STALE`, even when its current inventory before-image was byte-for-byte equivalent. The existing restart test had reused the original session IDs and did not cover a real reconnect.

The coordinator now permits a different session UUID only while the transaction remains `PREPARING`, only for the same participant, and only when the participant's SHA-256 before-image digest matches. It journals the refreshed session/revision before accepting that attestation. It preserves the request digest, both participant IDs, both before-image digests, and both after-images. A mismatched digest remains stale and leaves the previous durable prepare and both reservations intact. The regression also reconstructs the coordinator after the first participant refresh and verifies that the refreshed binding remains durable while volatile attestations are discarded.

This is transaction-state recovery groundwork only. The coordinator is still not instantiated by Master or connected to owner tick-thread reservations, player inventory mutation/save fences, InvSee++ click/cursor handling, apply/replay, or reconnect-before-play gating. RC5-02 remains P0 `BLOCKED`, and the remote click gate remains closed.

## Red/green regression

On the prior implementation in a disposable checkout, the new restart/reconnect regression failed as expected: `expected WAITING_FOR_OTHER_PARTICIPANT but was STALE`.

After the change, the same focused regression passed. The complete Master module suite passed twice in separate detached clean worktrees at base `53e50d4ced6a755a0309627c5a83f630049321ab`, each with the exact same three-file source/test overlay, Temurin Java 25.0.3.9, Gradle 9.8, offline dependencies, `--no-build-cache`, and one worker:

| Pass | Master tests | Failures / errors / skipped | Master JAR SHA-256 |
|---|---:|---:|---|
| 1 | 131 | 0 / 0 / 0 | `B9A2ED161B33A0756C48519D4D283AAF8656A054F3E5B23EEF8003D6B103D1EB` |
| 2 | 131 | 0 / 0 / 0 | `B9A2ED161B33A0756C48519D4D283AAF8656A054F3E5B23EEF8003D6B103D1EB` |

The identical JAR hashes establish reproducibility for the Master module output only. They do not qualify a matching Paperclip/backend binary pair or a release.

## Source and evidence hashes

- `PlayerInventoryTransactionCoordinator.java`: `733E4BF41733FDBD22A6B52804C01235FD60196D0A74D810D0227D3FA75E1504`
- `PlayerInventoryTransactionJournal.java`: `36C3B0B1A556F9AB21F04376310F40FEC285A231A4D91AA8352AD379D208D37F`
- `PlayerInventoryTransactionJournalTest.java`: `73112826E6ED3AF034F273CF8A1F7910274BE11D924AAE6E3DD54BDA844A513A`
- Pass 1 log: `01AD33E29E90B88C2C48FFE1CE47817B55BEBCACBDE4BF8DAD57F8D9E6E67A89`
- Pass 2 log: `C9E4319CA63C8CB2341F47C907288C55A968194A5D990BEAB40DF61DD07AD6CE`
- Expected red baseline log: `26AE375BC46AB1B955A281CD4CEBCE40920A4F9ADC88699F3A9D23C52854C3FB`
- Focused green log: `2F681F832B886E1540A9E6F034CEA6739C992185E2A4F03FAE74AFC66F1F38F6`
- Pass 2 diagnostic thread dump during slow Shadow remapping: `3864C01DF2FE51B211EC810F33F8FB1BA7F609CE42B31A51C432374BD310E6EA`.
- Second pass 2 dump during test execution: `D:\multipaper-tests\evidence\rc5-invsee-reconnect-master-pass2-thread-dump-2-20261031.txt`, SHA-256 `877A26FF50B7C0A7C0BE748D42793B648E67099579BC2DBFCC818D6DB2C96C51`.
- Pass 2 test-worker dump: `D:\multipaper-tests\evidence\rc5-invsee-reconnect-master-pass2-test-worker-thread-dump-20261031.txt`, SHA-256 `0E7E393B26AAABBB7D49B82D156CA0C6ED040F5A7CAF4ACF565141C56F00598C`.

Build/test logs and the pass 1 JUnit XML remain under `D:\multipaper-tests\evidence\` and the two detached worktrees under `D:\multipaper-tests\`. No Crafty server, live world, or player data was accessed.
