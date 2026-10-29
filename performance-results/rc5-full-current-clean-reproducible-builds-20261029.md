# RC5 exact-source full clean reproducible builds — 2026-10-29

## Source and method

- Isolated, clean Git worktree: `C:\Users\elibo\AppData\Local\Temp\rc5-full-current-04724-20261029`, detached HEAD `04724a62e188d5f99c20435a6a47427d09de5d1e`. The main working checkout's unrelated edits were not included or altered.
- Java: Temurin 25.0.3+9. Gradle: 9.8.0. Offline, `--no-build-cache`, `--no-daemon`, `--max-workers=1`; builds were sequential and each began with `clean`.
- Command each pass: `gradlew.bat clean build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar --offline --no-build-cache --no-daemon --max-workers=1 --console=plain`.
- Paperweight `applyAllPatches` passed in this C: NVMe worktree. Log SHA-256 `22AAF3FE9D289E174C7B6748467AB23DDB6F29A1295E69FA4052D36744990EFB`.

## Full test results

Both clean builds completed successfully: pass 1 in 10m17s (97 tasks; log SHA-256 `27D95251B2B9F5BC8B03D149C7DF934CC4FFF9BBD0F92D6A44BADC1AFA442E91`), pass 2 in 9m42s (97 tasks; log SHA-256 `9F0B1903BC339516B0202E5B45B162B38052FF0AD787BA4F1BF2530226093CEB`). The full project suite produced **10,788 tests, 0 failures, 0 errors, 88 skipped** across 193 JUnit XML reports:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| API | 481 | 0 | 0 | 2 |
| Master | 78 | 0 | 0 | 0 |
| Master messaging protocol | 9 | 0 | 0 | 0 |
| Server | 10,217 | 0 | 0 | 86 |
| Checkstyle | 3 | 0 | 0 | 0 |
| **Total** | **10,788** | **0** | **0** | **88** |

Every one of the nine `build/libs` JARs was hashed after each run. Pass 1 and pass 2 manifests are byte-identical; manifest SHA-256 `75306BFF57A45671A5645B0E07479F177D2B24F54BD8BB9125B0E743654239C4`. `jar tf` passed for all nine outputs.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `multipaper-api-26.3-RC5.jar` | 2,972,398 | `57A422D7A3EB95DD89551BF0EBB2B6FDDD56CB35081E9D5C43D7EA19CEF1DC57` |
| `multipaper-api-26.3-RC5-sources.jar` | 2,130,533 | `42CCC7F84218F45656BB42879FB0B9BAF6051D991B4C910BAE627C5AC88FCC9B` |
| `multipaper-api-26.3-RC5-javadoc.jar` | 22,291,722 | `BC8CF72E68CDF59E5D21CAB3F91A3B81A31DC237F02BEDF7E6CE000714E45B60` |
| `multipaper-master-3.0.0.jar` | 19,208,411 | `E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E` |
| `multipaper-mastermessagingprotocol-3.0.0-26.3.jar` | 116,550 | `A5F55D3DADCAB6997EAE6C4FA71EDC10357352D6DFBADD9FB36F3171D9645B22` |
| `multipaper-server-26.3-RC5.jar` | 29,313,768 | `86A883FB09FF50542510D6F778E6B77BA080DD2CB518B7739B3CDE051FE91B34` |
| `multipaper-paperclip-26.3-RC5.jar` | 64,347,662 | `A03B3C4AD79E6102E678E6CD3AD9F859A6C32ADEA36225F3545E4E4313988E94` |
| `multipaper-bundler-26.3-RC5.jar` | 107,658,047 | `C5697DD7D8CD73F3232D71B36D1A19F7F2871F3CC76029EC885BD24756241254` |
| `paper-checkstyle-26.3-RC5.jar` | 22,343 | `ECDCFE07327155CEB62FA8F5FBA903F4BB1D6DE5BF94F57B0695EDBC773C666B` |

Raw build logs, JAR manifests, and JUnit XML are preserved in the isolated worktree and `C:\Users\elibo\AppData\Local\Temp` paths above. The disposable tests left no Java processes or listeners on the checked Minecraft/fixture ports.

## Setup attempt and limits

The first exact-source worktree attempt on D: stalled in Paperweight patch setup; it was stopped after a 15.5-minute no-output interval and a thread dump showed Gradle waiting in included-build coordination. Its log SHA-256 is `09A26044590454981425C0CEF6FFF4C4FA75045337A1FF77542A67C61BA53298`; thread-dump SHA-256 is `4E2A6EE2C9A409E4A7C96A338E3A0A0BEBA9A5DC93F3E6FE4B075BA144ACE117`. Only that disposable process was stopped. The same source then completed patch application and both full builds on C: NVMe. Neither attempt accessed live servers/worlds.

This proves Java 25 compilation, configured full-suite tests, and reproducibility for the exact source commit. It does not prove RC5 gameplay, native Java/Bedrock authentication, two-PC monitoring/capacity, or P0 InvSee++ item conservation/live chest attribution. RC5 remains blocked and must not be installed or published.
