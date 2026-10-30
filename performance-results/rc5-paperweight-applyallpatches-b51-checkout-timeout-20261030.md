# Exact-current Paperweight patch-generation attempt — 2026-10-30

## Fixture and setup

Created detached worktree `D:\multipaper-tests\worktrees\rc5-paperweight-checkout-diagnostic-b51-20261030` at exact project HEAD `b51e61a6d13cf9048230ece8dc31c7d6f30841a4`. Temurin 25.0.3.9 / Gradle 9.8 were explicit. A read-only Gradle `help` probe confirmed `checkoutPurpurRepo` output is the fixture's ignored `.gradle/caches/paperweight/upstreams/purpur`. The isolated `checkoutPurpurRepo` task succeeded in 3m05s, creating a nested Git repository at configured Purpur ref `004981010c612cedad5aaf0c531237594abef307`; fixture HEAD remained b51e61a.

## Patch-generation result

Ran one serialized `applyAllPatches --project-cache-dir D:\multipaper-tests\project-caches\rc5-paperweight-current-c185-known-good-20261030 --no-build-cache --no-daemon --max-workers=1 --console=plain --info` task in that disposable fixture. It completed the first Purpur setup and entered nested `:purpur:checkoutPaperRepo`. That checkout produced a Git pack of 61,817,580 bytes and pack index of 6,956,580 bytes, then remained at the checkout task with no further Gradle log lines for 15 minutes. The task was interrupted at that bound. A zero-byte nested `.git/index.lock` remains in the partial Paper checkout; the partial cache and lock are retained unchanged as evidence and must not be treated as a complete repository or reused until inspected.

Combined Gradle log SHA-256: `B1E1744F539D422DA8775C7D8FB599C7D0AB049C3CFFD9CF059FC98164495A7F`. No tests ran and no compilation or product JAR was produced. Exact-current fixture HEAD was rechecked as `b51e61a6d13cf9048230ece8dc31c7d6f30841a4`; its tracked status remained clean. The specific Gradle and Git process IDs started for this attempt were absent after interruption. No server, game port, Crafty host, world, or live player data was accessed.

## Interpretation and next step

This is a bounded Paperweight setup/patch-generation interruption, not a source regression or test result. It provides evidence that the current project's top-level Purpur checkout is isolated correctly. The earlier C185 run's root worktree movement during `checkoutPurpurRepo` remains unexplained; do not attribute it solely to a missing nested `.git` directory. For any further retry, use a new exact-HEAD disposable fixture, preserve this partial Paper cache and stale lock, and bound the nested Paper checkout separately. RC5-01 remains P0 `TESTING`; no release gate is cleared.
