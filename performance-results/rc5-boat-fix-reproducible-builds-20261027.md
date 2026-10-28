# RC5 Boat dependency fix: reproducible build evidence

Date: 2026-10-27
Source branch: `feature/port-26.3`
Source HEAD before uncommitted fix: `2265a5a3bf54d1a482c5ac21bd856a9a2674ee32`
Fixture: `C:\Users\elibo\AppData\Local\Temp\multipaper-rc5-boatlog-20261027` (isolated worktree; no live server/world used)
Runtime: Temurin Java 25.0.3.9, Gradle 9.8

## Scope and result

The exact Boat-to-Mob `ClassCastException` from the supplied Crafty2 log is guarded in the generated `MultiPaperEntitiesHandler` and canonical `patches/server/0027-Sync-entities.patch`. The focused `MultiPaperEntitiesHandlerDependencyTestSuite` passed 3 tests: non-Mob/boat entity-link handling, boat passenger dependency, and Mob leash set/clear. Result: 3 passed, 0 failures, 0 errors, 0 skipped.

After `applyAllPatches` succeeded, two sequential clean builds ran with:

```text
gradlew.bat clean build createPaperclipJar createBundlerJar :multipaper-master:shadowJar --no-build-cache --no-daemon --max-workers=2 --console=plain
```

Both full builds succeeded. Each completed 10,749 tests with 0 failures, 0 errors, and 88 skipped (189 XML test result files). Pass one took 7m33s and pass two 7m28s. The focused suite is included in those totals. Pass-one artifact copies are retained under `artifacts-pass1`; pass-two outputs remain in the fixture's build directories.

## SHA-256 (both passes)

| Artifact | SHA-256 |
|---|---|
| Bundler `multipaper-bundler-26.3-RC4.jar` | `1A14BD6831B28F620D2F9521011D5E474C3FAA15FBD950E937D5508513AE000F` |
| Paperclip `multipaper-paperclip-26.3-RC4.jar` | `D8C015EFE458CADA31113C606AB2541AF809A932ADBA514D267B55DD66B6393E` |
| Server `multipaper-server-26.3-RC4.jar` | `2D1B49200C9E7D82E331615A1823F604AF09A83C7651D8AF86510A985F396CA5` |
| Master `multipaper-master-3.0.0.jar` | `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7` |
| Master messaging `multipaper-mastermessagingprotocol-3.0.0-26.3.jar` | `0C1F9690E33C02865AF0ADB4E396D277CD3A41F0AEC2FD5B2FB7692F1F59B97A` |

The product filenames retain their existing RC4 version strings; these are local development outputs, not an RC5 package and not release-ready binaries. The build proves compilation and test success, not runtime cross-backend boat behavior. The boat issue remains `TESTING` until an isolated runtime scenario checks a boat and passengers across backend ownership/replication and confirms no cast exception or duplicate entity.

No build/test service was left running. No live Crafty/SMP service or data was accessed.
