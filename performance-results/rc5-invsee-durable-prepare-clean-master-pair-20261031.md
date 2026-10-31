# RC5 durable-prepare independent clean Master builds — 2026-10-31

## Build setup and result

Used two independent retained clean worktrees at exact base `d13733c8a2e8e801370facb862b6ba8e8049a7fa`: `E:\multipaper-tests\rc5-d137-pass1` and `E:\multipaper-tests\rc5-d137-pass2`. Copied only the two transaction source files and their two focused test files from the current worktree into each, then verified their hashes matched across both overlays and the source checkout. No other source or patches were changed in these fixtures.

Sequentially, each worktree ran the same Java 25.0.3.9 / Gradle 9.8 offline command with one worker and no daemon/configuration/build cache:

```text
 .\gradlew.bat :multipaper-master:clean :multipaper-master:test :multipaper-master:shadowJar --offline --no-daemon --no-configuration-cache --no-build-cache --max-workers=1
```

Both clean Master-module builds passed **131/131 tests**, 0 failures/errors/skips across 14 suites. Coordinator tests passed 9/9 and journal tests 13/13 in both. Each `BUILD SUCCESSFUL`; pass1 completed in 3m21s and pass2 in 3m17s. The produced `multipaper-master-3.0.0.jar` hashes are byte-identical:

`4ABC8E283F0FF60B0F14A934A263A2989EAF564CE06DB4FB0B569955CC126B24`

## Evidence

Pass1 evidence directory: `D:\multipaper-tests\evidence\rc5-invsee-prepare-master-pass1-20261031`.

- Gradle log SHA-256 `BE4CA76E650A02F59664C38F9FAE5C42ECF1D3DA1D5FF7BAE7984D7ED3064B6D`.
- JAR SHA-256 `4ABC8E283F0FF60B0F14A934A263A2989EAF564CE06DB4FB0B569955CC126B24`.
- JUnit XML manifest SHA-256 `E4996B871091C9B2B31426132E391DA552A359AA1AE4BBA3456CA841EBB22AC0`.

Pass2 evidence directory: `D:\multipaper-tests\evidence\rc5-invsee-prepare-master-pass2-20261031`.

- Gradle log SHA-256 `50B195C90119B86B8F94B5BB62E4045F7CB4B13E31B91E9D56B97089F071D666`.
- JAR SHA-256 `4ABC8E283F0FF60B0F14A934A263A2989EAF564CE06DB4FB0B569955CC126B24`.
- JUnit XML manifest SHA-256 `FB1C04DC6B1FA382A4E769F17D01FF542B5437274A8CB349CD55E3822DC70E56`.

Source hashes in both worktrees: coordinator `9B4B575E34E4A9B25AE2C8EEBA7F6DDBAF47B62F76785EE21FCD1B2187A40146`; journal `A17AE3D44334D6863EB136ADD38034F2BAB13FBB8B8EB09ECC557531A150A837`; coordinator tests `20BDB0DD5E6360E3FA98F81B1F80EA68E64AC6126D4F79B0FE558030B2B0DFDA`; journal tests `6F9A95BC7492C31E157F6FEA6BACBF10FAD64CA8DA1A5420C0D038C5431066D8`.

## Verification boundary

This is two reproducible **clean Master-module** builds and full Master-module test runs for the current Master-only overlay. It does not complete a fresh clean Paperweight backend build or create a newly version-matched Master/Paperclip package. It does not wire the coordinator into Master handlers, implement backend owner protocol/tick-thread inventory reservations or save fences, integrate InvSee++ clicks/cursor, or prove cross-owner item conservation. RC5-02 remains P0 `BLOCKED`; remote edits stay fail-closed. No live SMP/Crafty process or data was accessed.
