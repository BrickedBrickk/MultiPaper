# RC5 isolated Master chunk-write rejection diagnostics — 2026-10-30

## Scope

This is a diagnostic-only Master change. Under the existing per-chunk lock, the write guard now captures the requester, active owner, previous owner, and one of the two existing rejection reasons. `WriteChunkHandler` emits a timestamped `[MULTIPAPER_CHUNK_WRITE_REJECT]` line only when the Master refuses a region write. The acceptance condition, lock scope, pending-write cancellation, and false acknowledgement are unchanged. The log contains backend names and world/chunk coordinates, not chunk NBT or player data.

The new Crafty1 `1latest.log` remains unavailable at the named Downloads path or as mounted bytes. This report does not claim a fresh parse of the uploaded log. All runtime work below was in a marked disposable fixture; no live Crafty/SMP process, world, or player data was accessed.

## Focused and full test results

Temurin Java 25.0.3.9, Gradle 9.8, detached worktrees at base `1b460804887628cad720c3c067e56b2b3c4d19f6` with only the diagnostic source/test patch applied:

- The full suite includes the 3 new reason-classification tests and existing Master integration tests for active-owner stale writes, former-owner writes (including after last unsubscribe), first-write behavior, chest NBT preservation, and restart persistence.
- After optimizing the accepted-write path to avoid constructing diagnostic records for successful saves, the full Master suite passed **102/102** in each of two independent worktrees; 0 failures, 0 errors, 0 skipped. The uncached clean rebuild/test runs took 2m54s and 2m53s.
- Both independent `multipaper-master-3.0.0.jar` outputs are byte-identical: SHA-256 `794EBBB2E2EA0C14E8FE318736BD147702892B244BCA426D5CAE838F966178DC`.
- Final source SHA-256: `ChunkSubscriptionManager.java` `8672FCEF717B698774526AAEF745618406B70CBB2977775425D993388439BDFC`; `WriteChunkHandler.java` `52191877A5F6B82EBE0BEF912B80BA86EEDB38C1C2A9AC812D88491274183E4B`; new `ChunkSubscriptionManagerTest.java` `5D0FF924A3D7BDF5D82A2BE302BE1D7CC7334D12BF956A58EF7BA33B64EE1169`.

## Exact-current disposable runtime

Reran the protocol-valid quick-move containment scenario against the final diagnostic Master JAR and exact-source Paperclip SHA-256 `464FCE20DEE5A7734666A573BA207F94E4D3E6AD7903C6BEF02A648CB32CD4F7` plus InvSee++ SHA-256 `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9`. The world was fresh and synthetic. Viewer mutation was denied; the target retained exactly 16 diamonds. The runner outcome was **PARTIAL** because Master-rejected region writes remained present. Master exited gracefully and all six listeners closed.

The backend logs contained 70 rejected-write exceptions from `race-a` across 48 coordinates and 163 from `race-b` across 98 coordinates, with 30 shared coordinates. Master emitted 233 structured rejection records over 116 unique coordinates. Every backend error occurrence matched a Master record for the same requester and coordinate (70/70 and 163/163). Master classified them as:

- 87 `ACTIVE_OWNER_MISMATCH` (22 requester `race-a` versus active owner `race-b`; 65 requester `race-b` versus active owner `race-a`).
- 146 `NO_ACTIVE_OWNER_PREVIOUS_OWNER_RECORDED` (13 requester `race-a`, previous owner `race-a`; 35 requester `race-a`, previous owner `race-b`; 40 requester `race-b`, previous owner `race-a`; 58 requester `race-b`, previous owner `race-b`).

This confirms which existing guard branch produced the rejection in this synthetic schedule. It does not prove each rejected snapshot was stale, does not establish the live Crafty warning burst had the same cause, and does not exercise a populated chest in this runtime. The focused integration tests separately preserve exact seeded chest NBT/items through selected stale-write and Master-restart cases. No live chest recovery is claimed.

