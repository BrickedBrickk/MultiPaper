# RC5 blocked current review bundle v45 — 2026-10-29

## Artifact and provenance

- Archive: `release-staging/MultiPaper-26.3-RC5-blocked-current-source-v45-20261029.zip`
- Size: 82,745,807 bytes
- Archive SHA-256: `61f4c894ea200cfa87722e22e3c5d0a0a9bcbae2f5f274f42b8f4921eabfc7ad`; detached sidecar matched.
- Embedded manifest SHA-256: `5f987b93e17c41531b3ff89317d9b4218e70519b9dabdf49002445b0448c456c`; all 58 payload entries were recalculated from ZIP member bytes and matched, 60 total entries.
- Product source: base `04724a62e188d5f99c20435a6a47427d09de5d1e` plus the implementation/test overlay committed as `f59ae3833b8faa4344c407a04beba98978f79bea`.
- Packaged Master SHA-256: `E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E`.
- Packaged normal Paperclip SHA-256: `4180250D05A570009F30349A0251A4924D11D403ED7E4C65CF5686C18B204E7F`.
- Build evidence: two clean Java 25 full builds; 10,794 tests each, zero failures/errors, 88 skipped, nine JARs byte-identical: `rc5-player-session-registration-readiness-20261029.md`.

## Package verification

Direct archive verification passed the detached sidecar, all embedded payload checksums, exact Master/Paperclip manifest hashes, required tracker/matrix/chest report entries, and the explicit `BLOCKED / NOT FOR INSTALLATION OR DISTRIBUTION` label. The 60 archive entries contain no duplicate paths, unsafe world/backup/playerdata/region/raw-log/database/key paths, private-key markers, or literal RCON password patterns. The diagnostic-only Paperclip used for ACK instrumentation is not included.

## Exact packaged-pair chest regression

The package now includes `rc5-v43-packaged-pair-chest-273-20261029.md`. The exact packaged normal JAR pair passed the 273-read forced Master no-holder/stale-owner chest schedule in both ownership directions. Master rejected the delayed former-owner snapshot; exact single/double chest contents remained after new-owner backend restart and graceful Master restart. Both fixtures and all six ports were closed. This is selected isolated evidence only; it does not attribute or recover live empty chests.

## Release state

Local review only. RC5-01 remains P0 `TESTING`; live chest loss remains unattributed. RC5-02 remains P0 `BLOCKED`; remote InvSee++ edits remain fail-closed and functional cross-owner item conservation is not implemented. Paired ACK runtime evidence used a diagnostic Paperclip, not the packaged normal Paperclip. The new Crafty1 upload bytes remain unavailable for a fresh parse. Exact six-component authenticated login/routes, old-client behavior, target-PC monitor overhead and intended-hardware capacity remain pending. No public release, deployment, or live-server access occurred.
