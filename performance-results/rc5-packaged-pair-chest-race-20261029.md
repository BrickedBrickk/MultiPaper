# RC5 packaged Paperclip/Master chest persistence race — 2026-10-29

## Exact artifacts and fixture isolation

- Paperclip: `8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692` (the current package candidate).
- Master: `1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E`.
- API JAR: `57A422D7A3EB95DD89551BF0EBB2B6FDDD56CB35081E9D5C43D7EA19CEF1DC57`.
- Java: Temurin 25.0.3+9. Fixtures were fresh and marked disposable under `D:\multipaper-tests\system-temp`; the source template was the marked disposable chest attribution fixture. The two fixtures used the repository's exact stale-owner race runner, with its allowlist extended to include the packaged Paperclip SHA-256. No source behavior changed.

## Results

Both ownership directions passed 273 bounded concurrent no-holder reads while submitting the delayed former-owner chest snapshot. Each run observed exactly 273 target-chunk no-holder warnings, Master rejected the stale x32 former-owner image, and the new owner's x17 single-chest diamond stack plus double chest emerald x17/gold ingot x23 were exact after handoff, new-owner backend restart, and graceful Master restart.

| Direction | Result SHA-256 | RCON transcript SHA-256 |
|---|---|---|
| Backend A → Backend B | `1A35CE676B2BF6826DB848B68CAEDDB3AD1CE47152EF76588D61F5BA16B34D10` | `29219E1A1976F37300FF46805DA7A14728E95416DFCEF0DBD665650952073294` |
| Backend B → Backend A | `42A1A80AAC264B6B71A0A52A96758D66288C68C4F0659ECFE3BFB3190DB1B679` | `BBEC2AEB07A9CB5AC0253D5B238573DEA58372DA35D88EAA0C2AB4973D58AEEF` |

Full per-process logs and hashes are retained in each fixture's `run-logs` directory. Backend stderr and Master stderr were empty in both fixtures. Both Master shutdowns were graceful. After both runs, the six test ports `33651–33654, 33661–33662` had zero listeners and zero fixture Java processes remained.

## Limits and status

This closes the prior artifact-hash gap for these selected save/handoff/restart schedules: the prior tests on Paperclip `99B24A…` do not substitute for this exact packaged-pair result. It does not cover power loss, every Moonrise unload/save interleaving, production hardware, the live empty-chest incident, or recovery of any player data. The source log attachment remains unavailable for a fresh parse. No live host/world was touched. Keep RC5-01 P0 `TESTING` pending live incident attribution and broader lifecycle evidence; never auto-restore. InvSee++ RC5-02 remains P0 `BLOCKED`.
