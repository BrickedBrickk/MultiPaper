# RC5 v43 packaged-pair 273-read chest race — 2026-10-29

## Exact artifacts and fixture design

- Normal packaged Paperclip: `4180250D05A570009F30349A0251A4924D11D403ED7E4C65CF5686C18B204E7F`.
- Packaged Master: `E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E`.
- API: build output from the two-clean-build set in `rc5-player-session-registration-readiness-20261029.md`.
- Runner: `scripts/Test-RC5CurrentCandidateChestRace.ps1`, SHA-256 after adding this Paperclip to its known-artifact allowlist: `0E5B72E03C54915D2814F7823B68959B2FCA484E32160A9A3D2FB331EB5571DA`.
- Runtime: Temurin Java 25.0.3+9. Fresh `.multipaper-disposable` fixture roots under `D:\multipaper-tests\system-temp`; worlds and chests were synthetic. The production no-holder read path was explicitly asserted (`holder=null`). Each burst used 273 asynchronous Master-backed reads, bounded to 32 workers, behind a pending-save barrier. After the delay, the former owner attempted to write its captured stale chest-bearing chunk snapshot through `MultiPaper.writeRegionFileNBT`.

## Results

Both directions **PASS**. Master rejected the stale former-owner write in each run. The new owner initially loaded the original single chest with 32 diamonds, wrote the current value of 17, and retained it through a new-owner backend restart and graceful Master restart. The double chest retained emerald x17 on its left half and gold ingot x23 on its right half. Each run recorded exactly 273 no-holder warnings for the forced read burst and confirmed the target chunk warning. Master was not force-stopped.

| Direction | World / fixture | Result SHA-256 | RCON transcript SHA-256 |
|---|---|---|---|
| Backend A → Backend B | `rc5nh1029051533` / `D:\multipaper-tests\system-temp\rc5-v43-chest-418-e9-273-AtoB-20261029` | `68E7A3F67AB2A4F4E2550A44AD6B7EEA3D94E42436B1CA2D546C8A7D4C3CB3AD` | `58E72CDCC90C536105BAFBE4F798286C0FA6EB7A979BEA35148AB351749759D9` |
| Backend B → Backend A | `rc5nh1029051741` / `D:\multipaper-tests\system-temp\rc5-v43-chest-418-e9-273-BtoA-20261029` | `1EF33D9666F6EEBC8581C7E8DABA8DDDA3D8C0A083777E9E1EB402990F65428D` | `650411E1FAEB295AFEA8569CA61632D7EFB75F3AE98CD0BAEE4F71F4C1510E3F` |

Full disposable backend/Master log hashes and empty stderr hashes are retained in the two fixture `run-logs` directories. Both test sessions exited successfully. TCP ports 33651–33654 and 33661–33662 were verified closed, and no fixture Java process remained.

## Interpretation and limits

This is a direct no-holder/stale-writer/restart regression on the exact normal JAR pair in blocked bundle v43. It demonstrates that this tested Master path reads Master-backed chunk state and does not let the delayed former owner replace the newer chest snapshot in either ownership order under the selected schedule. It does not establish the source of the live players' empty chests, verify every Moonrise unload/save/power-loss ordering, test a crash during the pending save, or justify restoration of live data. RC5-01 remains P0 `TESTING`; no live service, world, or player data was accessed.
