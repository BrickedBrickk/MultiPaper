# RC5 Crafty1 no-holder path current-source recheck — 2026-10-29

## Input custody

The user-referenced Crafty1 `latest.log` upload was not present at `C:\Users\elibo\Downloads\1latest.log`, under the Codex attachment directory, or as the repository's `logs/latest.log`. The repository file is unrelated ViaProxy output (1,123 bytes; SHA-256 `C12DD0FB17A2D75CDC621FD3C08C3DE5302CE7459FFCE6B2FDC4F39CB75EBBF0`) and was excluded. No fresh digest, parse, or recount of the requested upload is claimed. The preserved Crafty1 analysis remains the prior read-only digest `920B665985CB2066D2199F0A6795C438D268FEADE91E8BE1246EAEA00CCA25D1`.

## Current source trace

On branch `feature/port-26.3`, HEAD `dd4756dcc7262225e5cd54411eae08274c77c605`, the inspected current path still matches the documented Master-backed fallback:

1. `MultiPaper.readRegionFileAsync` detects a missing local holder and calls `forceReadChunk` for region data (current `MultiPaper.java` SHA-256 `5178E9B21A006F6472279F22C4A2C8D8BDD8397CFDD107E5972C2DA6F63CBFC8`).
2. `forceReadChunk` sends `ForceReadChunkMessage` to Master; it does not select the backend-local region directory.
3. `ForceReadChunkHandler` waits for Master `ChunkLockManager` for region data, reads `RegionFileCache` under Master world storage, then applies any pending container transaction before replying (SHA-256 `2AEFF88713CD5EF29DCD4E5D21CEB6F941E15C40FA86FACC5C984425041EFD99`).
4. The ordinary `ReadChunkHandler` also waits on the Master chunk lock when it reads Master region data (SHA-256 `2485F3AEEC66EAE80958FBC93F8380B2170CD67EFD3AA2DEEF46ACA6D1AE01BA`).

This supports the conclusion that the warning describes an intentional Master-backed fallback, not a direct Moonrise read from a backend's local region file. It does not guarantee freshness if a prior owner save was rejected, absent, or incorrectly ordered. The warning lines have no chest coordinates or block-entity NBT; they do not prove stale reads or chest loss.

## Existing isolated evidence and scope

No stress fixture was rerun: prior reports already provide hash-pinned Java 25 evidence for the 273-read production no-holder route and current-candidate A→B/B→A ownership schedules. The 273-read result preserved exact single/double chest stacks through chunk load and graceful Backend/Master restarts; the bidirectional schedules rejected the delayed former-owner image and retained the exact seeded contents. Evidence: `rc5-crafty1-273-noholder-read-stress-20261028.md` and `rc5-current-source-chest-273-bidirectional-20261029.md`.

Those reports cover selected synthetic schedules, not every Moonrise unload/save/power-loss timing and not the reported live chest incident. No live Crafty host, process, world, or player data was accessed. No source behavior or test changed. Keep RC5-01 P0 `TESTING`; no chest recovery is claimed or attempted. RC5-02 InvSee++ remains P0 `BLOCKED`; three stale replacement rejections are not item-conservation evidence. Entity UUID/NBT/removal warnings remain separate lifecycle issues with focused regressions outstanding.
