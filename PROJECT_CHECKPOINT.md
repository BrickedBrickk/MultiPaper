# Current project checkpoint — authoritative status (2026-10-24)

This section supersedes older conflicting status notes below. Preserve the dirty worktree; do not reset, clean, access production/Crafty, or repeat completed scheduled-tick/entity investigations without a fresh regression.

## Current candidate

- Current checkout: `c3ba0e41eedcd7ec96d59a6f8cd2c858aa3214cc`. The worktree contains intentional existing changes; preserve them.
- Root fix: Master transient entity writes now persist the deduplicated current chunk snapshot as authoritative state. This prevents disk-only entities moved to other chunks from being resurrected by merge. Regression: `MasterIntegrationTest.transientEntitySnapshotsReplaceMovedEntitiesAndAcknowledgeFailures` and `WriteChunkHandlerTest` pass.
- Exact hashes: Paperclip `A5631373DD44AD7D523BA5AF88BC99CDAEAAB61F55666D1ABFF78194D83C86A9`; bundler `D5F49095CC3FE0219C4465E0DE92583CAE0BFF9057B02F272CB08259E5E54026`; server `0DA99374B672A2FD0BBFD1F8A5E0584726047B093931F025ECC54FF8BEA8A9CF`; Master `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`.
- Java 25 full build/package passed; 182 suites, 10,736 tests, 0 failures/errors, 88 skipped. Focused Master integration/handler tests passed after the fix. `git diff --check` passed with line-ending notices.
- Root Gradle config normalizes release archive timestamps. Two independent no-build-cache archive generations matched exact SHA-256 hashes; all normalized entry payloads match the runtime-tested pair, and the reproducibility gate is closed.
- Fresh exact-pair 16-client/61-second disposable soak passed: 16/16 connected, zero runtime entity warnings or soak movement rejects; orderly post-exit MCA scan: 61 records, 61 unique, 0 duplicates.
- Fresh exact-pair localhost synthetic capacity run passed: 20/20 offline protocol clients moved for 181 seconds with zero disconnects, runtime movement rejects, entity warnings, or watchdog/tick overruns. Tick samples remained at 20 TPS; observed P99 tick time stayed below 10 ms. Initial-login movement corrections totaled 2,190. This is localhost load evidence, not intended-hardware capacity certification. Transcript and samples: `%TEMP%\multipaper-26.3-final-capacity-5b8ccf25\capacity-4bc27e8966ad4c08b75a974b120fc55b.{out,ticks.txt}`.
- Exact-pair entity recovery passed: 4 ownership transfers, owner hard crash, restart/reload, and non-owner removal, always one UUID.
- Evaluation package: `release-staging/MultiPaper-26.3-RC2-entity-snapshot-fix-20261024/`; ZIP SHA-256 `BB0D897A5EFF99B6E6C0ECEA632EE2BD816712FDB2294AE56295F45191774A2C`. Do not treat it as stable.
- Detailed evidence: `performance-results/current-candidate-entity-snapshot-fix-20261024.txt`.

## Completed work to reuse

Scheduled-tick handoff/replay and entity synchronization/crash recovery were previously validated; the user explicitly instructed not to repeat those investigations without a fresh failure. Vanilla mechanics and live block updates also passed their corrected disposable probes. Resource generation completed in isolated output with zero patches and content matching tracked resources; the known reset task stays excluded from builds.

## Remaining gates

1. User-side real Minecraft online-mode login and human Survival/farm/redstone/portal play; user said they will test later. Do not ask again.
2. Intended-hardware capacity run on isolated resources. The 20-client localhost run above does not establish the intended-hardware limit; the previous remote attempt shared resources with live services and is invalid. No production or Crafty access.
3. Stable release only after both gates pass; otherwise keep the candidate staged and report the precise blocker.

No Gradle client or MultiPaper server process is active. The localhost capacity fixture was stopped after the run. I also found the older `%TEMP%\multipaper-26.3-scheduled-replay-fixed-20261022-224033` disposable fixture still running with zero players; both backends were sent `stop`, exited, and their world data and logs were retained. The idle fixture Master was then stopped. The worktree remains intentionally dirty; preserve all existing changes.

---

Historical investigation log follows. Prefer the current section above where old entries conflict.
# MultiPaper release checkpoint — 2026-10-23

