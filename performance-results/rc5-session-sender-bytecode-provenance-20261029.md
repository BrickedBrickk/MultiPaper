# RC5 Paper session sender compilation provenance — 2026-10-29

## Purpose

Resolve a documentation conflict: earlier Paperweight attempts failed before backend compilation, while a later NVMe Paperweight report recorded successful current backend compilation. This audit checked the retained successful fixture without rerunning Gradle or modifying its files.

## Source and bytecode evidence

Fixture: `C:\Users\elibo\AppData\Local\Temp\rc5-paperweight-nvme-20261028`; it is a registered detached worktree rooted at the project repository, not the live server.

The fixture `SOURCE-SHA256-current.txt` records exact current source hashes:

| Input | SHA-256 |
|---|---|
| `multipaper-server/src/main/java/puregero/multipaper/MultiPaper.java` | `5178E9B21A006F6472279F22C4A2C8D8BDD8397CFDD107E5972C2DA6F63CBFC8` |
| `multipaper-server/src/main/java/puregero/multipaper/MultiPaperConnection.java` | `2E0C57C659C9E25E4CC92005FC155D1A8E138C3DA921ADEEAF9CB8919C27179B` |
| `multipaper-server/src/main/java/puregero/multipaper/MultiPaperInventoryHandler.java` | `4BDBBFB5CB772F3894B4B260B5BF2718516F107B31F716EF32E38B34F08CC815` |
| `multipaper-server/src/main/java/puregero/multipaper/externalserverprotocol/PlayerInventoryUpdatePacket.java` | `F55E2DF0A40D7591E35EDB46928CC99CF24A8168F52F0F3B817DDF624B7A483A` |
| `multipaper-server/src/test/java/puregero/multipaper/MultiPaperInventoryHandlerTest.java` | `41A249F70C2B26A5D7A80AB40B20C720F75AE938285DB6BA4DFD78F9DACEC826` |
| `multipaper-server/src/test/java/puregero/multipaper/externalserverprotocol/PlayerInventoryUpdatePacketTest.java` | `00B6FCEB4B1C36F033636F69C294699D349DF1136EDC70C7237CA473A7B193A7` |

Current checkout hashes match the fixture manifest for these files. `PlayerSyncSession.java` is line-for-line equal after newline normalization.

Java `javap` inspection of compiled classes showed the backend session capability flag, the `RegisterPlayerSessionMessage` response branch, a compiled `registerPlayerSession(ServerPlayer)` method that sends the registration message, and a compiled invocation from `MultiPaper.sendPlayerConnect`. Compiled class hashes:

- `MultiPaper.class`: `197DB11B173D3CA0CC9178C048289D06769FFE62C9E98F7A84A3D64AEFD4191E`
- `MultiPaperConnection.class`: `0E834CE627BB472E82BBC0AA77E98335B2A46BF4BE90ACE0605C514BC9431338`

The successful Java 25 / Gradle 9.8 compilation and focused inventory suite are documented in `performance-results/rc5-paperweight-nvme-and-inventory-test-followup-20261028.md`. Retained JUnit XML for `MultiPaperInventoryHandlerTest` is 9/9 and `PlayerInventoryUpdatePacketTest` is 9/9, both zero failures/errors/skips. No new task was run for this audit.

## Remaining boundary

This corrects only the source compilation status. It does not prove a paired backend negotiates the session capability, receives a registration acknowledgement, waits for registration readiness, or safely participates in an inventory transaction. The current sender completes the player-connect path asynchronously; a future transaction caller must wait until Master confirms the current owner/session. No owner prepare/attestation, click identity, cursor escrow, cross-owner transaction, paired runtime, or exact-RC4 16→32 fix is established. RC5-02 remains P0 `BLOCKED`; remote InvSee edits stay fail-closed. No JAR was rebuilt and no live service or world was accessed.