Runtime fixture/logs: `F:\multipaper-tests\rc5-owner-reject-runtime-optimized-20261030\rc5-gate-runtime-logs-reusable-130621\`. Master log SHA-256 `29BBE6602B5B7EB3CD547DAF4844161BF46517FC1BB381BB1CF74596B822FA51`; backend A/B output SHA-256 `49CD85FF49900D206BAB047CAE9DB9DDBC9A1B3F1C2B3C4AD34FADC8C44EAB68` / `52768E657D7EC21EDBF1F01CAE23C8F1B81C656C7721F64C8BF367C58B27264C`; runner result SHA-256 `F00A7DB1ABCDBAAFEC1C263711B465D146E44F81BA142DA2DAC9FDC9B7DE2DB7`.

## Exact-current populated-chest no-holder stress

Ran the current final Master/Paperclip/API artifacts with the existing chest stale-writer regression runner in two fresh isolated worlds, using a temporary runner copy updated only for the current artifact hashes. Each direction seeded a single chest with diamond x32 and a double chest with emerald x17 / gold ingot x23; unloaded and handed the chunk between owners; performed 273 concurrent no-holder reads (32 workers); injected a delayed former-owner write; restarted the new backend; and gracefully restarted Master.

- **A → B pass:** world `rc5nh1030131708`, fixture `D:\multipaper-tests\system-temp\rc5-chest-273-diagnostic-final-AB-20261030`. All 273 reads completed and 273 fallback warnings were observed. `RC5_READRACE=PASS holder=null reads=273 workers=32 staleWrite=REJECTED diamond=17`. The stale former-owner write was rejected. The new owner first encountered a previous-owner/no-active-owner guard record, then the stale old owner was rejected against the active new owner. After backend B and Master restarts, the exact final contents remained diamond x17, emerald x17, gold ingot x23. Master was not force-stopped.
- **B → A pass:** world `rc5nh1030131848`, fixture `D:\multipaper-tests\system-temp\rc5-chest-273-diagnostic-final-BA-20261030`. Same 273/273 fallback and read-race assertions passed; the former-owner write was rejected; exact final single/double chest contents survived backend A and Master restarts. Master was not force-stopped.

Both runs used Master SHA-256 `794EBBB2E2EA0C14E8FE318736BD147702892B244BCA426D5CAE838F966178DC`, Paperclip SHA-256 `464FCE20DEE5A7734666A573BA207F94E4D3E6AD7903C6BEF02A648CB32CD4F7`, and API `26.3-RC5` from the same candidate build. The temporary stress runner SHA-256 is `F42489C223F11B3DC1E8F5A23B88FBE1BD8D5C0C9F619D81EDED93A11DBFFAC9`.

Exact evidence hashes:

- A → B: result `BC9D7A1C8E0A81FDA3234B7B38C8B8BE3A0CE00483AAFC6033E126133F12270B`; Master log `5B6D60988EA808EEF6493341BA9816B53BFF9CEEDDFB454AF3DCD3AF8DFD906D`; backend A/B logs `1FDB40CEA4375C67E5C594C3568F6DD41571247F91DB104DC6A5DF0128EF07A9` / `73BD4689C72F95AD02CE3F67AE7E8375DAEE2CD677DEC43BD65D89E76F73AF69`; RCON transcript `3FD2152DC92B48F52EFE07B01681FA36F5550703C59C846DC0AD8AB78FBB1C19`.
- B → A: result `5FE56EBE6E64E8955CABAFCBFDBD9FE0D56F149A6CE444855E4906F314C46EFC`; Master log `08928695E8A9661E4FB522BF2EFEEB82CD6CC46FB6D627573404AC7F95C1F9BE`; backend A/B logs `F1B341C699DA5DDD7707FDC8378F6D4F432FE0E054F4CB0E940A652CC34BCBE2` / `99318C2C939BFB32388D14394289658610E16657B27B9781007D3B27EBB54032`; RCON transcript `CCF7002458B56347457FE245F45C2D37F6533ED0E01ABDC260B8B0EF364057D2`.

All listeners closed and no test Java processes remained. An earlier four-read adaptation stopped only on a stale assertion string; the temporary matcher was updated to recognize the plugin's `workers=4` field, and the final 273-read runs passed. Failed and passing fixtures/logs were retained.

## Disposition

Keep the owner-write guard. The two exact-current chest stress runs verify the Master-backed no-holder fallback and chest preservation for these two handoff/restart schedules. This does not attribute the real players' reported empty chests or prove every save/crash timing safe. The newly referenced Crafty1 upload remains unavailable as mounted bytes. RC5-01 remains P0 `TESTING`; RC5-02 remains P0 `BLOCKED`. No RC5 artifact was published or marked installable.