Preserve the dirty worktree at `c3ba0e4`; do not reset, clean, or replace existing changes. Do not access Crafty or production. Reuse scheduled-tick and entity evidence unless a fresh test shows a regression. Stable release is not published.

## Completed and verified

- Scheduled-tick handoff is closed: `Test-ScheduledTickUnloadReplay.ps1` passed five receiver restart/reload cycles, three delayed-ACK cycles, sender/receiver crashes, Master restart recovery, and exactly-once piston recovery; focused suite 12/12. Evidence: `performance-results/scheduled-tick-final-artifact-validation-20261023.txt` and `performance-results/scheduled-tick-durable-handoff-validation-20261023.txt`.
- Entity synchronization is closed absent regression: ownership transfers, crash recovery, reload/removal, and the eight-client movement soak passed. The expanded Java 25 report recorded 10,731 tests with 0 failures/errors and 88 skipped. Evidence: `performance-results/current-entity-sync-routing-and-final-package-20261023.txt`.
- Automated gameplay on the 6C6C65A8 candidate passed hopper conservation; piston, observer, and sticky-piston mechanics; inventory/crafting; and Nether transfer/removal. This is automated disposable-world evidence, not human play. Evidence: the report above.
- Hardware baseline on A9 Paperclip/E9 Master: eight moving clients ran 181 seconds with 20 TPS, 8.2 ms average tick, and no soak disconnects. A 16-client login ramp reached 11/16 and logged a watchdog stall and entity sync warnings. Eight is the only prior measured limit, on an older candidate. See `performance-results/two-pc-hardware-20261024/REPORT.md`.
- Clean Paperweight bootstrap uncovered and fixed a malformed hunk in `multipaper-server/build.gradle.kts.patch`. The tick-guard patch step now uses `--ignore-space-change` in the tracked build file and persistent patch, so it applies to fresh generated source without touching the original worktree. `applyAllPatches` and clean resource regeneration succeeded in `%TEMP%\multipaper-release-cert-clean-20261023`.
- Two Java 25 cache-disabled clean builds completed all build/test/package tasks. Each had 183 test suites, 10,734 total tests, 0 failures, 0 errors, 88 skipped (including 10,194 server tests). `git diff --check` passed with only existing line-ending notices.
- Repeated builds initially varied because Javac emitted randomized local-variable debug names in one Paper class. The server compile now retains source/line debug data and omits local-variable names (`source,lines`), preserving runtime behavior and stack line numbers. After that change, all ZIP entry contents matched across the two uncached builds; JAR byte hashes still differ because ZIP entry timestamps vary. Exact archive-hash reproducibility remains open.

## Latest clean evaluation package

Directory: `release-staging/MultiPaper-26.3-RC2-clean-evaluation-20261023/`

- Paperclip SHA-256: `CD5A20908A54CBE17C86E2700D4813A037F12D917820200856D3D6287031760A`
- Bundler SHA-256: `9BC118E582DC5BA35D6E0C2FB231108BAE436379ECE19D5D7D48C28DDC7D158A`
- Master SHA-256: `E9ACBAFCC7189E0157643142A065841680BAE7E9BE784394BF0F880E4BB0A0D5`
- ZIP SHA-256: `AA4A349CF6CA44C55B02DCBDF935E301A0035B96740FDE46737709A419D4718D`

The embedded checksum manifest was verified against every package file. This is an evaluation package, not a stable release. Its runtime mechanics evidence is reused from the preceding 6C candidate; the current clean JARs have not had a new two-backend or intended-hardware runtime run.

## Open gates and next actions

1. Real authenticated GUI login and human Survival/farm/redstone/portal play. The user said they will test later. Disposable endpoint `100.78.108.117:32122` is still running the older A9 candidate; keep it available, and do not treat that as verification of the latest package.
2. Run intended-hardware capacity measurement on the latest package. Prior evidence supports 8 only on A9; the 16-client pressure run exposed startup/entity warnings. Do not claim more than 8 without new successful evidence.
3. Resolve the remaining ZIP timestamp variation if byte-identical artifacts are required, then build/package the final candidate and record its checksums. Do not publish stable while critical gameplay/capacity gates remain.

## Fresh high-load diagnostic — 2026-10-24

