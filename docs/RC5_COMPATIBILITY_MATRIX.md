# MultiPaper 26.3 RC5 component compatibility and setup matrix

**Release state: BLOCKED / DO NOT INSTALL.** InvSee++ cross-backend edits still have a reproduced duplication path; the current remote-edit guard blocks that feature rather than fixing it. Official metadata was rechecked on 2026-10-29: current stable candidates are Geyser 2.12.1 build 1276, Floodgate 2.2.5 build 141, ViaVersion/ViaBackwards 5.12.2, InvSee++ 0.31.22, and GrimAC 2.3.74. GrimAC 2.3.75-1299a2a is available as an alpha, not the latest release, and is not selected by default. The current six-component stack has not been rerun against RC5. Keep the SMP on RC4 until P0 integrity and exact-stack/runtime gates pass and the owner schedules a manual maintenance window.

## RC5 candidate artifacts

| Component | Candidate version | SHA-256 | RC5 evidence |
|---|---|---|---|
| Master | `multipaper-master-3.0.0.jar` | `1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E` | Two sequential clean Java 25 builds match; Master tests pass in both. The exact packaged Paperclip/Master pair passed the 273-read delayed stale-owner chest schedule in both ownership directions; see `performance-results/rc5-packaged-pair-chest-race-20261029.md`. |
| Backend Paperclip | `multipaper-paperclip-26.3-RC5.jar` | `8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692` | Two sequential clean Java 25 builds match; all configured tests pass. The exact pair passed selected 273-read chest handoff/restart schedules. Ender Chest revision updates have not been deliberately reordered in a paired runtime. |

Both backends must use the same Paperclip bytes and Master must come from that same build set. The API artifact from this set is `57A422D7A3EB95DD89551BF0EBB2B6FDDD56CB35081E9D5C43D7EA19CEF1DC57`. The two-week evaluation has not been authorized; the InvSee++ P0 and exact six-component/runtime gates still block publication or installation.

The current Paperclip includes monotonic Ender Chest slot-update revisions, before-images, and stale/replayed update rejection. Focused packet/unit regressions pass 20/20, including serialized packet replay ordering, but this Ender Chest delta has not had a paired runtime test. Revisionless Ender Chest updates are rejected, so do not mix this build with RC4 peers. The new server, Paperclip and bundler JAR hashes and clean-build evidence are in `performance-results/rc5-ender-chest-revision-fence-20261029.md`; older paired gameplay reports must not be attributed to this rebuilt candidate, except for explicitly hash-matched tests such as the new chest race report.

Earlier zero-warmup/zero-write-delay chest race schedules passed in both ownership directions on Paperclip `99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85` plus this Master; these 2026-10-28 schedules are supplemental and are not evidence for Paperclip `8C281...`. Reports: `performance-results/rc5-chest-zero-delay-race-20261028.md` and `performance-results/rc5-chest-zero-delay-reverse-race-20261028.md`.

The earlier combined Crafty1-scale schedules on Paperclip `99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85` plus this Master ran 273 reads, rejected the delayed former-owner snapshot, and retained exact chest contents. This older-candidate evidence is distinct from the exact packaged-pair follow-up below. Neither diagnoses or recovers live chest loss or covers all unload/crash windows.

The exact pair passed a fresh disposable gamerule runtime: settings made on either backend converged on its peer and persisted through graceful backend and Master restarts. A follow-up set `players_sleeping_percentage=50` on Backend B, observed it on Backend A, and confirmed persistence on both backends after graceful backend/Master restarts. This covers only the gamerule value, not players sleeping, quorum, wake, or night skipping. Other global commands/settings remain open. Evidence: `performance-results/rc5-gamerules-cleanpair-runtime-20261028.md` and `performance-results/rc5-sleep-threshold-cleanpair-runtime-20261028.md`.

## Current official component availability — checked 2026-10-29

This table separates stable release pins from prerelease availability. They are **not yet tested as one stack on the current RC5 pair**. The pre-existing exact-stack runtime results below remain tied to the older listed artifacts. Do not silently replace tested pins in an installation; rerun the exact disposable stack first.

