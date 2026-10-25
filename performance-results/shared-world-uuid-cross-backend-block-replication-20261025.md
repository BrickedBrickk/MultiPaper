# Shared world UUID and block replication regression — 2026-10-25

## Defect and fix

The disposable two-backend runtime showed that each backend gave the same configured world a different `World#getUID()`. `SendUpdatePacket` addresses the world by UUID; the receiver could not resolve the other backend's UUID and dropped updates. Ordinary block changes and piston movement therefore stayed local to the owner.

The durable source fix uses Master-backed `ReadUidMessage` for every world load:

- New `multipaper-server/minecraft-patches/sources/0077-MultiPaper-share-world-uuid.patch` uses the shared UUID for `ServerLevel`, `Level.uuid`, and `PaperWorldMetadata`.
- Updated persistent Paperweight patches `0010-MultiPaper-modern-port-MinecraftServer.patch` and `0018-MultiPaper-modern-port-ServerLevel.patch` to use the single-argument UUID lookup too.
- `MultiPaper.getWorldUid` now fails visibly if Master returns an invalid UUID or the read fails. It no longer caches a backend-local fallback, which could silently recreate split identities during a Master connection problem.
- `multipaper-server/build.gradle.kts.patch` applies the new patch after Minecraft source patches in the compile chain. Clean builds regenerate the change from the persistent patches.

## Validation

- Java 25.0.3 / Gradle 9.8 focused compile passed. Master UUID sharing and the new `MasterProcessRestartPersistenceTest.worldUidSurvivesGracefulAndCrashMasterRestarts()` passed; that test verifies byte-identical UUIDs across graceful restart and forced termination/recovery after a successful read response.
- Final clean full build: `clean build createPaperclipJar createBundlerJar :multipaper-master:shadowJar --no-build-cache --no-daemon --max-workers=2 --console=plain`; succeeded in 6m28s with 96 actionable tasks. 188 suites; 10,744 tests; 0 failures/errors; 88 skipped. The Master UUID restart regression is included in these totals.
- Exact-pair disposable runtime: `%TEMP%\multipaper-shared-worlduuid-failclosed-blocks-20261025`, world `farm28a0a57_1025_161758`. Both backends reported UUID `c6188c6d-9e60-462f-920a-7b90484b6ba0`; one backend owned the chunk and the other subscribed with an open peer connection. Three alternating observer/piston cycles moved and retracted the cargo. The peer saw both an ordinary gold-block update and the piston-moved stone block.
- On these exact artifacts, five plugins loaded on both backends, Geyser Standalone started, and a synthetic protocol-777 client received chunks and successful `/list`, `/tps`, `/mspt`, `/slist`, `/mpdebug`, and `/version` responses. Logs: `%TEMP%\multipaper-shared-worlduuid-failclosed-blocks-20261025\six-component-smoke-20261025-161946`.
- Exact-pair automated portal check passed: the 26.3 test client activated a vanilla Nether portal, transferred to `minecraft:the_nether`, and received the respawn packet. Logs: `%TEMP%\multipaper-shared-worlduuid-failclosed-blocks-20261025\portal-activation-20261025-162035`.
- These synthetic fixtures do not certify authenticated human Java gameplay or Bedrock/Floodgate login.

## Rebuilt artifacts

Built in isolated worktree `%TEMP%\multipaper-26.3-shared-worlduuid-28a0a57-20261025`:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Paperclip | 64,332,733 | `DC4A57CF66E022AEDD4FD592F788F0D349E535C63527C14309976B9D859DF15F` |
| Bundler | 107,645,238 | `CA9781810B47DF8A55BDCFC18F6C112F68485E55DC2F6871DDB6134A5C167B3B` |
| Server | 29,306,861 | `0B034628D95817DE43A6D3F0161BE49C64C0D38A050447B34FBCB60073543CFB` |
| Master | 19,181,084 | `80388426E4C9A92707A9B24B9DF9C853CB21096FA4DAF3EB9A7DAF8218D082EE` |
| Master messaging protocol | 110,381 | `0C1F9690E33C02865AF0ADB4E396D277CD3A41F0AEC2FD5B2FB7692F1F59B97A` |

The fail-closed source set passed two sequential clean full builds. Both produced all five artifacts byte-for-byte identically; exact-hash runtime validations above passed. Reproducibility is verified with the local dependency cache; empty-cache dependency bootstrap remains uncertified.

## Remaining release blockers

Authenticated human Java gameplay (user said they will test later), real Bedrock/Floodgate identity and reconnect, legitimate-client GrimAC/ViaBackwards behavior, and isolated 5/10/20-client capacity on the intended PCs remain open. No Crafty or production services were accessed. RC3 remains untouched; stable publication stays closed.
