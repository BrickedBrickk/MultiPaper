# RC5 reproducible build pair — 2026-10-30

## Scope

Two fresh detached clean worktrees were built independently from the same source commit, `3bc6f46f3c6dc006540b5dc3b74b7033a35ccdb2` (`feature/port-26.3` source under test). Both used Temurin Java 25.0.3.9 and Gradle 9.8, with no build cache, configuration cache, or daemon and one worker. No source files were changed between passes.

- Pass 1: `E:\multipaper-tests\rc5-reproducible-build-pass1-3bc6f46-20261030`; `applyAllPatches` 17m20s, Master/Paperclip tasks 3m02s.
- Pass 2: `F:\multipaper-tests\rc5-reproducible-build-pass2-3bc6f46-20261030`; `applyAllPatches` 13m48s, Master/Paperclip tasks 3m27s.
- Both patch-generation and artifact tasks succeeded. Both standalone Master and Paperclip JARs were generated from the same exact candidate source.

## Artifact comparison

| Artifact | Bytes | SHA-256 (both passes) |
|---|---:|---|
| `multipaper-master-3.0.0.jar` | 19,216,550 | `86F694BA1882E2D08DC841ECE62EA11B105663DAADEF3D7C1C8DF52B1531BE19` |
| `multipaper-paperclip-26.3-RC5.jar` | 64,352,019 | `464FCE20DEE5A7734666A573BA207F94E4D3E6AD7903C6BEF02A648CB32CD4F7` |
| `multipaper-bundler-26.3-RC5.jar` | 107,662,634 | `F667121A5CBD5A35FC2CB01F7BFBEB5AF9F2D1011896D5BBFCCA0A480DA19CA9` |
| `multipaper-server-26.3-RC5.jar` | 29,317,539 | `86FEB0DF8A712847A0CAE23BAD421474A3580961593868613F3C61DCBF09A767` |

## Isolated launcher smoke

A copy of the pass-2 Paperclip was launched with Java 25 in a fresh marked disposable directory using `--version`. It downloaded/applied the bundled patches and printed `26.3-DEV-3bc6f46`; exit code 0. The disposable directory contained no world, server properties, plugins, or secrets. The process exited and no listener remained. This is a packaging/launcher smoke only, not a server startup, login, plugin-stack, gameplay, or Crafty test.

## Test linkage and limits

The full serial Master, messaging protocol, and MultiPaper server test run was performed against the exact source commit above: 10,337 total, 10,251 passed, 86 skipped, 0 failures/errors. Focused boat/entity-NBT/inventory-authority/gamerule regressions passed 22/22. Full report and XML digest manifest: `rc5-evolume-clean-checkout-full-suite-20261030.md` and `rc5-evolume-test-xml-sha256-20261030.csv`.

This establishes reproducible build bytes and a successful Paperclip version smoke for that source commit. It does not establish release qualification. The current live chest-loss cause/recovery is unknown, cross-owner InvSee++ item conservation is blocked, and exact six-component authenticated Java/Bedrock, old-client compatibility, two-PC capacity, and two-week operation remain open. The pair is not authorized for installation or publication. No live system, world, or player data was accessed.

## Build logs

- Pass 1 patch generation: `E:\multipaper-tests\rc5-reproducible-build-pass1-3bc6f46-20261030\rc5-build-pass1-applyallpatches-20261030.log`
- Pass 1 artifacts: `E:\multipaper-tests\rc5-reproducible-build-pass1-3bc6f46-20261030\rc5-build-pass1-artifacts-20261030.log`
- Pass 2 patch generation: `F:\multipaper-tests\rc5-reproducible-build-pass2-3bc6f46-20261030\rc5-build-pass2-applyallpatches-20261030.log`
- Pass 2 artifacts: `F:\multipaper-tests\rc5-reproducible-build-pass2-3bc6f46-20261030\rc5-build-pass2-artifacts-20261030.log`