- Re-read `performance-results/two-pc-hardware-20261024/backend-b-latest.log`. The 16-client `c3ba0e4` run's watchdog stack is in `SendTickListPacket.persistJournal` → `MultiPaper.writeDataAndAwait` → `CompletableFuture.get` on the backend server thread. This confirms a synchronous Master-storage wait can stall a tick during a handoff under login pressure. It is a release performance risk and remains unverified on the newer clean evaluation candidate; do not reopen completed tick correctness tests based on this alone.
- The same older run recorded one duplicate UUID warning while a spider crossed from chunk `[16,10]` to `[16,11]`, plus entity updates received for unloaded chunks. These are diagnostic warnings, not proof of duplicate persisted entities or loss. The current checkout includes the later entity subscription readiness patch (`0073`) and current-owner/stale-update repair. Validate those on a disposable current-candidate runtime before changing entity code.
- The 104 “unsubscribe notification for an unloaded chunk” warnings indicate unsubscribe messages can race chunk teardown. The handler currently logs and discards them; confirm whether this is harmless teardown ordering during a latest-candidate load before treating it as a defect.
- At the time of this diagnostic, the next gate was an isolated current-candidate login ramp. That ramp and chunk-crossing soak completed successfully as recorded below. Master-storage delay remains untested on the current candidate.

## Current-candidate local runtime check — 2026-10-24

- Started a new disposable offline-mode two-backend fixture at `%TEMP%\multipaper-26.3-entity-current-9e4eba8f` using the clean evaluation Paperclip (`CD5A2090…31760A`) and Master (`E9ACBAFC…B0A0D5`) package hashes. Existing live fixture processes and data were not changed.
- Against that earlier candidate, the 16-client stationary 31-second run and 16-client moving 61-second chunk-crossing run passed. This was superseded by the newer peer-start candidate's 16-client test below, which surfaced duplicate UUID registration warnings and reopens entity certification.
- Fixed `Test-ProtocolCapacity.ps1` after its final position assertion incorrectly queried only backend A while player ownership was split. It now checks both backends for each player. Evidence and raw paths: `performance-results/current-candidate-entity-load-20261024.txt` and the fixture directory above.
- There were 311–316 synthetic movement rejects during client initialization/staging before the measured soaks; there were none during the soaks. Console startup also showed three `AddChunkSubscriberMessage for unloaded chunk` warnings for Nether chunks. These signals need interpretation separately and are not proof of real-client login failure or entity loss.
- The old watchdog stack remains unverified on delayed Master writes against the current candidate. To close that performance risk, the next reproducible gate is a disposable storage-delay test that measures backend tick responsiveness while durable tick journals wait for Master confirmation; preserve the exactly-once journal guarantees.

## Process and workspace state

- No Gradle build client is active; resident daemons/workers may remain. Recheck before starting any build.
- The resource regeneration and tests ran in a temporary checkout with the current tracked changes plus relevant untracked source/patch files. The original dirty worktree and existing packages were preserved.
- Do not restart old scheduled-tick/entity investigations unless a new test demonstrates a regression.

## Latest continuation — 2026-10-24

The previous checkpoint's entity-runtime gate is now closed for the current candidate. Root cause: Master `ServerStartedMessage` can arrive before `MinecraftServer.getServer()` exists; the handler discarded it, and the peer direction rule rejects the other backend's reverse attempt. `PeerServerStartTracker` now deduplicates and retains early messages, retries readiness every 100 ms for up to 120 seconds, then dispatches once. Regression is covered by `PeerServerStartTrackerTest`; clean fixture creation is reproducible via `scripts/New-DisposableEntityFixture.ps1`.

