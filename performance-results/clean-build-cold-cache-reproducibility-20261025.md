# Java 25 clean-build and empty-dependency-cache evidence — 2026-10-25

Source: `feature/port-26.3` commit `2fd01b3` (same source for both passes). Java: Temurin 25.0.3; Gradle 9.8. The normal Gradle wrapper distribution was already installed, but `-g D:\Temp\multipaper-empty-gradle-home-20261024` created a fresh Gradle user home with an empty dependency cache. The first invocation resolved dependencies from scratch and completed; then two separate `clean build` invocations ran using that isolated home. No production or Crafty servers were accessed.

Commands (first pass log: `D:\Temp\multipaper-empty-cache-clean-build-20261025.log`; repeat log: `D:\Temp\multipaper-empty-cache-clean-build-repeat-20261025.log`):

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat clean build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar :multipaper-master:build :multipaper-mastermessagingprotocol:test `
  -x :multipaper-server:applyMinecraftResourcePatches `
  --no-build-cache --no-daemon --max-workers=2 --console=plain `
  -g D:\Temp\multipaper-empty-gradle-home-20261024
```

The only excluded task is `:multipaper-server:applyMinecraftResourcePatches`, the upstream resource-patch reset task previously verified to have no MultiPaper resource patches for this source. `ProcessResources` ran from clean outputs in both passes. The clean outputs each contained 9,932 resources; worldgen tag resources were present. Earlier source/output parity evidence compared 9,893 Minecraft source files with zero missing or mismatched; this clean pass reproduced the full output count.

Both clean runs succeeded:
- Pass 1: `BUILD SUCCESSFUL in 25m 7s`, 93 actionable tasks (59 executed, 34 up-to-date).
- Pass 2: `BUILD SUCCESSFUL in 20m 32s`, 93 actionable tasks (58 executed, 35 up-to-date).

Both runs reported identical test XML totals: 183 suites, 10,737 tests, 0 failures, 0 errors, 88 skipped. Breakdown: API 481 (2 skipped), Server 10,197 (86 skipped), Master 53, Master Messaging Protocol 6.

Byte-identical SHA-256 in both clean runs:
- Paperclip `multipaper-paperclip-26.3-RC4.jar`: `F6466A12BE9BAF2B0071902D4B0AA764AC82698B551A00D633353D1D5517D694`
- Master `multipaper-master-3.0.0.jar`: `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`
- Bundler `multipaper-bundler-26.3-RC4.jar`: `377E84A966CCEE05659633B7B50981206B4CB590B1B52B8E6F0B612D06178AF7`
- Server `multipaper-server-26.3-RC4.jar`: `C2B376671FEAE18BAF1A7A07AAD35CD138B5F73D5E92946D3C1810AD5D1CAD65`

The Master artifact is reproducible and unchanged. The Paperclip server manifest is `26.3-DEV-2fd01b3`, empty build number, and epoch build time because `BUILD_NUMBER` was unset; the candidate filename/project metadata remains RC4. The earlier locally staged Paperclip (`CFB7...`) was built before the checkpoint-only `2fd01b3` commit and is superseded. These exact clean artifacts then passed a disposable two-backend runtime/plugin/command smoke; exact-hash evidence is in `performance-results/final-artifact-runtime-smoke-20261025.md`.

The published v26.3-RC3 tag and assets remain unchanged. Do not publish stable: real authenticated Java gameplay, real Bedrock/Floodgate identity/reconnect, legitimate-client GrimAC behavior, and intended-hardware capacity are still open.