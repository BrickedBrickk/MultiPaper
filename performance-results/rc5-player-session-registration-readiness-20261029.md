# RC5 Master player-session registration readiness prerequisite — 2026-10-29

## Scope

This change makes the Master acknowledgement for `RegisterPlayerSessionMessage` observable to server code and fences readiness to the exact player session and Master connection generation. It is a prerequisite for a future owner-attested inventory transaction protocol, **not** a functional InvSee++ fix. Remote cross-backend InvSee++ edits remain fail-closed; no player-data transaction is sent by this change.

The implementation deduplicates registrations for the same pending player/session, completes readiness only for a successful Boolean reply, denies stale replies after session replacement or connection loss, and retires only a matching session on player disconnect. No remote click path consumes this readiness yet.

## Regression and build evidence

- Focused command: `gradlew.bat :multipaper-server:test --tests puregero.multipaper.MultiPaperInventoryHandlerTestSuite --no-daemon --max-workers=1 --console=plain --offline --no-build-cache`.
- Result: **44 tests, 0 failures, 0 errors, 0 skipped** across 12 suites. The new `PlayerSessionRegistrationStateTest` is **6/6** (JUnit XML SHA-256 `F2E06B5D61C3366721288C2B2199E8F9715B5868C4F317EAA59D835BD70B40DE`).
- Two sequential clean full builds used Temurin 25.0.3+9, Gradle 9.8.0, offline, no build cache, no daemon, one worker, and `clean build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar`. Both passed: **10,794 tests, 0 failures, 0 errors, 88 skipped**, across 194 suites. Build log hashes: pass 1 `3CED1963D37A923815CF8B98C3545218AF3F5ADD39FC78FA924C41E6CD2C0495`; pass 2 `D9CEBB2E834FFB1B6110CE814732ED8058B904DFCEE5F91FEFA25AA97E828687`.
- The isolated build worktree was based on source commit `04724a62e188d5f99c20435a6a47427d09de5d1e` plus the five implementation/test overlay files listed below. Both passes produced identical hashes for all nine JARs, and `jar tf` succeeded for each:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `multipaper-api-26.3-RC5-javadoc.jar` | 22,291,722 | `BC8CF72E68CDF59E5D21CAB3F91A3B81A31DC237F02BEDF7E6CE000714E45B60` |
| `multipaper-api-26.3-RC5-sources.jar` | 2,130,533 | `42CCC7F84218F45656BB42879FB0B9BAF6051D991B4C910BAE627C5AC88FCC9B` |
| `multipaper-api-26.3-RC5.jar` | 2,972,398 | `57A422D7A3EB95DD89551BF0EBB2B6FDDD56CB35081E9D5C43D7EA19CEF1DC57` |
| `multipaper-master-3.0.0.jar` | 19,208,411 | `E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E` |
| `multipaper-mastermessagingprotocol-3.0.0-26.3.jar` | 116,550 | `A5F55D3DADCAB6997EAE6C4FA71EDC10357352D6DFBADD9FB36F3171D9645B22` |
| `multipaper-bundler-26.3-RC5.jar` | 107,661,052 | `3A2D74C29CEF8CB0A952964D4F9DCE01A44531DF0E02A6CC94CC39D3478888C6` |
| `multipaper-paperclip-26.3-RC5.jar` | 64,351,418 | `4180250D05A570009F30349A0251A4924D11D403ED7E4C65CF5686C18B204E7F` |
| `multipaper-server-26.3-RC5.jar` | 29,317,522 | `E4F680FD72F941443EC5EF701FA7A778EE134F438A846E4A575943F284EACA36` |
| `paper-checkstyle-26.3-RC5.jar` | 22,343 | `ECDCFE07327155CEB62FA8F5FBA903F4BB1D6DE5BF94F57B0695EDBC773C666B` |

## Changed implementation/test files

- `multipaper-server/src/main/java/puregero/multipaper/MultiPaperConnection.java`
- `multipaper-server/src/main/java/puregero/multipaper/PlayerSessionRegistrationState.java`
- `multipaper-server/src/main/java/puregero/multipaper/MultiPaper.java`
- `multipaper-server/src/test/java/puregero/multipaper/PlayerSessionRegistrationStateTest.java`
- `multipaper-server/src/test/java/puregero/multipaper/MultiPaperInventoryHandlerTestSuite.java`

## Limits and gate

This proves compilation and configured tests for the above source. It does not verify actual acknowledgement timing on a paired running backend/Master, ownership transfer, InvSee++ click parity, cursor escrow, crash-safe atomic item conservation, or any chest recovery. No live service, player data, world, or Crafty host was accessed. RC5-02 remains P0 **BLOCKED** and release remains blocked. The full build logs and JUnit XML are retained in `C:\Users\elibo\AppData\Local\Temp\rc5-full-current-04724-20261029`.