- Focused peer-start suite passed. Full Java 25 build/package passed; aggregated XML: 184 suites, 10,736 tests, 0 failures/errors, 88 skipped. `git diff --check` passed with only CRLF notices. The build excluded the known failing resource-reset task; generated resources were already up-to-date.
- Exact disposable offline two-backend runtime `%TEMP%\multipaper-26.3-peer-start-c6a97879` passed peer connection, entity readiness on both backends, exactly-one replication, ownership transfer, owner hard crash/restart/reload, and removal. `Verify-TwoBackendReplication.ps1` then passed hopper conservation/transfer, piston/observer/sticky-piston behavior on both owners, entity lifecycle, Nether transfer/removal, and zero new duplicate-UUID or missing-entity request warnings. Evidence: `performance-results/peer-start-readiness-and-current-candidate-20261024.txt`.
- New evaluation package: `release-staging/MultiPaper-26.3-RC2-peer-start-fix-20261024/`; ZIP: `release-staging/MultiPaper-26.3-RC2-peer-start-fix-20261024.zip`. Paperclip `40436D886F39432AE7272311B35CEFB7B757BCBDDE7F997F36903ADEFE2FB5BF`; bundler `987679EA0174DD3590417657D5BCC3D4BE62E665FBECD969AF9AE0B4CC81CE5B`; Master `E9ACBAFCC7189E0157643142A065841680BAE7E9BE784394BF0F880E4BB0A0D5`; ZIP `54D5ABCA7C3A9070F5AC14C9FFEFCED54E9847F0A616CAE42A3834AD3DF38282`.
- The local 31121–31132 fixture and tunnels were save-stopped/closed. The exact remote 311xx temporary fixture was also stopped, its logs copied, and only its unique `/var/tmp/multipaper-26.3-hw-current-20261024-aeb22353` directories removed on both hosts. The existing remote 321xx services were observed running and left running; no production world or Crafty panel was accessed. The benchmark shared CPU/memory/disk with those live services, so its result is invalid for capacity.

The candidate package documentation and manifest were refreshed after validation. Current ZIP SHA-256 is `8997066CC55DD310AA189A2FF5FD8D0548D33D8986DF4C442BA2AA1888504AF5` (the earlier `54D5...` ZIP is superseded). Exact artifacts remain unchanged. Hardware attempt details/raw logs are in `performance-results/current-candidate-hardware-capacity-attempt-20261024.txt` and `performance-results/two-pc-hardware-20261024/current-candidate-rc2/`.

Remaining release gates: human online-mode GUI login and Survival/farm/redstone/portal play (user will test later); a valid exact-candidate intended-hardware capacity run during a maintenance window or on separate hosts (the failed 2/8 attempt shared hosts with live services); a second clean cache-disabled build to check archive reproducibility. Do not publish stable until these are resolved. Reuse tick/entity work and the current mechanics evidence unless a fresh test shows regression.

## Fresh entity-load regression — 2026-10-24

- The current peer-start RC2 candidate's 16-client moving local test reached 16/16 connections and observed movement from all clients, but failed its integrity gate: 15 `Entity uuid already exists` warnings during initial chunk loading. It is not a passing capacity or entity certification run. Raw outputs: `%TEMP%\multipaper-26.3-local-capacity-rc2-20261024\capacity-d5d964aa3da34235971e26399e88f9d8.{out,err}`; backend logs are under the same fixture's `backend-a` and `backend-b` folders.
- Representative collisions include distinct chunks/positions for one UUID (e.g. a Nautilus in `[12,8]` and attempted in `[13,8]`) and the same UUID, type, position, and chunk for two in-memory candidates (multiple Cods in `[4,8]`). The test surfaced a real duplicate-registration anomaly; do not dismiss it as console noise. Some collisions may involve loaded mirrors, stale entity-chunk records, or overlapping load paths. Root cause is not yet proven.
- Code path inspected: `EntityUpdateNBTPacket.loadEntity` uses UUID lookup/update to converge known entities; entity registration still warns in `EntityLookup.addNewEntity`. MCA/NBT inspection of the captured Master storage found two UUIDs persisted in multiple chunks: squid `727e0433-d453-45aa-a260-fec96c7f8eb8` in `(4,7)` and `(3,8)`, and bat `06574bb3-6ae4-4cc1-8165-3ece44ad98ca` in `(3,11)` and `(4,11)`. The `(0,0)` region had 111 entity records / 109 UUIDs. These records were found during the attempted save/stop sequence, which did not complete cleanly; they prove duplicate records existed in Master storage but do not prove a post-shutdown state. Master `WriteChunkHandler.merge` and Moonrise's transient entity save merge concatenate entity records without UUID deduplication, so the writer path is suspect. The exact writer/event sequence that creates cross-chunk duplicates is not yet proven. UUIDs from warning examples were not found in this saved scan; separate persistent duplicates were confirmed.
- The disposable fixture's data/logs remain intact. No Gradle process was active when checked. No source fix or rebuild has been made for this newly surfaced regression. Next: reproduce on a small deterministic fixture with entity movement across chunk boundaries and concurrent owner/reload paths; identify which source produces each duplicate; add a regression test and fix the persistent patch/source of truth, then run the focused test before rebuilding. Do not start an overlapping build.
- Correction to the stop/save note: the first fixture's diagnostic `save-all flush` never completed. Backend thread dumps show `MoonriseRegionFileIO.flush` waiting while an entity read for `(5,6)` timed out/retried; the watchdog began shutdown, but its save flush also remained pending. The fixture was snapshotted before stopping its three disposable processes. This is test-induced by the diagnostic save command, not a measured soak stall, and is not a clean-save verification. A second, low-load control ran two moving clients for 61 seconds with no duplicate warnings and 100 unique entity UUIDs persisted at backend shutdown; it did not reproduce the high-concurrency failure. Details: `performance-results/current-candidate-entity-duplicate-forensic-20261024.txt` and `%TEMP%\multipaper-26.3-entity-dupe-controlb-20261024`.
- Keep the existing completed scheduled-tick, entity recovery, and mechanics evidence. Outstanding release gates remain human online-mode play (user will test later), intended-hardware capacity evidence without sharing resources with live services, clean cache-disabled reproducibility, and final packaging. Stable remains unpublished.


