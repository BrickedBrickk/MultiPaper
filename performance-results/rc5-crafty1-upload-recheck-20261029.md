# RC5 Crafty1 latest.log recheck — 2026-10-29

## Evidence custody

The requested uploaded `latest.log` was not mounted in this session at `C:\Users\elibo\Downloads`, the Codex attachment directory, or the repository's `logs` directory. The repository's `logs/latest.log` is a separate 1,123-byte ViaProxy log and was excluded. No new byte-level review or hash is claimed for the upload. This review therefore reuses the previously preserved, read-only Crafty1 log analysis with SHA-256 `920B665985CB2066D2199F0A6795C438D268FEADE91E8BE1246EAEA00CCA25D1` (110,461 bytes); original evidence was not modified.

## What the preserved log establishes

- 273 `has no chunk holder for reading chunk` warnings occurred at 20:09:52–20:09:53 on `Paper I/O Worker #0`, over `world,region` coordinates. The lines contain no chest coordinates, block-entity NBT, or save outcome.
- The same log contains 119 `moved too quickly` warnings at 16:27:01–16:27:07 for one player session, eight duplicate entity UUID attempts, 13 `EntityUpdateNBTPacket` warnings before the destination chunk was loaded, ten entity-removal owner-routing failures followed by broadcast fallback, and three rejected stale inventory replacements. None is correlated in the log to chest coordinates or a specific chest action.

## Source trace and data-integrity interpretation

In the logged RC4 source, the no-holder warning is emitted when a region read has no local `NewChunkHolder` (or the Bukkit world is unavailable). That branch calls `forceReadChunk`, sends `ForceReadChunkMessage` to Master, and Master reads its authoritative world `region` storage through `RegionFileCache`, waiting for the chunk lock. It does not select the backend's local region file. This is an exceptional, designed Master-backed fallback; the warning is not proof of a Moonrise bypass or chest corruption. It also does not prove freshness: a previously stale Master snapshot remains possible if an earlier save/ownership ordering was wrong.

No-holder reads with populated single/double chests and delayed former-owner writes have already been exercised in disposable Java 25 fixtures. The 273-read production-path stress retained exact chest stacks through chunk load, backend restart, and graceful Master restart (result SHA-256 `031CF4C0A0B10EFC22CD12B854773FC82AC2E9B4282ACCB4EDE685593A879B07`). Separate current-candidate A→B and B→A handoff stress runs rejected stale former-owner snapshots and retained exact contents; the old-Master control accepted a stale snapshot. These are selected synthetic schedules, not attribution or recovery of the live reports. Do not restore live data automatically.

The other warnings remain separate observations: movement-threshold warnings are not chest evidence; UUID collision attempts and early entity-NBT updates need entity lifecycle/duplicate-UUID regressions; removal broadcast fallback is a routing fallback, not proof of successful removal at every backend; stale inventory replacement rejection indicates refusal/resync, not item conservation. InvSee++ duplication remains its independent P0 blocker.

## Reproduction and status

No new isolated run was started because the new upload bytes were unavailable and the same warning counts and source path already have hash-pinned analysis and bounded isolated regressions. Repeating those schedules without a new source/artifact or failure signal would not add evidence. The 273-read chest case passes in the cited fixtures, while the live empty-chest incident remains unattributed. Keep RC5-01 P0 `TESTING`; keep entity lifecycle issue RC5-13 at its existing `REPRODUCED` status pending exact regression coverage; keep RC5-02 InvSee++ `BLOCKED`. No source change is justified by these warning counts alone. No live server, process, Crafty host, or world was accessed or modified.

## Manual isolated follow-up

When the exact upload is available, record its hash before parsing and compare warning text/count/timestamps. For further reproduction, use a fresh marked disposable world on Java 25, seed and snapshot complete chest `Items` NBT for single and double chests, then exercise no-holder reads during save-lock wait, chunk unload/reload, both ownership-transfer directions, concurrent readers/writers, backend restart, and graceful Master restart. Compare complete NBT after every phase; never connect to or restore the live world.
