# RC5 exact-source Paperweight retry: Mache resource copy stall

Date: 2026-10-30. This is a setup diagnostic in a disposable detached fixture; it is not a product test or source regression.

## Attempt

- Fixture: `D:\multipaper-tests\worktrees\rc5-paperweight-checkout-diagnostic-b51-20261030`
- Fixture HEAD before and after: `b51e61a6d13cf9048230ece8dc31c7d6f30841a4`; tracked worktree status was clean.
- Project branch remained at `8cb404c246dfe0525fd1440dd9b3c6a3c457053e`.
- Java: Temurin 25.0.3.9; Gradle 9.8.
- The retry resumed the preserved Paperweight upstream cache after the interrupted index lock had been moved to `D:\multipaper-tests\rc5-paperweight-evidence-20261030\paper-index.lock.interrupted-20261030`.
- Purpur checkout completed; patch stages completed through `:purpur:applyPurpurPatches`, Paper API patching (10 patches), and root `:applyAllPatches` startup.
- Nested task `:multipaper-server:setupMacheResources` logged `Copy initial sources...` and remained there until the 60-minute bound. A thread dump shows its worker in `Files.copy`/`CopyMoveHelper.copyToForeignTarget` while closing a `FileChannel` at Paperweight `SetupMinecraftSources.run(SetupMinecraftSources.kt:135)`; the included-build parent was waiting on nested build completion.
- The run was interrupted at the bound. Exact Java PIDs 24800 and 41020 were confirmed absent afterward. No overlapping Gradle task was run.

## Result and evidence

No generated patched Paper/Minecraft source tree was confirmed, no compilation or JUnit tests ran, and no product JAR was produced. The stall does not establish a product defect. It identifies a prolonged resource-copy/close operation in the disposable Paperweight setup path; its underlying filesystem cause is not established.

- Full Gradle log: `D:\multipaper-tests\rc5-paperweight-applyallpatches-b51-resume-20261030.log`, SHA-256 `3513CE92CAF144A166EE0C9CC2BC0E0986438195C59D6A5D821E250DFB7B6439`.
- Current worker thread dump: `D:\multipaper-tests\rc5-paperweight-applyallpatches-b51-resume-thread-current-20261030.txt`, SHA-256 `339A75A69AB5A10A66780027F19C723789DC443E3A823C2061C6401DC438EE94`.
- Earlier nested Paper checkout evidence and archived lock remain at `D:\multipaper-tests\rc5-paperweight-evidence-20261030\` as detailed in `rc5-paperweight-applyallpatches-b51-checkout-timeout-20261030.md`.

RC5 chest integrity remains P0 `TESTING`; InvSee++ conservation remains P0 `BLOCKED`. This diagnostic changes neither status. No live server, world, player data, or Crafty host was accessed or changed.
