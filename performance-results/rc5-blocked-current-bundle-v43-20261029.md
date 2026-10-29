# RC5 blocked current-source review bundle v43 — 2026-10-29

## Artifact and provenance

- Archive: `release-staging/MultiPaper-26.3-RC5-blocked-current-source-v43-20261029.zip`
- Size: 82,743,113 bytes
- Archive SHA-256: `21ef1e18731ae48ccf914ee4f2b622a493653e718921ff8de73fa483f9babd88`
- Detached sidecar matched the archive bytes.
- Embedded `SHA256SUMS.txt` SHA-256: `29f983c2c95760f9f1f30aab634dd0f9d010244b372aa4c5abbfb111b03d18bb`
- All 57 payload entries were rehashed from the ZIP members and matched; 59 total entries including the manifest and manifest checksum.
- Packaged product source: base `04724a62e188d5f99c20435a6a47427d09de5d1e` plus implementation/test overlay committed as `f59ae3833b8faa4344c407a04beba98978f79bea`.
- Packaging checkout HEAD: `3973e666648d4367503d4f0435ec9fedc5003dfe`.
- Master: `E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E`.
- Normal Paperclip: `4180250D05A570009F30349A0251A4924D11D403ED7E4C65CF5686C18B204E7F`. The diagnostic-only Paperclip used to record the registration callback was not included.
- Build evidence: two sequential clean Java 25 full builds, each with 10,794 tests, zero failures/errors, 88 skipped; all nine JARs byte-identical. Details are in `rc5-player-session-registration-readiness-20261029.md`.

## Package audit

Direct ZIP-member validation confirmed the sidecar, all 57 payload checksums, no duplicate paths, no world/backup/playerdata/region/raw-log/database/key entries, and no private-key or literal RCON-password text matches in package documentation/configuration. The current tracker, compatibility matrix, manual update plan, monitor materials, and current evidence reports are included. There are no live world files or private player chat.

## Release status and limits

The README says **BLOCKED / NOT FOR INSTALLATION OR DISTRIBUTION**. RC5-01 remains P0 `TESTING`; chest tests are isolated and do not establish the cause or recovery of live empty chests. RC5-02 remains P0 `BLOCKED`; remote InvSee++ edits remain fail-closed and functional cross-owner item conservation is not implemented. The registration ACK runtime used a diagnostic Paperclip and does not claim runtime behavior for the normal packaged Paperclip. The latest referenced Crafty1 upload bytes were unavailable, so the packaged log report is retained historical evidence, not a fresh parse. Exact six-component authenticated login/routes, target-host monitor overhead, old-client behavior and intended-hardware capacity remain unverified. This is a review archive only; no public release, deployment, or live-server access occurred.

## Reproduction

The archive was created with `scripts/Build-RC5BlockedCurrentBundle.ps1` using v42 as the immutable prior container and the exact normal Java 25 build outputs above. The script rejects unexpected JAR hashes and refuses to overwrite an existing archive or payload directory. Archive member checks were run after creation against the sidecar and embedded manifest.
