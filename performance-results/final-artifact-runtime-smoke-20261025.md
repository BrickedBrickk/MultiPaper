# Exact clean-built RC4 Paperclip runtime smoke — 2026-10-25

## Artifacts under test

- Paperclip: `multipaper-paperclip-26.3-RC4.jar`, SHA-256 `F6466A12BE9BAF2B0071902D4B0AA764AC82698B551A00D633353D1D5517D694`.
- Master: `multipaper-master-3.0.0.jar`, SHA-256 `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`.
- Java: Temurin 25.0.3.

## Disposable fixture and checks

A private copy of the retained test fixture was created at `%TEMP%\multipaper-rc4-clean-build-runtime-20261025`; the retained fixture was not changed. Master coordination/proxy ports were 31921/31922; backend ports 31923/31924; loopback RCON ports 31931/31932. Both backend `server.jar` files were replaced with the exact clean-built Paperclip above; the cloned Master JAR matched the exact Master hash above. The fixture ran offline-mode only with a synthetic client.

Both backends registered with the Master and reached `Done` (Backend A 17.186s, Backend B 15.156s). RCON `/plugins` on each listed the five enabled components:

- Floodgate Spigot `2.2.5-SNAPSHOT (b141-81b65cc)`
- GrimAC `2.3.74`
- InvSee++ `0.31.22`
- ViaBackwards `5.12.1`
- ViaVersion `5.12.1`

Geyser Standalone is a separate process and was not part of this RC4 Paperclip smoke. The prior loopback status ping is recorded in the compatibility report; no Bedrock account authenticated.

`Run-ProxyCommandProbe.js` connected synthetic protocol `26.3` client `CommandSmoke` through the Master. It received chunks and passed `/list`, `/tps`, `/mspt`, `/slist`, `/mpdebug`, and `/version`. `/slist` listed both backends and the client on Backend A. This verifies current exact-hash startup, plugin loading, Master registration, chunk delivery, and command routing only; it is not online-mode authentication, human gameplay, Bedrock login, or GrimAC legitimate-client evidence.

Startup had no plugin dependency failure or exception. Existing warnings: Floodgate's shaded Guava calls terminally deprecated `sun.misc.Unsafe.objectFieldOffset`; GrimAC noted version 2.3.75 is available. No code or plugin configuration was weakened.

Both backends received `save-all flush` and `stop`; both exited before the disposable Master was terminated. All TCP listeners on ports 31921–31932 were confirmed closed. Logs are retained in the disposable fixture directory. No production server or Crafty was accessed.