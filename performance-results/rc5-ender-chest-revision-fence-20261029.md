# RC5 Ender Chest mirror revision fence and reproducible builds — 2026-10-29

## Change and observed boundary

Added a monotonically increasing, per-real-player-session revision to Ender Chest mirror updates. Slot deltas carry the old `Replacing` image as well as the new stack. Receiving mirrors reject missing, duplicate, or lower revisions before mutating Ender Chest slots; each `ExternalPlayer` mirror keeps its accepted revision state for that session. The owner-source and player-session checks remain in force. The remote InvSee++ click gate remains closed.

This addresses a concrete source-level ordering gap identified in `performance-results/rc5-invsee-editor-provenance-source-trace-20261028.md`: Ender Chest updates previously had no before-image/revision and could unconditionally overwrite a newer mirror if an older update arrived later. It does **not** demonstrate that this happened in RC4, explain the logged three stale inventory replacement rejections, or fix the reproduced 16→32 InvSee++ duplication. The expected/lower/replay behavior is covered by focused regressions, but no paired runtime deliberately reordered ender updates was run. Mixed-version peers lacking revision metadata are rejected by the RC5 receiver; do not mix RC4 and RC5 backends.

## Tests and build provenance

- Temurin Java 25.0.3+9 / Gradle 9.8.0, offline, no build cache, no daemon, one worker. Two sequential `clean build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar` runs passed: **10,785 tests across 192 JUnit XML reports, 0 failures/errors, 88 skipped** in each run.
- The final focused inventory/packet suites passed **20/20** (9 inventory-handler tests, 1 revision-state test, 10 packet-handler tests; zero failures/errors/skips). The packet-level regression serializes and decodes revision 9 (diamond x8→x9), then revision 8 (x7→x8), and asserts only the first update mutates the mirror. The revision-state regression rejects duplicate, stale and unversioned revisions. Latest focused XML hashes: inventory handler `2711628A3DF5C3C9E46A0A89D240C07A7B4311C6815B386831F6E6169FE9465B`; revision helper `FE0F2B8E9851CEE32358E35EBC9B18EFB42B90F01043EDE92918F0F2457AA4C6`; packet handler `F1EAB380C1A6F6F387ADE83C55DA4DB70165A1A454623739B52E45F8BF335515`.
- All **9 JARs** are byte-identical between the two clean runs. Logs: pass 1 SHA-256 `77EA25BF84D5AC316FD599B05F87426399C2529DD7981D6DB615A757D95692C7`; pass 2 `654806BC311D070E5FB91A146B8B204CEDC7CF959990D4BCE7CB505E90C2C3F8`. Both say BUILD SUCCESSFUL (10m08s / 9m37s). Full logs, JARs, and XML are retained under `D:\multipaper-tests\system-temp\rc5-ender-revisions-clean-builds-20261029\pass1` and `pass2`.

| Artifact | SHA-256 |
|---|---|
| `multipaper-api-26.3-RC5.jar` | `57A422D7A3EB95DD89551BF0EBB2B6FDDD56CB35081E9D5C43D7EA19CEF1DC57` |
| `multipaper-api-26.3-RC5-sources.jar` | `42CCC7F84218F45656BB42879FB0B9BAF6051D991B4C910BAE627C5AC88FCC9B` |
| `multipaper-api-26.3-RC5-javadoc.jar` | `BC8CF72E68CDF59E5D21CAB3F91A3B81A31DC237F02BEDF7E6CE000714E45B60` |
| `multipaper-master-3.0.0.jar` | `1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E` |
| `multipaper-mastermessagingprotocol-3.0.0-26.3.jar` | `A5F55D3DADCAB6997EAE6C4FA71EDC10357352D6DFBADD9FB36F3171D9645B22` |
| `multipaper-server-26.3-RC5.jar` | `9C15E309F1B2EF66071D5A00D39DFF779D235FFF2286923907D358818024E6AB` |
| `multipaper-paperclip-26.3-RC5.jar` | `8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692` |
| `multipaper-bundler-26.3-RC5.jar` | `7794EEA5CFDE2FF63F9251ED30548552BA7811B355714FBDED0BF54A0D7BCC7A` |
| `paper-checkstyle-26.3-RC5.jar` | `ECDCFE07327155CEB62FA8F5FBA903F4BB1D6DE5BF94F57B0695EDBC773C666B` |

`jar tf` and `javap` confirmed the built server artifact includes `EnderChestSyncRevision`, the sender/receiver classes and their revision methods.

## Status and limitations

The new artifact is compile/test verified and reproducible, but has no paired runtime check for revisioned Ender Chest replication, no exact-plugin transaction path, and no end-to-end item-conservation claim. RC5-02 remains P0 BLOCKED; remote edits are still disabled. The local release staging bundle is stale relative to this Paperclip and must not be treated as installable. No live SMP/Crafty process or data was accessed or changed.

The final focused run included wire encoding/decoding in the packet replay regression. This test-only refinement does not change production source or the nine artifact hashes from the two clean matching builds above. No paired runtime was run.
