# RC5 chunk persistence disconnect barrier regression — 2026-10-29

## Scope and safety

Investigated the preserved Crafty1 warning context and an independent Master persistence ordering window using source review and deterministic unit tests. No live Crafty/SMP process, port, world, or uploaded log was modified. The newly named `1latest.log` / `2latest.log` upload paths were not present in `C:\Users\elibo\Downloads` at this check; the only byte-pinned Crafty1 evidence remains the previously preserved 110,461-byte log SHA-256 `920B665985CB2066D2199F0A6795C438D268FEADE91E8BE1246EAEA00CCA25D1`. Do not claim a fresh recount or correlate individual player actions without the actual uploaded bytes.

## Finding and source change

Master's chunk write lock tracked a pre-announced pending save, but not the lifetime of a region write after it was accepted and dispatched asynchronously to `RegionFileCache`. On backend disconnect, `ChunkLockManager.releaseAll` could remove that barrier and `ChunkSubscriptionManager.unsubscribeAndUnlockAll` could relinquish ownership before the accepted asynchronous persistence future completed. A subsequent no-holder read could therefore pass the barrier while the previous owner's write was still in flight. This is a reproducible ordering hazard; it is not proof that the 273 Crafty1 fallback warnings or any reported empty chest were caused by it.

The source now tracks accepted active region writes independently of pre-announcements. Disconnect drops announcements that never became writes, retains active-write barriers, and defers chunk unsubscribe/ownership release until the accepted region writes drain. Owner-rejected writes cancel only their pending announcement. Entity writes also mark/complete active lock state so the shared lock manager retains prior entity-read semantics. Failure and transient-snapshot paths release the appropriate state.

## Verification

Java 25.0.3+9, command `./gradlew :MultiPaper-Master:test --no-daemon --console plain`: **78 tests, 0 failures, 0 errors, 0 skipped**. The four focused `WriteLockManagerTest` cases pass, including the regression that failed before the fix (`disconnectMustNotReleaseBarrierForAlreadyAcceptedAsyncWrite`), plus overlapping writes, active writes without preannouncement, and unused announcement cleanup. Existing Master integration, entity-write, Master restart, and region persistence tests also passed. `git diff --check` passed (only existing Git CRLF normalization warnings).

This is unit/integration coverage of the Master lock/persistence ordering, not a new Paper chest runtime test or byte-for-byte dual-backend restart run on the modified Master JAR. No changed JAR has been packaged. Before release/build claims, complete sequential clean reproducible Java 25 builds and chest-content runtime tests against this exact source.

## Crafty1 log interpretation remains unchanged

The retained log's 273 20:09:52–20:09:53 no-holder messages enter the deliberate `ForceReadChunkMessage` path to Master-owned region storage, which waits on the declared chunk lock. They do not show a backend-local Moonrise disk bypass and carry no chest/block-entity context. Selected existing isolated 273-read and bidirectional stale-owner chest fixtures passed, but they do not attribute or recover live empty chests. The 119 movement warnings, eight duplicate UUID registrations, 13 early entity-NBT warnings, ten removal-routing fallbacks, and three stale-inventory replacement rejections remain separate signals with no demonstrated chest-loss correlation.

## Status

The newly reproduced disconnect/write ordering hazard is fixed in the current uncommitted worktree and its Master suite regression passes. RC5-01 remains P0 `TESTING`; real incident attribution/recovery, exact-candidate rebuilt-binary chest runtime, and broader save/crash ordering are still open. RC5-02 remains P0 `BLOCKED`. Never restore live player data automatically.

## Clean builds and exact-candidate chest runtime follow-up

- Two sequential clean Java 25.0.3+9 `:MultiPaper-Master:clean :MultiPaper-Master:build --no-build-cache --rerun-tasks` runs each passed the full Master suite: **78 tests, 0 failures/errors/skips**. Resulting `multipaper-master-3.0.0.jar` SHA-256 matched across both runs: `E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E`. Build log hashes: pass 1 `A5ADDB49FCF24FE998150BB71EEF03D23F8B21AB270D3209F032F5DC06165478`; pass 2 `6F55D9DDE3CB0F8820BED994585911C57ADD1B84DC3A562FD15FB833DC7E6926`.
- Ran `Test-RC5CurrentCandidateChestRace.ps1` on exact Paperclip `8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692` plus the rebuilt Master above. A→B and B→A both completed 273 production no-holder reads during a delayed stale former-owner write; Master rejected the stale snapshot in both directions. Single chest diamond x17 and double chest emerald x17 / gold x23 persisted after new-owner and graceful Master restarts. A→B result SHA-256 `EDEA8029E80F7F13A659A41D77B1E77A0D97642FFC0F8609E0ACE958F6CB67EF`, RCON transcript `A422F1420AAC55AAFCCAB923A574FA6D18BF3DE6A250E7ABAF77C6410E3CE3B2`; B→A result `F8BAD068063470450061D74721C9784B68752B4C50A89E3DFB427B0BBBA0A13B`, transcript `2E5B34DB734A830B2EB6E9BA7B058484AECDD07DFAF8D51B467690E3BD56A7CE`. Fixture roots: `D:\multipaper-tests\system-temp\rc5-active-write-fence-chest-273-a2b-20261029` and `...-b2a-20261029`.
- Both fixtures report `Master force-stopped: False`; all fixture Java processes exited and test listener ports 33651–33654 / 33661–33662 were confirmed closed. PowerShell harness parser and `git diff --check` passed.
- These exact-pair runtime tests verify chest conservation across the tested handoff/read/restart schedules. They do not deliberately disconnect a backend while a real Paper `RegionFileCache` write is mid-flight; that precise race is covered deterministically by the red/green lock regression plus the full Master suite. The runs also do not identify or recover any live empty chest. RC5-01 remains P0 `TESTING`.