| Component | Current official artifact / channel | Verified local artifact hash | Source and scope |
|---|---|---|---|
| Geyser Standalone | 2.12.1 build 1276 | SHA-256 `1DD1DE0D56AD5F50D31FEFF2E18F6F71DE50BC142853131D473BFD71D9E3809F` | Official Geyser Download API reported build 1276 on 2026-10-28. Supports NetherNet, but no Bedrock login/route tested with this artifact. |
| Floodgate Spigot | 2.2.5 build 141 | SHA-256 `21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E` | Still latest in the official Download API check; exact tested bytes already present locally. |
| ViaVersion | 5.12.2 | SHA-256 `24E4876FEFE73E08A4C87CD6C457EE0916E62DD69656631CAF41E26F909D504B` | Official signed GitHub release; includes 26.2→26.3 block-breaking and item-visibility fixes. |
| ViaBackwards | 5.12.2 | SHA-256 `9A148A80EA22074F4BD1626CDBE254A4CD998BF44E7CEAFF4E285A1B6B35DA42` | Official signed GitHub release; includes 26.3→26.2 block-state and map creative-backup fixes. |
| InvSee++ | 0.31.22 | SHA-256 `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9` | Modrinth API still listed 0.31.22 latest; it remains the tested version and has the known MultiPaper duplication repro. |
| GrimAC Bukkit | Latest release 2.3.74; newer 2.3.75-1299a2a is alpha | Release SHA-256 `3AC71C0B6EF8AB586C1DD3AB4C0B3254394EF39D048E4985EFB2898B9FEF628C`; alpha SHA-256 `080E01C5545695B7E755F705D73DA49C5EAE9B86D67F1425F88F2C7B414F8DA9`, official SHA-512 `C0D42ABCFFC0698FC6A66D7063A2B2892BFC4307031B8D22BA14BF449F528C212F8F987E1DD0E6F0CE9F17A73307770373F6A0A8722573973C447F4789328E4B` | Keep stable 2.3.74 as the conservative pin. The alpha metadata lists Bukkit/Paper and Minecraft 26.3 but is not loaded on RC5. Recheck source and channel details: `performance-results/rc5-official-component-availability-20261029.md`. |

Official version/channel metadata and artifact checks are recorded in `performance-results/rc5-official-component-availability-20261029.md` and the 2026-10-28 artifact-availability report. New artifacts that were downloaded remain under `D:\multipaper-tests\system-temp\rc5-official-components-20261028`; hashes were checked locally. No plugin was installed into a live server, and neither the newer stable pins nor the GrimAC alpha were started together on RC5.

## Six components — prior exact-stack evidence

| Component | Exact official artifact tested previously | Installation/setup | Evidence status and remaining limit |
|---|---|---|---|
| Geyser Standalone | 2.12.0 build 1270; SHA-256 `AE06830C47719E29AD34FBFB4692597B60FE22460E4CCB6BA1AB113143102F04` | Separate process on PC1. Bind Bedrock listener on the intended PC1 interface/UDP port. Set Java remote to the PC1 Master proxy and Floodgate authentication. | Official hash and readiness/loopback UDP startup verified on the RC4 candidate. No real authenticated Bedrock account, external route, Floodgate identity or reconnect has been verified. RC5 exact-stack smoke pending. |
| Floodgate Spigot | 2.2.5 build 141; SHA-256 `21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E` | Install on both backends. Keep one identical private key in Geyser and both backend configs; never package/export it. | Exact official hash and plugin load on both RC4 candidate backends verified. No authenticated Bedrock/Floodgate identity through this Master topology has been verified. RC5 exact-stack smoke pending. |
| ViaVersion | 5.12.1; SHA-256 `B8033807E7182C35003A45962352DD642C1E91880CA281DA3A43CB6F481A95A2` | Install on both backends. | Official hash and load on both RC4 candidate backends verified; synthetic current-protocol login/chunk/command smoke passed. RC5 exact-stack smoke pending. |
| ViaBackwards | 5.12.1; SHA-256 `29D94D3A26C85C8A327574B2322DB4078992AE457F0173325431A12AAC384911` | Install with the same ViaVersion version on both backends. | Official hash and plugin load verified on the RC4 candidate; a synthetic older-protocol translation smoke passed. No legitimate older-client survival, movement, teleport, vehicle or reconnect test. GrimAC reports unsupported older-client vehicle behavior for this combination. RC5 exact-stack smoke pending. |
| InvSee++ | 0.31.22; SHA-256 `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9` | Install on both backends only if operators accept that remote cross-backend menu edits are gated off by this RC5 candidate until the transaction fix passes. Do not rely on stale replacement resync as conservation. | Official hash, load, permissions and historical menu probes verified on RC4. Current exact-RC4 protocol-valid test reproduced 16→32 diamond duplication under concurrent edits; stock Paper control retained 16. On the latest exact RC5 pair, single remote main and Ender Chest clicks were denied with matching client resync; one simultaneous remote/owner main click retained exactly 16 aggregate items. These are fail-closed containment results only; no cross-owner transaction/fix exists. **P0 BLOCKED.** See `performance-results/rc5-current-pair-invsee-containment-20261028.md`.
| GrimAC | 2.3.74; SHA-256 `3AC71C0B6EF8AB586C1DD3AB4C0B3254394EF39D048E4985EFB2898B9FEF628C` | Install on both backends. This build shades PacketEvents; do not add another PacketEvents JAR. Keep local databases/configs per backend. | Official hash and load verified on RC4; synthetic invalid-movement detection was observed. Legitimate-client false-positive and Bedrock behavior remain unverified. RC5 exact-stack smoke pending. |

