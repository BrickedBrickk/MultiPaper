# Proxy handshake fragmentation regression — 2026-10-25

## Finding

A valid Minecraft login handshake split across TCP reads was rejected by the exact published v26.3-RC3 Master. The unfragmented control stayed connected; the one-byte-fragmented handshake closed and Master logged `BufferUnderflowException` from `HelloPacket.readVarInt` through `HelloPacket.read` and `ProxiedConnection.rewriteHelloPacket`. This is a real proxy forwarding defect because the proxy discarded its handshake state after the first incomplete read, so the remaining handshake bytes bypassed address rewriting needed by Bungee forwarding.

Exact immutable RC3 fixture: `%TEMP%\multipaper-rc3-exact-six-stack-20261025`; Paperclip SHA-256 `22B1EE9737A98BA85A52AE09B6B9C6EB890331F9B7A9443BBD3CDEA9357C6460`; Master SHA-256 `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`. Reproduction logs: `%TEMP%\multipaper-shared-worlduuid-failclosed-blocks-20261025\proxy-handshake-fragmentation-20261025-163900`.

## Fix and regression coverage

The persistent Master source now buffers an incomplete framed handshake until the full frame arrives, then rewrites and forwards it once. The parser exposes a completeness check and retains existing long/legacy frame passthrough behavior. Added `HelloPacketTest.waitsUntilTheCompleteHandshakeFrameArrives()` covers every partial prefix. Runtime runner `scripts/Test-ProxyHandshakeFragmentation.ps1` starts a marked disposable two-backend fixture with forwarding enabled, checks an unfragmented control and split positions 1, 2, 4, and 20 bytes, then saves/stops the backends and restores the fixture configs.

Changed source:
- `MultiPaper-Master/src/main/java/puregero/multipaper/server/proxy/HelloPacket.java`
- `MultiPaper-Master/src/main/java/puregero/multipaper/server/proxy/ProxiedConnection.java`
- `MultiPaper-Master/src/test/java/puregero/multipaper/server/proxy/HelloPacketTest.java`

## Validation on final clean candidate

Two sequential clean Java 25 / Gradle 9.8 builds completed using `clean build createPaperclipJar createBundlerJar :multipaper-master:shadowJar --no-build-cache --no-daemon --max-workers=2 --console=plain`. Each reported 188 test suites, 10,745 tests, 0 failures, 0 errors, and 88 skipped. The final artifacts are:

- Paperclip `multipaper-paperclip-26.3-RC4.jar`, SHA-256 `DC4A57CF66E022AEDD4FD592F788F0D349E535C63527C14309976B9D859DF15F`.
- Master `multipaper-master-3.0.0.jar`, SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.
- Bundler SHA-256 `CA9781810B47DF8A55BDCFC18F6C112F68485E55DC2F6871DDB6134A5C167B3B`.
- Server SHA-256 `0B034628D95817DE43A6D3F0161BE49C64C0D38A050447B34FBCB60073543CFB`.
- Master messaging protocol SHA-256 `0C1F9690E33C02865AF0ADB4E396D277CD3A41F0AEC2FD5B2FB7692F1F59B97A`.

The focused Master proxy tests and the full clean test suites passed. The final pair also passed the disposable runtime test: the unfragmented handshake and splits at 1, 2, 4, and 20 bytes all remained connected with Bungee forwarding enabled. Logs: `%TEMP%\multipaper-shared-worlduuid-failclosed-blocks-20261025\proxy-handshake-fragmentation-20261025-165857`. The disposable fixture Master was restored to its original SHA-256 `80388426E4C9A92707A9B24B9DF9C853CB21096FA4DAF3EB9A7DAF8218D082EE`; no production or Crafty systems were accessed.

## Remaining release gates

This closes only the fragmented proxy handshake defect. Authenticated Java online-mode gameplay, real Bedrock/Floodgate identity and reconnect, legitimate-client GrimAC/ViaBackwards behavior, and intended-hardware capacity remain unverified. Automated Survival/farm/redstone mechanics certification also remains incomplete. Keep stable publication closed until critical release gates pass.
