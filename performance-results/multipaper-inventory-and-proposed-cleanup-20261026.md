# MultiPaper inventory and proposed cleanup — 2026-10-26

Read-only inventory only on both Debian hosts; no Crafty/production configuration or file contents were changed. SSH used BatchMode with strict host-key checking. The current build worktree, evidence, source, and unrelated files are to be preserved. **Nothing in this proposed manifest has been deleted.**

## Windows development PC

| Path / scope | Observed size/count | Disposition |
|---|---:|---|
| `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\performance-results` | 315 files / 837,052,880 bytes at inventory start | Preserve source evidence and fixture logs. The current monitor report was added afterward. |
| `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\release-staging` | 765 files / 16,022,323,354 bytes at inventory start | Preserve all packages. Three old ZIPs below are optional candidates; their unpacked sibling copies remain. The final RC4 package was added after this measurement. |
| `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\scripts` | 61 files / 661,082 bytes at inventory start | Preserve scripts and fixtures; monitor/packager files were added afterward. |
| `E:\multipaper-builds\MultiPaper-26.3-current-tip-2265-20261025` | 164,371 files / 2,746,156,302 bytes | Preserve exact clean-build worktree and reproducibility evidence. |
| `C:\Users\elibo\AppData\Local\Temp` | 276 top-level directories matched MultiPaper/Minecraft-data naming | Not fully size-audited; no deletion candidates proposed from this set. |
| `E:\multipaper-tests` | Four top-level MultiPaper fixture/evidence folders, including `system-temp` | Preserve. It includes the verified disposable runtime fixtures and the final archive reproducibility copy. |

Local build/process check found no active Gradle/Minecraft Java process or portal-test listener on ports 32751–32762. C: had approximately 354 MB free and E: approximately 34 GB free at the check. No wide cleanup scan was run against unrelated user data.

## Debian Crafty hosts

The following home-directory trees were sized read-only with `du -sb`. Their contents include old JARs, plugin/config directories, logs, and world data; world directories were observed under both the test and test-backup trees. Treat all as protected.

| Host | Exact path | Bytes |
|---|---|---:|
| `crafty1@100.78.108.117` | `/home/crafty1/multipaper-backups` | 3,332,508,037 |
|  | `/home/crafty1/multipaper-disposable-20261019-fresh` | 292,133,093 |
|  | `/home/crafty1/multipaper-release-capacity-20261022` | 378,216,947 |
|  | `/home/crafty1/multipaper-test` | 7,062,217,891 |
|  | `/home/crafty1/multipaper-test-backups` | 4,029,389,964 |
|  | `/var/tmp/multipaper-online-cert-20261024` | 220,982,520 |
| `crafty2@100.107.178.51` | `/home/crafty2/multipaper-backups` | 3,019,831,160 |
|  | `/home/crafty2/multipaper-disposable-20261019-fresh` | 248,247,569 |
|  | `/home/crafty2/multipaper-release-capacity-20261022` | 314,915,176 |
|  | `/home/crafty2/multipaper-test` | 4,628,693,478 |
|  | `/home/crafty2/multipaper-test-backups` | 3,620,889,198 |
|  | `/var/tmp/multipaper-online-cert-20261024` | 186,588,650 |

Read-only process inspection found two Java processes on PC1, with working directories `/var/tmp/multipaper-online-cert-20261024/master` and `/var/tmp/multipaper-online-cert-20261024/backend-a`, and one Java process on PC2 at `/var/tmp/multipaper-online-cert-20261024/backend-b`. No matching MultiPaper systemd service unit was listed. These processes were not stopped or signalled. The current home test trees contain many `world` directories and backups; **no Crafty-host deletion candidates are proposed**.

The inventory did not read `server.properties`, plugin configuration, environment variables, world contents, Crafty data, or process command lines. It did not inspect unrelated homes or workloads beyond identifying named MultiPaper roots and the active Java working directories.

## Exact proposed local deletion manifest

The three ZIPs are explicitly named `superseded`. Each contains five files; SHA-256 comparison verified every archive member against its sibling unpacked directory. A second copy of each candidate and the small package-preparation scratch file was copied to `E:\multipaper-tests\cleanup-backups\preapproval-20261026\`; all four copied files matched the original SHA-256 values. The backup checksum list is at that path. The off-volume copies are retained regardless of the decision.

| Proposed path | Bytes | SHA-256 | Reason |
|---|---:|---|---|
| `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\release-staging\superseded-MultiPaper-26.3-preview-1.zip` | 84,720,492 | `FB36BE9A0E3E63E932DA84A3952F5AF5A3D6EC342E677B4DF07F9CDEF124E902` | Explicitly superseded preview archive; exact five-file unpacked sibling and off-volume copy verified. |
| `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\release-staging\superseded-MultiPaper-26.3-RC1-initial.zip` | 84,721,226 | `6DB5B504F7FD3D851145517542DDF190C964EF2F3D15DDC8B0C594806D12EB39` | Explicitly superseded initial RC1 archive; exact five-file unpacked sibling and off-volume copy verified. |
| `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\release-staging\superseded-MultiPaper-26.3-RC1-textual-build.zip` | 84,721,326 | `6585810C5763237A38C90DE829C16750467ED248CAC5FD748BA35A96DA66240D` | Explicitly superseded textual RC1 archive; exact five-file unpacked sibling and off-volume copy verified. |
| `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork\release-staging\RC4-templates-source-placeholder.txt` | 148 | `C76B235E98DA27E4634F7B4AA279BABDD74480963F94B8B175C22D4E8FD6CE01` | Unused package-preparation placeholder created during this work; not included in the RC4 stage/archive. Exact off-volume copy verified. |

Total proposed size: **254,163,192 bytes** (the three archives total 254,163,044 bytes; placeholder is 148 bytes). Deleting only the three ZIPs would retain their unpacked copies. No deletion has occurred; the user must review and explicitly approve any removal. All other files remain outside this proposal.
