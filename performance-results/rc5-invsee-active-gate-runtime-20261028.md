# RC5 InvSee remote-click containment regression — 2026-10-28

## Finding

The earlier exact-RC5 runtime failure was real: one protocol-valid InvSee++ pickup emptied the remote target's 16-diamond inventory. Source/bytecode inspection found why the proposed `0153` guard had no effect: it was not included in the current Paperweight compile inputs, and the compiled `ServerGamePacketListenerImpl.handleContainerClick` had no call to the gate. The older `multipaper-server/minecraft-patches/sources/0022` is also not part of the active patch path in this build configuration.

## Active build fix

Added `multipaper-server/minecraft-patches/sources/0080-MultiPaper-reject-unsafe-remote-inventory-clicks.patch` and an idempotent `applyRemoteInventoryClickGatePatch` task in `multipaper-server/build.gradle.kts`, wired into Java compilation after Minecraft source generation. It inserts `rejectUnsafeRemotePlayerInventoryClick(this.player)` before vanilla click mutation. The generated listener contains that call immediately after packet-thread validation; compiled bytecode contains its invocation at instruction offset 16. The existing handler predicate recognizes the exact InvSee++ public main-inventory marker and denies remote/unresolved targets. This is temporary safety containment; it disables remote edits and does not implement the required cross-owner transaction.

## Reproducible build validation

Two sequential `clean build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar` runs used Temurin Java 25.0.3.9, Gradle 9.8.0, offline dependencies, no build cache, no daemon, and two workers. Both ran the complete project test suite: **10,770 tests, 0 failures, 0 errors, 88 skipped, across 192 JUnit suite files**. The active patch task ran in both passes. Durations/log SHA-256:

| Pass | Duration | Build log SHA-256 |
|---|---:|---|
| 1 | 8m 14s | `FAE221533506EC145E105BB2D396F9C7024AB8953B6F1D0422B746BBE4032C99` |
| 2 | 7m 40s | `EC4C1B94BF48FBB9C33799D7AE3D9020D783B23E910E5B79A5257A2872B72B47` |

All nine JAR outputs matched byte-for-byte:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `multipaper-api-26.3-RC5.jar` | 2,972,398 | `57A422D7A3EB95DD89551BF0EBB2B6FDDD56CB35081E9D5C43D7EA19CEF1DC57` |
| `multipaper-api-26.3-RC5-sources.jar` | 2,130,533 | `42CCC7F84218F45656BB42879FB0B9BAF6051D991B4C910BAE627C5AC88FCC9B` |
| `multipaper-api-26.3-RC5-javadoc.jar` | 22,291,722 | `BC8CF72E68CDF59E5D21CAB3F91A3B81A31DC237F02BEDF7E6CE000714E45B60` |
| `multipaper-master-3.0.0.jar` | 19,201,595 | `BB20D1935AC6A12A4B2BA0907097779BD268D4330FA8460C945354711704200C` |
| `multipaper-mastermessagingprotocol-3.0.0-26.3.jar` | 114,847 | `D789979F8F64E7DED363EBA24F649531F3C505E0F0317CA5C8B64412A5BE7EED` |
| `multipaper-server-26.3-RC5.jar` | 29,310,387 | `4D8BE09818BD205570F9D19D1DAB555F808B64728B69E558557EE885102537CB` |
| `multipaper-paperclip-26.3-RC5.jar` | 64,341,022 | `391D91696B8C6DBBFD1A952FABF15A5F31AA2DB5D129BC742CCC279905438349` |
| `multipaper-bundler-26.3-RC5.jar` | 107,653,155 | `4FC595340A92B5E2904ADE16BBEF8B613A241AD50A85F94489CEBB45D2F55F16` |
| `paper-checkstyle-26.3-RC5.jar` | 22,343 | `ECDCFE07327155CEB62FA8F5FBA903F4BB1D6DE5BF94F57B0695EDBC773C666B` |

JAR copies, manifests, test summaries, and build logs are retained under `C:\Users\elibo\AppData\Local\Temp\rc5-validation-current-upstream-20261028\repro-build-evidence\invsee-gate-active-pass1-jars`, `invsee-gate-active-pass2-jars`, `invsee-gate-active-patch-pass1.log`, and `invsee-gate-active-patch-pass2.log`.

## Exact-plugin runtime

Used the existing `.multipaper-disposable` fixture at `D:\multipaper-tests\system-temp\rc5-invsee-gate-runtime-20261028`, retained its prior JARs in `repro-artifacts\pre-active-gate`, and installed the two-build RC5 Paperclip/Master pair plus the exact InvSee++ 0.31.22 artifact (SHA-256 `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9`). The synthetic target was seeded with exactly 16 diamonds. One protocol-valid remote main-inventory pickup was sent.

