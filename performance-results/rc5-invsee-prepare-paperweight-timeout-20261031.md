# RC5 durable-prepare exact-source build setup timeout — 2026-10-31

## Scope and result

Validated the uncommitted durable-prepare journal source overlay in detached worktree `D:\multipaper-tests\rc5-invsee-prepare-pass1-20261031`, based on `d13733c8a2e8e801370facb862b6ba8e8049a7fa`. The overlay was hash-matched against the main checkout before the run. Java 25.0.3.9 / Gradle 9.8 was invoked serially with `applyAllPatches`, no daemon, no configuration/build cache, and one worker.

The run remained inside `:multipaper-server:setupMacheResources`, after `Copy initial sources...`, at `Setup git repo...`. A valid Java thread dump at 2:49:52 PM local time shows the included-build thread in JGit `AddCommand.call` / `ObjectDirectoryInserter` / `LooseObjects.tryMove` / Windows `File.setReadOnly0`, called by `SetupMinecraftSources.run`. The job was allowed approximately 60 minutes, then cancelled using Ctrl+C through its original session. Its launcher and included-build JVM exited, but a child Git `add` process remained after cancellation. After verifying its command was the generated-source `git add` in this disposable setup, I stopped that child process. A later read-only `git status` started during inspection also traversed the same slow worktree; those inspection processes were cancelled. The worktree and generated files were retained. A post-cancel capture contains only `26300:` (no process); it is not a thread dump and must not be used as one.

**Result: incomplete setup timeout, not a test/build pass or product failure. Zero JUnit tests ran and no release artifacts were produced.** The full-project test/build pair and artifact comparison remain pending.

## Preserved evidence

- Gradle output: `D:\multipaper-tests\rc5-invsee-prepare-pass1-20261031\rc5-invsee-prepare-pass1.log` (10,429 bytes), SHA-256 `D8C7A1B2F9C87C5CF0507C5EEA22BEF4A5356B3AE2759F6B93B6BC61BF436ECE`.
- Last valid thread dump: `D:\multipaper-tests\rc5-invsee-prepare-pass1-20261031\thread-dump-latest.txt` (108,304 bytes), SHA-256 `E3107E3F92A4AA7E6C869CF307E97F0E81E9C82B2169AF627C6C093D50EBDB48`.
- Invalid post-cancel capture, retained for provenance only: `thread-dump-timeout-60m.txt` contains `26300:` and is not a dump.
- The separate pass2 detached worktree was retained and subsequently used for the successful independent clean Master-module build pair documented in [`rc5-invsee-durable-prepare-clean-master-pair-20261031.md`](rc5-invsee-durable-prepare-clean-master-pair-20261031.md). No Git reset or deletion was performed. The older standalone Gradle daemon PID 20912 was not stopped because its relationship to the timed-out setup could not be established; a thread dump confirmed no active build operation. No test-server listeners were started.

## Next action and limits

Do not repeatedly rerun the same Mache setup without changing the setup strategy. The current Master-only source overlay now has two independent clean Master-module build/test passes with matching JAR hashes, but a fresh clean full Paperweight/backend build is still required before an RC5 artifact pair can be qualified. Reuse a complete prepared Paperweight checkout if one can be verified against the exact source overlay; otherwise use a supported setup path with bounded timeouts. Do not overlap Gradle tasks. Preserve the timed-out partial pass1 state.

The source change remains groundwork only: the transaction coordinator is not instantiated in Master or wired to owner protocol/tick threads, live inventory mutation/save fences, InvSee++ clicks/cursor state, or replay. RC5-02 remains P0 `BLOCKED`; remote edits stay fail-closed. No live SMP/Crafty process, world, player data, or configuration was accessed.
