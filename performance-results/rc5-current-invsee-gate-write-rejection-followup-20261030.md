# Exact-current isolated InvSee gate and Master write-rejection follow-up — 2026-10-30

## Scope and custody

Used only the disposable fixture `F:\multipaper-tests\rc5-invsee-current-3bc6f46-20261030` with exact-source Master/Paperclip artifacts from source `3bc6f46f3c6dc006540b5dc3b74b7033a35ccdb2`. A fresh synthetic world name was configured for both backends. The copied donor data and logs remain in the disposable test volume. No live Crafty process, world, player data, or server config was accessed or modified.

The newly referenced Crafty1 `1latest.log` is not mounted in this session and is absent from `C:\Users\elibo\Downloads\1latest.log`. Therefore this report does not reparse, recount, or correlate the user's new upload. The retained historical digest/counts are documented in `docs/RC5_BUG_TRACKER.md` and its linked Crafty1 evidence reports.

## Runtime result

A protocol-valid synthetic InvSee quick-move click was refused by the current fail-closed remote-click gate. The target retained exactly 16 diamonds and the viewer received inventory resynchronization. The runner result was **PARTIAL**, not a clean pass.

The fresh-world backend logs recorded 29 Master-rejected region writes from backend A (29 unique chunks) and 162 from backend B (92 unique chunks), with 26 chunk coordinates in common. Duplicate-UUID warnings were zero. The Master stopped gracefully; all fixture listeners closed and no fixture Java server remained.

Pinned artifacts:

- Master SHA-256: `86F694BA1882E2D08DC841ECE62EA11B105663DAADEF3D7C1C8DF52B1531BE19`
- Paperclip SHA-256: `464FCE20DEE5A7734666A573BA207F94E4D3E6AD7903C6BEF02A648CB32CD4F7`
- InvSee++ plugin SHA-256: `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9`
- Fixture logs: `F:\multipaper-tests\rc5-invsee-current-3bc6f46-20261030\rc5-gate-runtime-logs-reusable-124251\`

## Interpretation and next test

Current Master source atomically accepts a region write only from the active owner, or when the chunk has never been owned. It rejects a different active owner and rejects the no-active-owner/remembered-previous-owner case. The saved runtime logs do not report which branch rejected each write, nor give a Master-side owner-transition timeline. Consequently this run cannot classify the rejections as stale saves, valid suppression of an obsolete image, or an independent defect. They are not chest-loss evidence, but make this run unsuitable as clean persistence verification.

Next useful step is structured, timestamped isolated diagnostics containing world/chunk, requester, active owner, remembered previous owner, and rejection reason, followed by an isolated populated-chest run correlating claim, announced save, rejection, Master disk write, unload/reload, and restart. Do not relax the owner check or restore live data based on this result.

The gate contains the tested quick-move schedule; it does not provide the missing owner-attested live InvSee transaction or prove cross-owner item conservation. RC5-02 remains P0 `BLOCKED`; RC5-01 remains P0 `TESTING`. No fix or live recovery is claimed.
