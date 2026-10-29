# RC5 source commit and owner-fork publication — 2026-10-29

## Commit and destination

- Product/source/test commit: `a4e196bcccd40a3d26ae59dc18ba21f8efb8c3d0` (`Fence cross-backend inventory and chunk synchronization`). It contains the 39 reviewed staged files covering Master player-session fences, Ender Chest revisions, chunk unload persistence fencing, gamerule sync, the remote-inventory fail-closed gate, and focused tests.
- Documentation/checkpoint commit: `2d4837454736c9ee60712c50ca8c7b22cb78c43a` (`Record RC5 source commit and verification limits`).
- The verified owner-controlled remote is `fork` = `https://github.com/BrickedBrickk/MultiPaper.git`. Before the source push, its feature branch was exactly `8c8b5b5544590bf98b13db5e9e5e22355984fc91`, equal to local HEAD; both commits were pushed fast-forward. Final `git ls-remote fork refs/heads/feature/port-26.3` returned `2d4837454736c9ee60712c50ca8c7b22cb78c43a`. `origin` remains `https://github.com/PaperMC/Paper.git` and was not pushed. No force-push or history rewrite was used.

## Verification and limits

The committed product tree matches the source state used for the reported two sequential clean Java 25 builds. Each build passed **10,785 tests, 0 failures/errors, 88 skipped**, and all nine JAR outputs were byte-identical. Focused inventory/packet tests passed 20/20. The exact artifacts and full logs/hashes are documented in `rc5-ender-chest-revision-fence-20261029.md`; this commit step did not rerun Gradle.

This publishes source to the owner fork only. It is not an RC5 release: remote InvSee++ edits remain denied because no two-owner click transaction, cursor escrow, and crash recovery path exists (RC5-02 P0 `BLOCKED`). Chest handoff tests cover selected schedules but do not attribute or recover live empty chests (RC5-01 P0 `TESTING`). Do not install or publish binaries. The unrelated dirty worktree changes were not included, reset, or deleted; the live Crafty SMP was not accessed.