## Follow-up — transient entity persistence defect — 2026-10-24

The previous final section's statement that no source fix had been made is superseded. Master transient entity writes were confirmed to parse compressed NBT as raw data and had no async error reply or lock release. Fixed WriteChunkHandler to inflate/merge/recompress, prefer the current transient snapshot for matching UUIDs, retain disk-only entities, deduplicate UUIDs, and acknowledge errors while releasing the lock. Focused Java 25 validation passed 41 tests across WriteChunkHandlerTest, ContainerTransactionManagerTest, and MasterIntegrationTest; malformed payload integration also verifies waiting readers resume. Details: performance-results/current-candidate-entity-duplicate-master-merge-fix-20261024.txt.

Updated local Master jar SHA-256: C15441F65058F28ED37EE6FC353E84E70C72EAF2A72776D34B26410566BC0F06. Staged RC2 package is stale (Master E9ACBAFCC7189E0157643142A065841680BAE7E9BE784394BF0F880E4BB0A0D5, Paperclip 40436D886F39432AE7272311B35CEFB7B757BCBDDE7F997F36903ADEFE2FB5BF). This is only a per-chunk merge fix; cross-chunk UUID persistence remains unverified and blocks entity certification. Reproduce against a fresh disposable two-backend fixture on the updated Master, then finish the clean Paperclip build/package and remaining online-mode, human gameplay, and intended-hardware gates. User said they will test online-mode gameplay later; do not ask again.


## Full Java 25 build after Master fix — 2026-10-24

gradlew.bat build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar :multipaper-master:build :multipaper-mastermessagingprotocol:test -x :multipaper-server:applyMinecraftResourcePatches --no-daemon --max-workers=2 --console=plain completed successfully in 3m25s. Paperweight applied all 64 Minecraft and 13 Paper server patches. Aggregated reports: 182 suites, 10,736 tests, 0 failures, 0 errors, 88 skipped; Master tests ran with the new transient-write coverage, while unchanged server tests and artifacts were up to date. The task deliberately excluded the resource patch task that attempts to reset the local upstream cache; resource regeneration had already completed in the earlier clean validation.

Build artifact SHA-256: Paperclip 40436D886F39432AE7272311B35CEFB7B757BCBDDE7F997F36903ADEFE2FB5BF; Bundler 987679EA0174DD3590417657D5BCC3D4BE62E665FBECD969AF9AE0B4CC81CE5B; updated Master C15441F65058F28ED37EE6FC353E84E70C72EAF2A72776D34B26410566BC0F06. The staged RC2 package remains unchanged/stale and has not been refreshed or runtime-certified against this Master. The open cross-chunk UUID issue and pending user-side online-mode gameplay remain release blockers; intended-hardware capacity and updated clean reproducibility are also outstanding. Stable release remains unpublished.

## Continuation checkpoint — transient entity save source fix — 2026-10-24