The older tested hashes and official-source checks are recorded in `performance-results/official-plugin-artifact-validation-20261026.md`. That report used an earlier candidate and earlier Via/Geyser/Grim versions; it does not certify the current RC5 combination or newly available artifacts. Re-run six-component startup against the exact pair and selected current pins before any beta.

## Two-PC network topology

- **PC1:** MultiPaper Master/player proxy + Backend 1 (private game port) + Geyser Standalone (NetherNet TCP signaling and UDP gameplay listeners).
- **PC2:** Backend 2 only, on a private game port.
- Connect both backends to Master over the private LAN; use Tailscale for administration when needed. Keep Master coordination private to the backend hosts.
- Configure one Playit Java **TCP** tunnel to the Master player proxy. NetherNet requires Geyser signaling over **TCP** and gameplay over **UDP**; the older one-UDP-only Bedrock route is insufficient. Geyser's current guide describes a TCP+UDP listener/tunnel; Playit's support note says a combined TCP/UDP tunnel is currently Premium. Verify that the account's supported tunnel maps both protocols to Geyser before use. Any external signaling alternative must be selected, configured, and tested separately; it is not part of this package. Geyser's Java remote is the Master proxy. Do not expose either backend game port, Master coordination or RCON through Playit.
- Keep online-mode/authentication enabled as designed; do not bypass auth to make a test pass. Restrict RCON to loopback and a protected secret source; never package RCON credentials or Floodgate keys.

The PC1/PC2 example configs are templates only. Keep backend names, ports, JVM settings, plugin data and credentials separate. Follow `monitoring/MANUAL-CRAFTY-TOPOLOGY.md`, `monitoring/NETWORK-ROUTES.md`, and `docs/RC5_MANUAL_UPDATE_PLAN.md` after release gates pass and a maintenance window is manually scheduled.

## Required RC5 manual gates

1. Close the P0 InvSee++ transaction/conservation blocker and rerun click, cursor, simultaneous-owner and restart recovery tests on exact versioned RC5 artifacts.
2. Select and hash-pin the current six artifacts; rerun startup on both exact RC5 backends and Geyser; verify plugin hashes/configs without exposing keys. Include NetherNet TCP signaling and UDP gameplay listeners in the route test.
3. Test authenticated Java login and authenticated Bedrock/Floodgate identity, reconnect, permission and handoff with real allowed test accounts.
4. Test ViaBackwards using legitimate supported older clients and record GrimAC false positives/vehicle limits.
5. Verify the Java TCP Playit route and both Bedrock NetherNet TCP signaling plus UDP gameplay (or separately approved external signaling) while backend ports remain private. Keep 5–20-player capacity unproven until measured on the actual PCs.
6. Only after these gates and owner approval should the two-week, allowlisted test plan be activated.

## Exact-current 273-read chest race follow-up — 2026-10-29

The exact packaged Paperclip/Master pair above was exercised in two fresh Java 25 fixtures. Each direction ran 273 production no-holder reads during a delayed former-owner save, performed ownership handoff, and checked exact single/double chest contents after new-owner backend restart and graceful Master restart. Master rejected the stale write in both directions; all fixture services stopped gracefully and ports closed. Result/log hashes: `performance-results/rc5-packaged-pair-chest-race-20261029.md`. This verifies these synthetic schedules only; it does not classify/recover the real player chest-loss report or cover every crash/save interleaving. RC5-01 stays P0 TESTING.
