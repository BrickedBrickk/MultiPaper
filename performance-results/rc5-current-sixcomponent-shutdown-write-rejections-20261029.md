# RC5 current six-component disposable smoke: shutdown write rejections

Date: 2026-10-29 (America/New_York)

## Scope and safety

Ran the current Java 25 Paperclip/Master pair with the six hash-pinned components in disposable fixture `D:\multipaper-tests\system-temp\rc5-current-sixcomponent-freshworld-20261029-013000`. Both backends used a newly named, newly generated world `rc5sixstack1029013000`; no live Crafty process, world, port, or host was accessed. Geyser bound only to loopback UDP. The synthetic Java client `StackSmoke` logged in, received chunks, and ran `/list`, `/tps`, `/mspt`, `/slist`, `/mpdebug`, and `/version`.

This was not a clean persistence pass. During Backend A's orderly stop, at 00:16:14–00:16:15, Moonrise logged 18 failed block-chunk writes and `MultiPaper.writeRegionFile` threw `IOException: Master rejected persistence`. Backend A's save summary still reported 1,101 block chunks saved, 25 entity chunks saved and 0 POI chunks for the overworld; it then reported the Master rejection errors. Backend B reported 0 block chunks and 1 entity chunk saved at shutdown, with no matching write-rejection lines. Stack startup/proxy assertions passed, but persistence is **INCONCLUSIVE / FAIL-CLOSED**, not PASS.

The Master was still running while the backend shutdown saves were issued, and the runner requested `save-all flush` on both backends before `stop`. The Master log records both backend connections closing after their shutdown. The rejection is consistent with the existing owner fence refusing a write from a connection that is not the current owner or that has already relinquished ownership. This protection is preferable to accepting a potentially stale snapshot. The available Master log does not identify the owner for the 18 chunk coordinates, and there was no chest seeded in those coordinates, so this run does not establish whether a current owner later persisted those chunks or whether any contents were lost. It is a meaningful unresolved save/ownership diagnostic that needs per-chunk owner correlation and a focused persisted-content test before release.

The older six-component fixture run also had Master-rejected writes, but used an older copied world. This fresh-world run demonstrates the message is not limited to that old fixture. No code change was made to the chunk persistence/ownership fence based on this smoke alone. The disposable smoke runner was updated to stop backends gracefully while Master is live and refuse to print PASS if either backend log contains `Failed to write chunk data` or `Master rejected persistence`.

## Evidence

- Runner: `scripts/Test-RC5CurrentSixComponentStack.ps1`, SHA-256 `60583F718332CE7DC72461CEE8685EC7E7EC12A583203DE8E0B81E37A3C84D96` after the log-gate update. PowerShell parser validation passed.
- Backend A output: SHA-256 `34D46DF6F0D883C521207A8516871EF4B5A68738CDF089F8250DDD3C9E74D65B`.
- Backend B output: SHA-256 `8DF5C3F77F7CFBDA2AD1069986C64BA056CFB40C4A788A8F5E9BAF89C4C4A6E7`.
- Master output: SHA-256 `9C7E535ABA2EA85A4057EEA551A0910B1B1A35BDDD459558251C1A47FB819383`.
- All raw output and error streams remain in the disposable fixture path above. This preserves the captured logs; do not treat the former runner's printed PASS line as a persistence pass.
- Runner cleanup completed. No Java processes remained and test TCP ports 32651, 32652, 32661, 32662 and UDP port 32672 had no listeners.

## Next test needed

Use a clean isolated world and put known distinct contents in single and double chests at chunks held by each backend. Capture the owner before save/unload, request orderly and ownership-transfer saves, and correlate each rejected write with the Master owner and the subsequent accepted persisted snapshot. Restart one backend and Master in separate controlled cuts, then compare exact chest NBT and item totals. Keep the current owner fence in place unless a regression proves the ownership protocol itself wrong. Do not use live world data or automatically restore anything.

## Exact-pair rerun with populated, forceloaded chest — 05:38–05:40 EDT

Corrected the disposable runner so it forceloads the selected chunk after the synthetic probe player disconnects. A fresh world `rc5v45six1031` passed login/chunk delivery and the six-command probe, plus protocol-777 status ping. The test seeded diamond x17 at block `(-31,76,-199)`, ran `save-all flush`, mutated it to x29, and verified exactly x29 after Backend A stopped (read by Backend B) and after Backend B restart. No Master restart was included in this run.

The overall test still failed its persistence-log gate. Backend A emitted 20 distinct Master-rejected region-chunk writes during orderly shutdown, including chest chunk `(-2,-13)`. The Master was alive. `WriteChunkHandler` invokes `ChunkSubscriptionManager.runIfOwnerOrUnowned` for region writes, so rejection is the expected response when the sender is not the current owner; however, the Master output has no owner mapping for these coordinates and this experiment does not establish that each rejection was expected. The exact chest contents surviving both backend reads/restart shows no loss in this one schedule despite the rejected snapshot; it does not establish a clean save path or power/Master restart durability. Preserve the ownership fence pending per-coordinate owner correlation.

Runner was corrected and parser-checked (SHA-256 `E3ED71AED6570B9F1FAF5186AF356F125912729FF14C758D455706BD94D13FF7`). Raw run path: `D:\multipaper-tests\system-temp\rc5-v45-sixcomponent-freshworld-20261029\six-component-smoke-20261029-053801`. Backend A log SHA-256 `82DC40C7162F580A8A47193133275D023A7C78CB51318C88B76DB50D9A592988`; Backend B `A53EBDB4163255E6452A38DEDF2FDF043E7C7FFF0213088AE0FA281B976D8126`; Backend B restart `BD3C5D1F1B9EBFA66F26BDE047950B5F4356A4FA1E2C592B96F3A36F5F62CA5E`; Master `53162B3AA77C4395D5DD40F449D4CDC0A490D6EB3C9053180D8D652E2E9CC857`; Geyser `29D26503469DCC75C71FB1AAEC1E35BF83C7F300BAA1034CB9CD30E50A653DF5`. There were 20 distinct rejected coordinates and 20 exception lines; Moonrise also emits a failed-write error record per exception. Fixture TCP/UDP listeners and Java processes were checked closed.
