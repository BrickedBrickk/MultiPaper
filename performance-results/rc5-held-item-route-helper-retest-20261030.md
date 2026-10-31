# RC5 held-item equipment route helper retest — 2026-10-30

## Isolated focused result

Ran `:multipaper-server:test --tests puregero.multipaper.ExternalPlayerEquipmentRoutingTestSuite` using Temurin Java 25.0.3.9 / Gradle 9.8 in the prepared disposable checkout `E:\multipaper-tests\rc5-paperweight-e-volume-3bc6f46-20261030`, detached at base `3bc6f46f3c6dc006540b5dc3b74b7033a35ccdb2`. The two production classes, focused test classes, and selected-slot patch matched the main worktree byte-for-byte. Generated `ServerGamePacketListenerImpl.java` in the fixture contains the selected-slot hook.

Result: **2/2 passed, 0 failures, 0 errors, 0 skipped**. JUnit XML SHA-256 `21E611EF1F99475D1FB8565DFAFAB9BDFC3FF559AB6BA2374CF2C7E09D40B7E8`. `ExternalPlayer.java` SHA-256 `9A0763B8FA150FC1259338853F4F26B1B9B4E8FB747959D186497839CBE74F37`; `MultiPaperPlayerHandler.java` `5F279AEA60427CA2E0ACB9AFABCB9F9431411B21B32E1D6346ADA50C514CEA8F`; `PlayerActionPacket.java` `FAD6C519490EACA8CDD06E1A9C9E6B7718A67A9524E58D0AB44EEF038FDF2160`; generated listener source `49C3B95261B3BF8180AF72972DD2352CD639E75BD98A6BEFBC702DDC3E9AE540`.

## Failed main-worktree setup attempt

The equivalent task in the main worktree stopped before compilation at `:multipaper-server:applyPaperMinecraftResourcePatches` with `git reset --hard upstream/main` exit 128. Inspection confirmed `paper-server`, `paper-api`, `purpur-server`, and `purpur-api` nested checkouts all remained clean on their preexisting `main` branches; the root repository and existing uncommitted changes were not reset. In this checkout, the Purpur filtered repos do not expose `refs/remotes/upstream/main`, whereas the Paper remotes do. Treat this as a local Paperweight setup failure, not a product test failure. The prepared E: fixture completed the required patch-generation task and ran the test.

## Scope

These two tests verify local tracker routing and null-tracker tolerance only. The earlier protocol-correct two-client selected-slot runtime result remains the behavior evidence; this helper suite does not replace it or cover reconnect, ownership transfer, other equipment slots, client GUI rendering, or item conservation. RC5-07 remains `TESTING`; RC5-02 remains P0 `BLOCKED`. No live SMP/Crafty service, player, or world was accessed.

## Full backend test suite follow-up

The same isolated E: fixture then ran the complete `:multipaper-server:test` task on Java 25.0.3.9 / Gradle 9.8. Patch generation completed; the generated listener source hash was `49C3B95261B3BF8180AF72972DD2352CD639E75BD98A6BEFBC702DDC3E9AE540` and contained the selected-slot hook. The full module suite passed **10,228 tests: 10,142 passed, 86 skipped, 0 failures, 0 errors**. The equipment route tests are included in that suite; their JUnit XML SHA-256 is `D9C5D05169B50688723F07D027867F2D1C3A57D8B875FDDA0B01888FC3C74C4C`. Sorted 145-file test XML digest manifest SHA-256: `2774A64E2FAB894FA1245666E47BD891CEF9DBA5F5608F1DB04DA146C698759B`; manifest is [`rc5-held-item-current-server-suite-xml-sha256-20261030.csv`](rc5-held-item-current-server-suite-xml-sha256-20261030.csv).

The full task validates the current selected-slot source and generated hook against the 26.3 server tests in this prepared source-overlay fixture. It is one isolated fixture run, not two clean independent builds or a new runtime/client test. Keep RC5-07 `TESTING` pending reconnect/ownership-transfer, broader slot, and actual client-render verification. It does not change RC5-02's P0 `BLOCKED` item-conservation status.
