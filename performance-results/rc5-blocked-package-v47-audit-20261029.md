# RC5 blocked review package v47 audit — 2026-10-29

## Scope and custody

Read-only audit of `release-staging/MultiPaper-26.3-RC5-blocked-current-source-v47-20261029.zip`; the archive was not modified, extracted to a persistent location, or distributed. This is a local blocked-review package, not an installable release.

- Archive size: 82,759,936 bytes.
- Archive SHA-256: `13E3255441293EADC0616A9D2512A2F25C0D3AF5611DF203972F9236D081DA39`; its `.sha256` sidecar matches.
- ZIP contains 62 entries. The internal SHA256SUMS manifest has 60 payload entries; all **60/60** entry hashes were recomputed directly from ZIP streams and matched. It includes the Master and Paperclip JAR, RC5 docs, evidence, monitoring docs/config examples/start scripts, and monitor source/tests.
- Path review found no world/region/playerdata directories, logs, database files, RCON files, private-key files, or credential-named paths. A constrained scan for private-key blocks and inline credential-like settings found no secret values; one false positive was the monitor's local variable named `password`, populated at runtime from a required environment variable. This is a bounded package audit, not a general secret scanner.

## Staleness and release limits

The package provenance names product source commit `f59ae3833b8faa4344c407a04beba98978f79bea`, which is an ancestor of current branch HEAD `8d953398e45538679d5979aaa1a1ee22484a4370`. It therefore predates subsequent current-source changes. Its Master/Paperclip hashes (`E9CEE0B2…` / `4180250D…`) are not the latest current-source Master output (`466A094E…`), and the latest Master/protocol output has no matching Paperclip built from the same source. The packaged tracker is also older than the current working-tree tracker.

`README-BLOCKED.txt` correctly says BLOCKED / NOT FOR INSTALLATION OR DISTRIBUTION and names unresolved chest attribution, InvSee++ item conservation, exact-stack/network/hardware gates. Keep that label. Do not update only the documentation while leaving these stale binaries. A refreshed artifact requires a new exact-source full build pair, tests and runtime evidence after P0 gates.

## Result

The v47 archive is internally hash-consistent and contains no detected private/local-world payload, but it is stale relative to current source and is correctly blocked. No source, release staging, live server, Crafty host or world was changed. Full release remains blocked.