The active `:multipaper-server:applyMinecraftSourcePatches` Gradle task has completed successfully (64 patches applied; 9m02s). No build client remains active. Persistent source of truth is `multipaper-server/minecraft-patches/sources/0075-MultiPaper-process-transient-entity-saves-on-Master.patch`; generated `NewChunkHolder.java` now marks transient saves and sends the current snapshot directly to the Master, avoiding Moonrise's old-disk entity concatenation. The Master already has UUID-aware transient merge from the preceding checkpoint. This is a candidate root-cause fix, not yet runtime-certified.

The 16-client run described in the immediately preceding forensic note is invalid: it paired the updated Master `C15441F6…66BC0F06` with a stale Paperclip `40436D88…FE2FB5BF`. The Master logged protocol decoder `IndexOutOfBoundsException` and backend A hung during shutdown. Its MCA scan still establishes duplicate records in the failed fixture, but neither runtime nor shutdown outcome certifies the new fix. Do not reuse that run as passing or failing evidence for the matching candidate. The stale forensic note's proposed next steps are superseded by this checkpoint.

Next: run focused tests; rebuild Paperclip, bundler, Master and protocol as one matching candidate; hash and stage only after a passing disposable two-backend integrity/mechanics run. Then continue remaining release gates: user-side online-mode gameplay (user said later), intended-hardware capacity on isolated resources, cache-disabled clean reproducibility, and final packaging. No production/Crafty access. Stable release remains unpublished.

## Fresh matched-candidate entity regression — 2026-10-24

The Java 25 full build succeeded after adding repeat-safe source regeneration tasks for the tracked NewChunkHolder base patch and transient entity-save patch. It built matching RC2 artifacts: Paperclip SHA-256 `629192CADF7DEF419DDB397F0263488ACC8A4A4D1DE5F440C99BA3B77BD7BCA9`; Bundler `7139D934AB8E2E1BCFDC3D5E3F83CED9942DED5E9B810FACB8124BCCE3F8F0AC`; server jar `4BE4FABED03E0304BA23FB7A3A41818F79AEAFF81EA9432F81F631F1EF67164E`; Master `C15441F65058F28ED37EE6FC353E84E70C72EAF2A72776D34B26410566BC0F06`. Build passed in 7m03s; reports across projects: 185 suites, 10,739 tests, 0 failures/errors, 88 skipped. `:multipaper-server:compileJava` also passed after explicitly applying the persistent NewChunkHolder patches. The full build continues to exclude only `applyMinecraftResourcePatches`, whose `git reset --hard upstream/main` fails; resource outputs were already present and used.

Fresh disposable matching pair `multipaper-26.3-entity-matched-fresh-20261024` ran 16/16 offline protocol clients for 31 seconds with movement and zero duplicate UUID warnings, missing entity requests, unsubscribed requests, or tick overruns. Initial login had 157 movement rejects before the measured soak. After an orderly backend stop, read-only Master MCA scan found 80 entity records / 70 unique UUIDs and 10 UUIDs still duplicated across chunks. This is a confirmed persisted entity-integrity defect despite clean runtime warning counts; it blocks release. Scanner and raw logs are retained in that fixture.

`Verify-TwoBackendReplication.ps1` on the matching candidate passed hopper item conservation/owner handoff but failed a chest/shulker or peer-piston assertion on separate fresh probes. The script selects a piston controller using `execute if loaded`, which does not prove ownership; verify/repair that probe to use `ticklockprobe state` before treating the piston result as a product defect. The repeated duplicate-MCA result does not depend on that harness issue.

The initial bootstrap attempt used the stale `R0.1-SNAPSHOT` Paperclip (`Build-Number: RC1`) and is invalid; the corrected runs above use the matching RC2 artifact. All disposable fixture ports are closed; no production or Crafty access occurred. Do not publish stable. Next: trace how a UUID is written in both entity chunks, implement durable cross-chunk ownership/deletion semantics in persistent patches and Master storage, add a restart regression, then rebuild and repeat the matching-pair scan. Human online-mode Survival/farm/portal play (user said later), isolated intended-hardware capacity, cache-disabled reproducibility, and final package are still pending.

### Current root-cause direction — supersedes transient-only certification