**Pass:** the editor received the system message “Editing a player inventory owned by another backend is temporarily disabled for item safety”; the server resent the menu with all 16 diamonds; Backend B RCON read the authoritative target inventory as exactly one 16-diamond stack. The runner saved both backends and shut them down gracefully. Ports 33151–33154 and 33161–33162 are closed; no Java process command line referencing this fixture remained.

Fixture run: `D:\multipaper-tests\system-temp\rc5-invsee-gate-runtime-20261028\rc5-gate-runtime-logs-active-patch-103946`. SHA-256: assertions `58E7A3D1FE2D4838D2C6575843B45ABDB9362C4CF246F3722768006CB8B56CDF`; Backend A log `362AE25CD6202F839C94C66C157C674B3A9D5BA90B4E3A14CBCFA3C008E24B3A`; Backend B log `9B6F78269FA348DB74E7B787DB9C49E2D6A997F07798780C37F9782108278BDB`; probe `43DB82E13E8FAB3F987C4AB1E121C80D775228DF7F57EFCFB251A0789B7CC8BF`; RCON transcript `13B95AD0E87FB712DBCA2419A3C7C54F46ECC7D58BD413BCC5BDD10EC3221519`; runner `15D9C31F9CFA5C4A934CC85D88FB460B49F4A3FCDC99BE9F15CFC8E1EEB41890`; probe source `0F700FAEAFA9CA7909E23B8B101708F27CBB0434E771E9D99F010FECEC70C972`.

The portable-in-repository regression runner is `scripts/Test-RC5InvSeeRemoteClickGate.ps1` (SHA-256 `28198EA09ED237F6AF0D104C2B072611F46F9CE5CD8DAD00FB200587B3D8FED1`) with probe `scripts/test-fixtures/invsee-remote-click-probe/concurrent-viewers-protocol-valid.js` (SHA-256 `0F700FAEAFA9CA7909E23B8B101708F27CBB0434E771E9D99F010FECEC70C972`). It requires a `.multipaper-disposable` fixture and checks artifact/plugin hashes before startup. A second run through this checked-in harness passed at `D:\multipaper-tests\system-temp\rc5-invsee-gate-runtime-20261028\rc5-gate-runtime-logs-reusable-104756`; target again remained exactly 16 diamonds and the safety message was observed. Assertions SHA-256 `58E7A3D1FE2D4838D2C6575843B45ABDB9362C4CF246F3722768006CB8B56CDF`; probe log `6BBE0017C4425BC9407FEBBE36FA182ADB3D3B5583296774B5AA49D16DB367F6`; backend logs `BB4BCD2ABC8EB6FF25587DC1A531BDE4F56A3A71159CCA43EFD6DB9A04C31AE8` / `082C8C0F975AA6F501027040EA14C7DC0F6EDCEB16DE48FD5C47943663E498E7`; RCON `0920D85B7926F938C88C12170ED2E05A7D5132C2123C57E9A6935DCFD5280E91`. The probe does not model a real client's optimistic cursor.

## Limits and status

This proves a single exact-plugin remote click is rejected before mutation and that the target remains intact. The probe client does not model a full Java client's predicted cursor state, so this is not cursor conservation evidence. Shift-click, drag, repeated/randomized clicks, concurrent editors, local-owner editing, reconnect/ownership change, Ender Chest, restart recovery, and compatibility with other menu types remain untested. Remote editing is disabled by this gate; implement and test a durable two-owner transaction before claiming the RC5-02 feature fixed or publishing RC5. Tracker status stays P0 `BLOCKED`.

## Persistent-input rebuild verification — 2026-10-28

The earlier two-build evidence was generated while `applyRemoteInventoryClickGatePatch` existed only in the ignored `multipaper-server/build.gradle.kts`; it is superseded as source-provenance evidence. After moving the task and JavaCompile dependency into tracked `multipaper-server/build.gradle.kts.patch`, I removed the generated script only in the marked disposable C: fixture and regenerated it via successful `applyAllPatches`. The generated script contained both the task and its JavaCompile dependency. Two sequential clean full-project Java 25.0.3.9 / Gradle 9.8.0 offline builds were then run from that persistent input with `--no-build-cache --no-daemon --max-workers=2`; both executed the gate task.

