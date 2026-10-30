# RC5 blocked local package audit — 2026-10-30

## Artifact

- Payload: `release-staging/MultiPaper-26.3-RC5-blocked-3bc6f46-20261030.zip.payload/`
- Archive: `release-staging/MultiPaper-26.3-RC5-blocked-3bc6f46-20261030.zip`
- Status: **BLOCKED — NOT FOR INSTALLATION OR PUBLICATION**
- Product source: `3bc6f46f3c6dc006540b5dc3b74b7033a35ccdb2`; latest owner-fork branch tip at staging: `c4b86e67d77cddc34eb7513222fa2ad16a726bea` (later commits are documentation-only).
- Archive size: 82,465,923 bytes. ZIP SHA-256: `E98D7B2B05838CDDC8CCF192081C8D6FC695E4440F8ADA0ED9B19C89B30902D9`.
- Included `SHA256SUMS.txt`: SHA-256 `AA850E651A737BE53024B6E60E643C8D6A08F8BB7345146C0EFB0B1230D70743`; it lists 20 payload files (the manifest itself is identified by this report and external sidecar).
- ZIP sidecar `release-staging/MultiPaper-26.3-RC5-blocked-3bc6f46-20261030.zip.sha256` matches the archive digest.

## Included

The bundle contains the exact-source Master and Paperclip JARs, blocked status/limitations and tracker pointer, component version/hash references and topology, manual update/rollback instructions, monitoring collector and sanitized example configs, Crafty topology/network instructions, backup/restore guidance, beta plan, reproducible-build evidence, and full-suite test report. It intentionally omits the large historical issue tracker and full historical compatibility matrix; the canonical tracker remains at `docs/RC5_BUG_TRACKER.md` in the source repository. No third-party plugin JARs are bundled; component references point to the 2026-10-29 official availability/local hash audit and must be rechecked before any future release.

Product JARs:

- Master 19,216,550 bytes — SHA-256 `86F694BA1882E2D08DC841ECE62EA11B105663DAADEF3D7C1C8DF52B1531BE19`.
- Paperclip 64,352,019 bytes — SHA-256 `464FCE20DEE5A7734666A573BA207F94E4D3E6AD7903C6BEF02A648CB32CD4F7`.

## Package verification

The archive was extracted into a fresh disposable F: audit directory. All 20 listed file hashes matched; there were zero missing, malformed, mismatched, or unlisted files. The ZIP sidecar matched. Filename audit found no worlds/region files, `server.properties`, MultiPaper config, RCON files, key files, or raw `latest.log`. The bundle contains no real monitor config; example JSON uses placeholder paths and RCON environment-variable names only. No credentials, private keys, local plugin databases, local worlds, raw logs, or player chat were intentionally added.

## Release gates that remain closed

The package README and build-status file explicitly block installation/publication. Chest-loss incident cause/recovery remains unknown (P0 TESTING); InvSee++ cross-owner item conservation remains blocked (P0 BLOCKED). The current exact-pair six-component runtime, authenticated Java/Bedrock paths, older-client behavior, farms, 5–20-player capacity, and two-week beta have not been verified. Reproducible builds and `--version` smoke do not satisfy those gates. No live server, Crafty host, world, or player data was accessed or modified.
