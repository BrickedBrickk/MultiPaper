# RC5 generated entity-query decompiler regression — 2026-10-30

## Failure reproduced

Two clean full-source builds from the same effective source produced different `LevelEntityGetterAdapter.class` bytecode. `javap` showed one build invoking `EntityLookup.getEntities(type, consumer)` and the other returning immediately. Direct source inspection of the second build's generated `multipaper-server/src/minecraft/java/net/minecraft/world/level/entity/LevelEntityGetterAdapter.java` found Vineflower's `$VF: Couldn't be decompiled` placeholder and a `ConcurrentModificationException` in `ArrayListSpliterator.tryAdvance` during generic type inference. That placeholder compiles into the silent no-op.

The good generated source SHA-256 was `B1ED211CB179ADA2E4657848FA9BDE90ABA9FF2F801B7CC436416804E22700E7`; the broken source SHA-256 was `715E645A4EEBF9C03E1FAB84C325BBFD87F081E6848BC6EE8C67099322360044`. The bytecode difference was not a timestamp or archive-order change: it disabled visible-entity type queries.

## Regression and source correction

Added `LevelEntityGetterAdapterTest.entityTypeQueryReturnsVisibleEntities`, which inserts a test `EntityAccess` into `EntityLookup`, queries via `LevelEntityGetterAdapter.get(EntityTypeTest, consumer)`, and asserts the entity is returned. The test was registered in `LevelEntityGetterAdapterTestSuite` to match this project's suite-only JUnit discovery configuration.

On the preserved F: fixture with the broken decompiler output and no normalizer, the focused suite failed 1/1 as expected: expected the inserted entity but received an empty list. The raw failure XML is preserved at `D:\multipaper-tests\evidence\rc5-level-entity-getter-repro-20261030\before-fix-failing-test.xml` (SHA-256 `A8E0D1C8C8FF674ADA6FFE41100C2A21704690D2983E21541B77AC35FFBED9D3`); the exact generated no-op source is alongside it (SHA-256 `715E645A4EEBF9C03E1FAB84C325BBFD87F081E6848BC6EE8C67099322360044`).

Added an idempotent Gradle source-normalization task in `multipaper-server/build.gradle.kts.patch`. It runs after `applyMinecraftPatches` and before Java compilation, validates the expected field and unique method signature, and rewrites the method body to the canonical `visibleEntities.getEntities(type, consumer)` call. Unknown class shape fails the build. It normalizes either generated source form and does not change any other method.

Copied the updated patch and regression into the same isolated F: worktree. `applyAllPatches` succeeded; the normalizer ran before `compileJava`; the focused JUnit suite then passed **1/1**. This is one fixture check, not full-source or artifact verification.

## Current gate

The cause of Vineflower's concurrent modification is not established. The normalizer prevents its failed decompilation from becoming a compiled no-op, and the regression covers that behavior. Pass 1 on an independently prepared fixture, two clean full builds and full suites, and server/Paperclip/bundler hash comparison remain pending. Until those checks pass, do not call backend artifacts reproducible or stage/publish RC5. No live SMP/Crafty environment was touched.

## Final validation — 2026-10-30

The source normalization was verified on both generated-source forms: the previously broken F: form and the previously good E: form. A focused run passed 1/1 in each fixture. Two independent clean Java 25 full builds then completed successfully:

| Fixture | Tests | Skipped | Failures/errors | Duration |
|---|---:|---:|---:|---:|
| E: `rc5-current-tip-pass1-96acd911-20261030` | 10,840 | 88 | 0 | 12m 8s |
| F: `rc5-current-tip-pass2-96acd911-20261030` | 10,840 | 88 | 0 | 12m 1s |

Both runs used `clean`, then `build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar --no-daemon --no-configuration-cache --no-build-cache --max-workers=1 --console=plain --offline`, with Temurin Java 25.0.3.9 and Gradle 9.8. Logs are `D:\multipaper-tests\rc5-level-entity-getter-pass1-clean-full-20261030.log` and `D:\multipaper-tests\rc5-level-entity-getter-pass2-clean-full-20261030.log`.

All four release-relevant JARs are byte-identical across clean fixtures:

| Artifact | SHA-256 |
|---|---|
| `multipaper-master-3.0.0.jar` | `8EB978519B900CEABDD67C278FCD1A0C16D2DF538879DFA509305EEB437E54B3` |
| `multipaper-server-26.3-RC5.jar` | `04D127DAA2912848F5952314DE833AB752F8B2EA83BF925046EFC73CBD02E2D6` |
| `multipaper-paperclip-26.3-RC5.jar` | `20024BC1C50AF5DDB1248AD2C79426A892A219AE69AE7E1770395123171B936A` |
| `multipaper-bundler-26.3-RC5.jar` | `B9110A43E6B21E0F62F323954D28296870C4A69A636863A2204395C6A4114E6C` |

The original clean-build mismatch is resolved for this exact source/build setup, and the focused regression plus full test suites pass. This verifies the entity getter behavior and clean build reproducibility; it does not verify unrelated RC5 issue categories or qualify a release. The underlying Vineflower concurrency failure remains unexplained. These builds were isolated and did not access live SMP/Crafty data or services.
