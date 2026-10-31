# RC5 durable-prepare full Master module suite — 2026-10-31

## Result

After the exact-source full Paperweight attempt timed out before tests, ran the full Master module test task in the current worktree with Temurin Java 25.0.3.9 and Gradle 9.8, one worker, no configuration cache, and no build cache:

```text
 .\gradlew.bat :multipaper-master:test --no-daemon --no-configuration-cache --no-build-cache --max-workers=1
```

Result: **131/131 passed**, zero failures, errors, or skips, across 14 JUnit suites. The coordinator suite passed 9/9 and journal suite passed 13/13. This includes durable prepare/restart/fresh-attestation, failed prepare persistence, durable abort, v2 record compatibility, randomized journal recovery, chest barriers/restarts, and the rest of the Master module tests. The Java 25 Gradle log says `BUILD SUCCESSFUL in 5m 20s`; the test task ran after Java sources were `UP-TO-DATE`, because the exact modified sources had already been compiled by the preceding focused Java 25 coordinator+journal run.

The initial attempt in this sequence inherited Java 17 and failed during Gradle configuration because Paperweight requires Java 21 or newer; it ran no tests and is not included in the result. The successful rerun explicitly set `JAVA_HOME` to Temurin 25.0.3.9.

## Evidence and limits

- Gradle log SHA-256: `BCB89A6C6502B8B8D09F59815CD17D1FBF0F783C519C51D4CD6E9D39CAA66EA7`.
- JUnit manifest SHA-256: `E9D24864B088D6900589598A0F2E3E3CBF5B3C145E27CBA5AA5CF3B5E367AC6F`.
- Preserved log, all 14 XML files, and manifest: `D:\multipaper-tests\evidence\rc5-invsee-prepare-master-tests-java25-20261031`.
- Coordinator source SHA-256 `9B4B575E34E4A9B25AE2C8EEBA7F6DDBAF47B62F76785EE21FCD1B2187A40146`; journal source `A17AE3D44334D6863EB136ADD38034F2BAB13FBB8B8EB09ECC557531A150A837`.
- Coordinator test SHA-256 `20BDB0DD5E6360E3FA98F81B1F80EA68E64AC6126D4F79B0FE558030B2B0DFDA`; journal test `6F9A95BC7492C31E157F6FEA6BACBF10FAD64CA8DA1A5420C0D038C5431066D8`.

This is one full Master-module test run, not two clean full-project builds. The Paperweight backend suite, clean release builds, artifact reproducibility, Master runtime integration of this coordinator, backend owner protocol, live click/cursor integration, and item-conservation guarantees remain unverified. RC5-02 remains P0 `BLOCKED`, and remote InvSee edits remain fail-closed. No live SMP/Crafty process or data was accessed.
