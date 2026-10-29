# RC5 player-session epoch and protocol validation — 2026-10-29

## Scope

Validated the current working-tree Master and MasterBound protocol session-epoch groundwork using Temurin 25.0.3+9 and Gradle 9.8.0, with an isolated Gradle user home at `D:\multipaper-tests\gradle-home-rc5-session-check-20261029`. No live server, world, Crafty host, or external player data was accessed. No Gradle task overlapped another.

Both test commands used `--rerun-tasks`, recompiling the protocol and Master sources and test sources before execution:

```powershell
.\gradlew.bat :MultiPaper-Master:test --tests 'puregero.multipaper.server.MasterIntegrationTest' --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :MultiPaper-MasterMessagingProtocol:test --tests 'puregero.multipaper.mastermessagingprotocol.messages.ProtocolRoundtripTest' --rerun-tasks --no-daemon --console=plain
```

## Results

- `MasterIntegrationTest`: **40 passed, 0 failed, 0 errors, 0 skipped**. Includes `livePlayerSessionEpochRequiresNegotiatedCurrentOwnerAndIsClearedOnDisconnect`, same-owner transaction checks, rejection of a sender-provided other-owner snapshot, unacknowledged/renegotiated peer capability checks, pending-save chunk ownership ordering, durable chest stale-owner fencing, and player save/read ordering.
- `ProtocolRoundtripTest`: **6 passed, 0 failed, 0 errors, 0 skipped**. Includes the separate session capability marker, both message directions, appended stable MasterBound message IDs, and transaction marker round trips.
- Exact JUnit XML SHA-256: Master integration `69809FC6AB2481A695F86DACA2A964438DA3022E87342356BB1403FF10E02FAB`; protocol `6F93AC101179CACA1485079B80F083AA442A325D7EBC5C9E69FF2B80E0E00AA9`.
- Modified-source hashes at validation: `ServerConnection.java` `570A979E9BF40BCC19C6739E5F9962DBCB66D23465D20BCC53911D8780C71281`; `RegisterPlayerSessionHandler.java` `F5E48D233BCBE99449542194B1770574BA09C5293889901DCDB2D5A3151C2A14`; `CommitPlayerDataTransactionHandler.java` `9CAE8606CDF5F3AA2E2CCE2B68A18C92B181FC12E51BD9933353D733D5DB98F6`; `RegisterPlayerSessionMessage.java` `51A099CB76795315DE6BDF9F424479D1E3B77B8AC1B4FF1F56401099D48D0ADC`.
- Gradle exited successfully for both invocations. Test-created loopback listeners closed; no Java process remained after completion.

## Limits / status

This is Master/protocol-only evidence. It does not compile or run the Paper session-registration sender, exercise `PlayerSyncSession` lifecycle in a real server, implement target-owner prepare/attestation, transact InvSee++ clicks, escrow the editor cursor, update both live inventory mirrors, or prove item conservation under shift-click/drag/reconnect/crash schedules. Cross-backend commits remain fail-closed and remote InvSee edits remain disabled. RC5-02 stays P0 `BLOCKED`; the exact RC4 duplication is not fixed. Paperweight's current-worktree setup remains an independent integration gate. RC5 is not releasable based on these tests.
