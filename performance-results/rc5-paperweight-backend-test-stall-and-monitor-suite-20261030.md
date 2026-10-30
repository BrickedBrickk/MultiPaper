# RC5 backend validation attempt and monitor unit suite — 2026-10-30

## Backend test attempt

- **Fixture:** `D:\multipaper-tests\worktrees\rc5-full-current-724-rebuild`, detached exact-HEAD fixture based on `724f6cdfa865514df55bd3de89d60cb006bc5d49`. The main checkout's unrelated dirty worktree was not staged or copied into it. Temurin `25.0.3+9-LTS`; Gradle wrapper `9.8.0`; offline, no build cache, one Gradle worker.
- An initial task selector `:paper-server:test` failed immediately because this build has no `paper-server` Gradle project. The actual backend module is `:multipaper-server`.
- Correct command: `:multipaper-server:test --no-daemon --offline --no-build-cache --max-workers=1 --console plain`. It advanced through `:checkoutPurpurRepo` and printed `:multipaper-api:compileJava`, then made no visible console progress for more than five minutes. The Gradle daemon accumulated minimal CPU. A `jcmd Thread.print` snapshot records an included-build worker sleeping in Paperweight `Command.run` / `CheckoutRepo.run`; this is evidence of a stalled build path, not a confirmed underlying cause. No JUnit test results or backend test count were produced.
- Canceled only the disposable Gradle run through its owning tool session after collecting the thread dump. Verified its specific wrapper/daemon processes were gone afterward; no game server or test fixture process was started. Log SHA-256 `2233D1A5E6345B0C1FCA16541C718D7E726929D923E4B88AE11624CEAE64151F` at `D:\multipaper-tests\rc5-multipaper-server-test-20261030.log`; thread dump SHA-256 `9EB4F2B828C55C3572AEE782A9A5BC557972D9BC39E74D73B1204041A2EAFF9E` at `D:\multipaper-tests\rc5-multipaper-server-test-thread-dump-20261030.txt`.
- **Disposition:** The backend/Paper-Moonrise suite remains unverified. The full Master module suite's 99/99 result is independent and cannot substitute for this. This attempt did not change production source or generate a qualifying candidate binary.

## Beta monitor unit tests

- Python 3.11.9: `python -m unittest discover -s scripts/tests -p 'test_beta_monitor.py' -v` passed **18/18** (0 failures/errors) in 0.307 seconds. Log SHA-256 `62201E46501B238F49B8EA5FF07B28D67BA6B927B22E3AD41D1A4B3A72EA321C` at `D:\multipaper-tests\rc5-monitor-tests-20261030.log`.
- This verifies the synthetic monitor unit cases, including bounded rotation/retention, sanitized export/merge, categorized integrity warnings, CPU baselines, TPS/MSPT parsing, and safe Ctrl+C flush. It does not measure Crafty-host overhead, validate credentials/RCON against a live server, or prove the two-PC monitor setup works in production.

## Safety and release gate

No live Crafty/SMP server, world, player data, or production service was accessed or changed. Existing unrelated SSH tunnels visible in the host process list were not started or touched. RC5-01 chest attribution/recovery and RC5-02 InvSee++ conservation remain P0 release blockers. The backend suite, paired clean builds, package qualification, and pre-release publication remain outstanding.

## Follow-up clean-worktree setup attempt — 2026-10-30

- **Correction / resolution:** The parent `git worktree add` command appeared stalled after approximately 90 seconds, but its child `git reset --hard --no-recurse-submodules` continued populating the checkout and eventually exited. The destination was not abandoned or partial. After verifying the worktree at `c185e87f4a3d49fa6730cdbee9e9a82338b493f6` was clean, it was unlocked and used for direct backend-test invocations. Those direct invocations did not generate the ignored fork build scripts/classpath and failed before JUnit with missing Paper/Minecraft dependencies. The correct `applyAllPatches` setup then stalled during Paper API filtering's recursive copy and was interrupted after preserving diagnostics. This is a validation setup blocker, not a source regression or passing test. Details and limits are in `performance-results/rc5-paperweight-backend-test-setup-blocker-20261030.md`.
- Do not repeat the earlier instruction to avoid this worktree; it is now a verified clean detached fixture. Other pre-existing locked worktrees and Git metadata remain untouched. No live or production process/data was accessed.
