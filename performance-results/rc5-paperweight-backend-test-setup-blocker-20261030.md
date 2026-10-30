# Exact-source Paper/Moonrise backend test setup blocker — 2026-10-30

## Scope and isolation

All work ran in detached clean worktree `D:\multipaper-tests\worktrees\rc5-paperweight-current-c185` at `c185e87f4a3d49fa6730cdbee9e9a82338b493f6`. Main HEAD later advanced to `e6a6d7314bd1c6b6f8b9c7149a0cad4037ec6b72`; the product-source diff between those commits is empty. Java was explicitly Temurin 25.0.3+9 and Gradle wrapper 9.8.0 for the substantive attempts. No live service, Crafty host, world, or player data was accessed.

## Direct test attempts

- A direct offline `:multipaper-server:test --no-daemon --offline --no-build-cache --max-workers=1 --console plain` started the generated Purpur checkout and messaging-protocol compile, then failed at server `compileJava` with 100 missing Paper/Minecraft/Bukkit, Brigadier, Configurate and related symbols. It executed **0 JUnit tests**, exit 1. Log SHA-256: `3E8F6D0D3FDDF1D545D3C6BABD1DB6419AC5521A69466F186F1BA31C882AD1A9`.
- An online retry with Java 25 produced the same missing-classpath failure in 1m18s, 0 tests. The direct task did not generate the ignored `multipaper-server/build.gradle.kts` from its tracked `.patch`; the clean subproject was therefore compiled without its Paperweight fork build/dependency wiring. Thus “missing dependencies” is a symptom of incomplete patch setup in this direct invocation, not evidence of a product-source regression.
- One launcher-only retry initially selected Java 17 and failed during Paperweight configuration (“requires at least JVM 21”); it ran no task and no tests. The Java 25 environment was then set explicitly.

## Correct patch setup attempt

`gradlew tasks --all` confirmed Paperweight's `applyAllPatches` task. A single Java 25 invocation reached `:checkoutPurpurRepo`, `:applyUpstream`, `:purpur:checkoutPaperRepo`, `:purpur:applyPaperSingleFilePatches`, then `:purpur:filterPaperApiFromPaper`. Its included-build worker was in `FilterRepo.run` → `FileKt.copyRecursivelyTo` → `Files.copy`, copying the large Paper API into the disposable Purpur generated repository. The checkout and patch application made progress, but after the recursive copy stopped increasing the generated API file count for several minutes, the disposable Gradle invocation was interrupted. It did not reach root `applyAllPatches` completion, generate `multipaper-server/build.gradle.kts`, or run JUnit. This is a **setup-stalled** result, not a source test result.

Thread dump SHA-256: `61B1C6C3CB4A16A218472D5A144A4C71EC738A6B076EE1F2AE0AB1476F1AF4E8`, retained at `D:\multipaper-tests\worktrees\rc5-paperweight-current-c185\rc5-paperweight-applyallpatches-filterrepo-stall-20261030.txt`. The interrupted Gradle session exited; checks found no remaining process from that invocation and no listener on its local daemon ports 53111/53112. The isolated worktree remains detached; generated upstream/cache files are confined to that fixture.

## Chest-integrity relation

This attempt adds no chest-runtime result. Existing isolated Java 25 evidence for 273 actual no-holder reads, concurrent readers, A→B/B→A ownership schedules, delayed stale-owner writes, exact single/double chest contents, and selected backend/Master restarts remains in `rc5-crafty1-moonrise-fallback-log-analysis-20261028.md`, `rc5-crafty1-273-noholder-read-stress-20261028.md`, and `rc5-current-source-chest-273-bidirectional-20261029.md`. Those synthetic schedules show the tested no-holder path reading Master-backed region state and conserving the seeded NBT, but do not establish the cause or recovery of reported live empty chests. RC5-01 remains P0 `TESTING`; do not mark VERIFIED or restore live data from this evidence.
