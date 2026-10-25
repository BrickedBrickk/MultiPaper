# Exact clean-built RC4 player dimension transfer — 2026-10-25

## Artifacts and setup

- Paperclip/server payload SHA-256: `F6466A12BE9BAF2B0071902D4B0AA764AC82698B551A00D633353D1D5517D694`.
- Master SHA-256: `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`.
- Java 25.0.3; exact clean-built Paperclip on both disposable backends and exact Master; Floodgate 2.2.5-SNAPSHOT (b141-81b65cc), ViaVersion/ViaBackwards 5.12.1, InvSee++ 0.31.22, GrimAC 2.3.74 loaded on both backends.
- Offline protocol-26.3 test client `DimensionProbe` connected through the Master player proxy. It was explicitly operator-granted only in this disposable fixture for the existing command smoke.
- Focused reusable runner: `scripts/Verify-PlayerDimensionTransfer.ps1`. Raw logs: `%TEMP%\multipaper-rc4-clean-build-runtime-20261025\dimension-transfer-validation-20261025d\`.

## Result

- Passed: RCON teleported the connected player from Overworld to Nether. Both backend RCON views confirmed the Nether dimension and identical position `[1285.5d, 80.0d, 1312.5d]` in this run.
- Passed: RCON returned the same player to Overworld. Both backend RCON views confirmed Overworld and identical position `[1200.5d, 90.0d, 1200.5d]`.
- Passed: the same proxy client completed `/list`, `/tps`, `/mspt`, `/slist`, `/mpdebug`, and `/version`; `/slist` showed both backend names.
- Both backends reached `Done` and enabled all five plugins. The fixture save-flushed and shut down; Master stopped after backends. Ports 31921–31932 are confirmed closed.

## Limits and observed environment warnings

This checks server-side dimension change and cross-backend player position replication using a synthetic offline client and administrative teleport commands. It does **not** verify physical Nether portal construction/activation, player-walked portal travel, human gameplay, authenticated Java, Bedrock/Floodgate, or legitimate GrimAC movement.

The Windows fixture logged OSHI/JNA performance-counter registry errors and null-process-stat NPE traces because this host's PerfOS/Perflib counters are malformed; both backends nevertheless reached `Done`. GrimAC repeated its known warning that ViaBackwards on 1.21.2+ can cause unsupported older-client vehicle behavior. Floodgate's deprecated shaded Guava warning remains from the exact-hash smoke. These warnings are recorded rather than suppressed.