- Pass 1: **10,770 tests, 0 failures, 0 errors, 88 skipped, 192 JUnit XML files**; `BUILD SUCCESSFUL` in 11m30s. Log SHA-256 `B232B2ECAAA1B7CEA1EE9F2ABA73CE5C3910C8C926C30CD9E5934558C31D632F`.
- Pass 2: **10,770 tests, 0 failures, 0 errors, 88 skipped, 192 JUnit XML files**; `BUILD SUCCESSFUL` in 7m40s. Log SHA-256 `EEA5548D478A56B16D19386489E25024BCBF1F56B211F94932DF24DFB0028CB1`.
- All nine JAR outputs matched byte-for-byte across passes. Paperclip SHA-256 `391D91696B8C6DBBFD1A952FABF15A5F31AA2DB5D129BC742CCC279905438349`; Master SHA-256 `BB20D1935AC6A12A4B2BA0907097779BD268D4330FA8460C945354711704200C`; the other seven artifact hashes match those in the artifact manifest copied under `repro-build-evidence/invsee-persistent-pass1-jars` and `invsee-persistent-pass2-jars` in the isolated C: fixture.
- Reusable exact-InvSee runtime evidence already recorded for the same Paperclip/Master hashes verifies one protocol-valid remote click is denied and the target retains exactly 16 diamonds. This is a fail-closed safety gate, not InvSee parity or durable cross-owner inventory conservation. RC5-02 remains P0 `BLOCKED` and the release remains blocked.

The full logs and artifact copies are retained at `C:\Users\elibo\AppData\Local\Temp\rc5-validation-current-upstream-20261028\repro-build-evidence\invsee-persistent-pass1.log`, `invsee-persistent-pass2.log`, `invsee-persistent-pass1-jars`, and `invsee-persistent-pass2-jars`. These builds occurred only in the isolated fixture; no live server/world was accessed.

## Client carried-stack resync assertion — 2026-10-28

Extended the reusable protocol probe and disposable runner to inspect the 26.3 `window_items.carriedItem` field after a denied click. The latest run used the same exact RC5 Paperclip (`391D91696B8C6DBBFD1A952FABF15A5F31AA2DB5D129BC742CCC279905438349`), Master (`BB20D1935AC6A12A4B2BA0907097779BD268D4330FA8460C945354711704200C`), and InvSee++ 0.31.22. Result: PASS. The client received a full menu state 19 with the target slot restored to diamond x16 and carried stack count 0; backend RCON confirmed the target still held exactly 16. Assertions SHA-256 `B15B055C25B1239D2D6FC57F7AB9EF389D0F35AD399C43B806810C16558CBBFD`; probe log SHA-256 `114FD3EB2F62F42E563F9E0EDA4336C8D6634899B6385B5BFA42F79EF4208730`; script SHA-256 `10598D6B4ECB7A48C2D7AD672C2C923805CF9211C70B373EB0046DF00A6F90F2`; probe source SHA-256 `13BE294A9D9EEAEC1F91E64168952FCA4E7F333888F59D9CAECAAF9CF818819F`. Fixture: `D:\multipaper-tests\system-temp\rc5-invsee-gate-runtime-20261028\rc5-gate-runtime-logs-reusable-113308`.

This verifies that the fail-closed gate returns the authoritative carried stack in the full menu packet for this single click. It does not simulate a full Java client's speculative prediction/application or test successful remote editing, concurrent click transactions, restart recovery, or item conservation across accepted cross-owner edits. RC5-02 remains P0 `BLOCKED`. All fixture ports closed and its processes exited; live services/worlds untouched.

### Portable probe path confirmation — 2026-10-28

The protocol client now loads `Minecraft26_3ProtocolSupport.js` through a repository-relative path. Re-ran the complete disposable harness after that change; result PASS at `D:\multipaper-tests\system-temp\rc5-invsee-gate-runtime-20261028\rc5-gate-runtime-logs-reusable-114753`. It observed the denial message, retained target diamond x16, and received container state 19 with the same 16-diamond slot plus `carriedItem` count 0. Assertions SHA-256 `B15B055C25B1239D2D6FC57F7AB9EF389D0F35AD399C43B806810C16558CBBFD`; probe output SHA-256 `D300C619911E6A1689E3E4E51A89272777A3A8BB361DCB09B6ADFC366CAA8D97`; portable probe source SHA-256 `E653166A668CCBC3F5193B1D95D3EAF762083700BE7C3A1449EC59837D07E2B2`; runner SHA-256 `10598D6B4ECB7A48C2D7AD672C2C923805CF9211C70B373EB0046DF00A6F90F2`. The six fixture listeners were closed and no fixture Java command remained. This is only a denied-click resync check; RC5-02 remains P0 `BLOCKED` because functional owner-coordinated editing and crash-safe cursor escrow are absent.
