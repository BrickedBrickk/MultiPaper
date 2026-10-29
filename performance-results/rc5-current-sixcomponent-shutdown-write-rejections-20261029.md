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