The Master `WriteChunkHandler.merge` fix deduplicates UUIDs only inside one entity chunk file. The fresh scan found all 10 duplicate UUIDs in separate chunks, so this merge cannot guarantee global uniqueness. The leading mechanism is a moved entity being saved in its destination chunk while a stale transient/source-chunk snapshot remains in Master storage; this is consistent with the differing persisted positions, but the exact message/write ordering still needs instrumentation. A durable move/tombstone or equivalent cross-chunk ownership transaction must update both entity files and survive Master/backend recovery before this gate can close.
MultiPaper 26.3 entity persistence regression — 2026-10-24
========================================================

Confirmed source change
-----------------------
Persistent Paperweight source patch `multipaper-server/minecraft-patches/sources/0075-MultiPaper-process-transient-entity-saves-on-Master.patch` now allows the first empty transient entity save to reach RegionFileStorage, where the Master deletes the stored entity chunk. It restores `lastEntitySaveNull = save == null` so later empty saves are skipped. Paperweight applied all 64 source patches and the full Java 25 build passed.

Master regression coverage
--------------------------
`MasterIntegrationTest.entitiesChunksAreStoredPerChunk` now writes entity data, sends an empty entity chunk, and verifies that a peer reads zero bytes. The focused test passed. This confirms Master empty-chunk deletion; it does not cover duplicate records across two chunks.

Exact package from the full Java 25 build
-----------------------------------------
Paperclip SHA-256: BA30656535AFD61726D9E44180A27A246FC25A0B75D4442CD2F91FBFF6E27797
Bundler SHA-256:   2308CBCBC7C64C778487CD69E12853B0D114A3897BEFB158033781B901BF97C8
Server JAR SHA-256: 864AB41118F6A0695DE655A24CE4710D6F83FEA89FA911D0766F280D91DAE3F0
Master SHA-256:    CF7966061ADC78FB36DEF34D514318C7585017F1C2694BD1246852BC9B854FEF
Full build: Java 25, `build`, Paperclip, bundler, Master, and protocol test tasks succeeded. XML totals: 185 suites, 10,739 tests, 0 failures, 0 errors, 88 skipped. `applyMinecraftResourcePatches` remains excluded because it tries to hard-reset the local upstream checkout; the generated resource outputs were already up-to-date. Exact JAR byte reproducibility remains unverified.

Disposable runtime regression
-----------------------------
New script: `scripts/Test-EntityChunkSaveIntegrity.ps1`. It runs the existing 16-client movement soak, stops both backends orderly, stops the fixture Master, and scans entity MCA files in the overworld, Nether, and End. The script was syntax-checked. The first run against the full-build pair passed 16/16 clients for 61 seconds with zero disconnects, duplicate UUID warnings, missing entity requests, movement rejects during soak, or tick overruns; after orderly stop the overworld scan found 1 UUID persisted in two chunks.

A second disposable run with opt-in Master write tracing found 2 cross-chunk duplicate UUIDs after the same soak and orderly stop. Both were bats/cod with plausible chunk-local NBT positions. No empty chunk-delete event occurred for their source/destination chunks. The records and full logs are retained under `%TEMP%\\multipaper-26.3-entity-pairedtrace-5560f1ec`; scanner output is `entity-integrity-scan.json`. The trace Master hash was EBB70CE348DDF49D4E46E477F3BD92FC2E2C6E52A68849CF7C3697E058C1717B, paired with the above Paperclip. This differs only by opt-in diagnostic tracing from the full-build Master.

This is a fresh release-critical persisted entity-integrity failure. The empty-save patch improves deletion behavior but does not solve the cross-chunk duplicates. The normal writes that appeared in the trace were for the same entity UUID in different chunk files; a durable, idempotent cross-chunk ownership/tombstone transaction or equivalent remains necessary. Do not publish stable. Retain the failing fixture and logs.

Other open release gates
------------------------
- Real Minecraft online-mode GUI login and human Survival/farm/redstone/portal play: user said they will test later; no result yet.
- Isolated intended-hardware capacity measurement on the exact candidate: not done.
- Cache-disabled reproducible build and final package checksums/manual installation package: not done.
- Do not access Crafty or production; stable remains unpublished.

### Correction: entity scan completion — 2026-10-24

The preceding “Fresh entity-load regression” / “entity persistence regression” notes reported MCA duplicates from a scan that ran after the game ports closed but before the backend JVMs exited. Those were premature scans, not completed shutdown results. After restarting the disposable Masters, the backends finished saving, and all three earlier fixtures rescanned with zero duplicate UUIDs (78/78, 96/96, and 65/65). The fixture test was corrected to verify the RCON/game listener belongs to the same disposable backend and to wait for the backend process to exit before stopping the Master and scanning.

