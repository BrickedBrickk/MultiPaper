# RC5 InvSee transaction before-image fence — 2026-10-28

## Change

Added a required 32-byte SHA-256 before-image to each participant in the capability-gated `CommitPlayerDataTransactionMessage`. Bumped the transaction capability marker from the previous after-image-only value to a distinct version-2 marker, so a peer that only acknowledged the old layout cannot be treated as compatible with this wire change. Master serializes the participant UUIDs through `PlayerDataOperationQueue`, then `PlayerDataTransactionManager.commitIfCurrent` takes the playerdata world lock, recovers any pending journal, checks the committed transaction ID for exact idempotent replay, and compares every current Master playerdata file against the requested before-image hash before writing the durable batch journal. Any stale participant rejects the batch before either after-image is written. The previous owner-name/same-sender authorization rule remains in place; cross-backend transactions are still rejected pending remote owner attestation.

This hardens the not-yet-integrated transaction endpoint. No Paper/InvSee click caller was added, and no remote InvSee edit was enabled. It does not fix the reproduced 16→32 item duplication.

## Regression verification

Explicit runtime: Temurin Java `25.0.3+9`, Gradle `9.8.0`; all disposable local Master integration tests.

Command:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :MultiPaper-MasterMessagingProtocol:test :MultiPaper-Master:test --tests puregero.multipaper.server.MasterIntegrationTest --no-build-cache --no-daemon --rerun-tasks --exclude-task :MultiPaper-Master:shadowJar
```

Result: **46 tests passed, 0 failures/errors/skips**: protocol module 8/8 (5 round-trip + 3 path tests), `MasterIntegrationTest` 38/38. The protocol round-trip includes the 32-byte before-image field and asserts the new version-2 capability ID. The Master integration regression seeds two durable images, proves a batch with one stale hash is rejected and leaves both files unchanged, proves current hashes commit both after-images, retains duplicate-ID replay after a later normal save, and proves a previous-version capability acknowledgement does not unlock the new transaction format.

JUnit XML SHA-256:

- `ProtocolRoundtripTest.xml`: `F48040058DFC2F1B43D783EAFAE8069871C4BC924A9EEA13395A705608F0C7B1`
- `SharedDataPathTest.xml`: `F3D3E41E97FF53087B7C82AD44C31FBE007E488E9F17F240323A0E3627F187EE`
- `MasterIntegrationTest.xml`: `D5C9CE2EDFC9AB2E4C44F21B428B9A30091AEBD4B401E1D36F1327F022B0A73A`

## Excluded packaging attempt and limits

An initial wider `:MultiPaper-Master:test` attempt completed 73 tests but reported 5 failures in `MasterProcessRestartPersistenceTest`: its child Master process exited before listening because the packaged JAR had not completed. The preceding `shadowJar` task stalled in Shadow's `remapClass`/`FileInputStream.readBytes` traversal. A Java 25 thread dump was captured at `performance-results/rc5-master-beforeimage-thread-dump-20261028.txt`, SHA-256 `A8E54138B35FAA8F1EB3B18280517BCA931B267C8757E6AC9CB7BE29D4BD6E90`; only the disposable Gradle process was interrupted. Those five results are not source assertion failures, but the broader module run is not green and no Master JAR build/hash is claimed. The focused passing rerun excluded `shadowJar` and selected only the in-process integration class.

The source/tests have not had two clean reproducible packaged builds, the Paper/InvSee sender still does not call this protocol, there is no target-owner prepare/attestation or live cursor reconciliation, and no accepted cross-owner conservation test exists. RC5-02 remains P0 `BLOCKED`; no release artifact is cleared. No live SMP or world was accessed.

## Source hashes

- Protocol message: `E594259CE298451D714A27121CCBFA781AC03A2047CAF983C3EBBE8A4CA89632`
- Capability version marker: `A1C556AEE8BAE3FAAEEA894150AF96752EC7D3C1129637342FD7D84EC0D9554A`
- Transaction manager: `A0C99FFFB097A5833208CC628244E7737E6D778EEDFAE387D75B830DA85A72B9`
- Master handler: `A5847C10478D77B7A0E22E074B604F335CA19474B36F998EAECF1FFCF63B0395`
- Master integration test: `55FF4B51D7766C3CF793505ADBA41536D13755F1C82587B19FB0C7A045BDDA76`
- Protocol round-trip test: `170455F176CAC9BD3D14DD772D1A01C7C610133717F52A82835E364A93837735`
