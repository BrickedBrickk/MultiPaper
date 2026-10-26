# Published RC3 versus current-tip Ender Chest synchronization — 2026-10-25

## Test setup

The same disposable test runner, plugin JAR set, direct two-backend topology, and Geyser Standalone process were used on both artifact pairs. No RC3 release asset was changed.

- RC3 Paperclip: `22B1EE9737A98BA85A52AE09B6B9C6EB890331F9B7A9443BBD3CDEA9357C6460`
- RC3 Master: `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`
- Current-tip Paperclip: `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`
- Current-tip Master: `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`
- Both backends ran Floodgate 2.2.5 build 141, ViaVersion/ViaBackwards 5.12.1, InvSee++ 0.31.22 and GrimAC 2.3.74. Geyser Standalone 2.12.0 build 1270 ran at the same time. Exact plugin hashes are in `current-tip-rc4-validation-20261025.md`.
- Mineflayer 4.39.0 synthetic ViaBackwards 26.1 clients connected directly to different backends. The target opened its own Ender Chest and deposited six emeralds. An operator on the other backend read and edited the target via `/endersee`. RCON queried owner and remote mirrors plus both player inventories after each operation.

## Results

**Published RC3 fails the remote-mirror synchronization assertion.** After the target deposited six emeralds, RC3's owner backend reported six in the target's Ender Chest, while the other backend reported zero in the mirrored Ender Chest. The test stopped at that assertion. It did not perform a remote withdrawal, so no item loss or duplication is claimed from this failed run.

**Current-tip RC4 pair passes.** Both backends reported six emeralds after the deposit. A remote withdrawal moved all six to the admin inventory; redeposit restored six in both Ender Chest views; a second withdrawal again moved all six to the admin. At every asserted transition, target chest + admin inventory + target inventory totaled exactly six. No duplication or loss occurred in this sequence.

- RC3 result and logs: `%TEMP%\multipaper-rc3-enderchest-test-20261025\result.txt` and `logs-20261025-184809\endersee-probe.log`.
- Current-tip result and logs: `%TEMP%\multipaper-current-tip-enderchest-fixed-2265-20261025\result.txt` and `logs-20261025-184558\endersee-probe.log`.
- Runner: `scripts/Test-CurrentTipInvSeeEnderChest.ps1`, which pins both exact artifact hashes, prepares fresh marked data under `%TEMP%`, keeps RCON loopback-only, checks both backends enable all five plugins, runs Geyser concurrently, saves/stops the disposable backends and retains logs.

## Product fix and release implication

The fixed source adds a `PlayerEnderChestContainer.setChanged()` hook in persistent Paperweight patch `multipaper-server/minecraft-patches/sources/0076-MultiPaper-sync-enderchest-container-changes.patch`. `MultiPaperEnderChestHandler` broadcasts changes from remote player mirrors while suppressing echo during inbound inventory application. The main fix is commit `e41e53c`; follow-up `0e15bd8` compares ItemStacks including components to avoid missing repeat edits.

This is a direct exact-pair RC3/current-tip compatibility differential under a reproducible synthetic scenario. RC3 remains untouched; a fixed RC4 build is required for this behavior. Real authenticated account operation, crash-time inventory writes, and broader legitimate-client gameplay remain separate open release gates.