The corrected regression ran on the exact non-debug package pair: Paperclip `BA30656535AFD61726D9E44180A27A246FC25A0B75D4442CD2F91FBFF6E27797`, Master `C15441F65058F28ED37EE6FC353E84E70C72EAF2A72776D34B26410566BC0F06`. Sixteen offline protocol clients completed 61 seconds with zero disconnects, duplicate UUID warnings, missing/unsubscribed entity requests, movement rejects during the soak, or tick overruns. After both backend processes exited, all three dimension scans found 68 records / 68 UUIDs / 0 duplicates. Fixture and result: `%TEMP%\\multipaper-26.3-entity-finalpair-4e84b5d5`. The source patch that permits first empty transient saves to delete the stored entity chunk and the Master empty-write regression remain in place.

Master write tracing was removed from source after its diagnostic use. The trace-only Master JAR hashes `999CF5…` and `EBB70C…` are superseded. No current entity-integrity defect is demonstrated by a completed backend shutdown; do not rerun entity synchronization checks absent a fresh failing result.

Still-open release gates: real online-mode GUI login and human Survival/farm/redstone/portal play (user said later); intended-hardware capacity on isolated resources; a cache-disabled clean reproducibility check after the latest source patch; and final evaluation packaging/checksums. Stable remains unpublished. No production/Crafty access occurred.

## Continuation — clean builds and live block-update probe — 2026-10-24

- Checked Gradle before building: no Gradle client was active. Ran two sequential Java 25 cache-disabled full build/package/test passes, each successful (185 suites, 10,739 tests, 0 failures/errors, 88 skipped). Both Master and server JAR hashes matched exactly across runs. Paperclip and bundler ZIP/JAR hashes differed because entry timestamps differed; all uncompressed entry payloads matched byte-for-byte. `git diff --check` passed with existing CRLF conversion notices. Exact byte reproducibility remains open.
- Pass 2 hashes: Paperclip `E839A5F8102D7AB9015DC74BBC564C98D3F7CDF8A6F267E8E1C7D02B9B65D008`; bundler `2807F30E81F69AD6FC761702187DD8EBF234B1B4C696F2F606DFDA7DDC32CED2`; server JAR `0DA99374B672A2FD0BBFD1F8A5E0584726047B093931F025ECC54FF8BEA8A9CF`; Master `C15441F65058F28ED37EE6FC353E84E70C72EAF2A72776D34B26410566BC0F06`. Copies/manifests: `performance-results/repro-pass1-20261024/` and `performance-results/repro-pass2-20261024/`.
- Ran `applyMinecraftResourcePatches` successfully with output redirected to a temporary directory and `checkoutPurpurRepo` excluded. It applied zero patches; generated output had the same 9,893 files and content as the tracked resource tree. No reset was run and the dirty worktree was preserved. Forced task reruns attempted slow Paperweight source copies and were stopped safely; the successful task was the incremental isolated-output invocation.
- Started disposable current pass-2 runtime `%TEMP%\multipaper-26.3-release-cert-20261024` (Master 31921, backends 31923/31924). The earlier marker check proved load-time persisted reads only. A fresh probe with a 26.3 protocol client connecting through the local proxy and RCON mutations found changed blocks on the writing backend but not its peer; the chest update also failed the earlier replication script. Master logs show the test player connected to Backend B, but this RCON/protocol setup did not independently confirm live chunk owner plus subscriber state. Treat live update replication as an unresolved release gate, not yet a source-root-caused defect. Do not reuse the load-time marker pass as live-update evidence.
- The disposable client and both backends were stopped orderly; the matching disposable Master was then stopped. No source changes were made in response to this probe. No production/Crafty access occurred.

Next: prove the chunk is owned by the writer and the peer is a registered subscriber while both hold the chunk, then repeat a changed block and block-entity update; fix the persistent patch source and add regression coverage if it still fails. Do not rerun tick/entity checks without a new regression. Remaining release gates also include human online-mode Survival/farm/redstone/portal play (user said later), exact-candidate intended-hardware capacity, exact archive reproducibility, and final package/checksum documentation. Stable release remains unpublished.
