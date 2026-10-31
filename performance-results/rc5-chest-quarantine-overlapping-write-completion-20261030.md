# RC5 chest quarantine overlapping-write completion regression — 2026-10-30

## Reproduction

At product source `a87d9562a8d52e28837afa01c80c86b9c0c1555e`, a new isolated `ChunkLockManagerTest` seeded the durable failed-write marker, registered two accepted writes for the same chunk, completed the first successfully, and asserted that the marker remained while the other write was pending. The pre-fix run failed exactly at that assertion: the first success had deleted the marker even though the second write remained. Red-run log SHA-256: `BD952986DCBD03B8BC88CEB6F83C075F4D670A9D88A737FE1578197BC1D2C621`. The red output is retained at `D:\multipaper-tests\evidence\rc5-overlap-quarantine-red-a87-20261030.log`.

This exposes a real recovery gap in the implemented failed-save quarantine: if a prior region write failed and left old on-disk chest data, a later successful write could clear the durable warning before every already-accepted same-chunk write had completed. A Master crash while a later write was still active could then restart without the marker. This test models the lock/completion cut point; it does not itself terminate a Master process during an actual disk write.

## Fix and focused regressions

`ChunkLockManager` now checks the per-chunk write barrier before clearing quarantine. A successful completion retains the marker while another active or announced write exists. The final successful completion clears it. If an earlier successful repair is followed only by cancellation of the remaining announcement, the marker clears once the barrier drains. A later failure removes the pending-success state and preserves the durable quarantine. Disconnect cleanup also resolves successful repairs whose remaining announcements were canceled.

The two new tests cover (1) two overlapping active writes and (2) a successful write followed by cancellation of another announced write. Along with the pre-existing marker-stage and atomic late-announcement tests, the focused run passed **4/4**. A further regression directly exercised disconnect cleanup: after a successful repair with another announced write pending, `releaseAll` canceled the unused announcement and cleared quarantine. The updated focused class passed **5/5**; latest XML SHA-256 `3858923271BBEA268BC1D4CA0A6D9A4F8666F610C1A6E5A2AB19776F744BFB03`; Gradle log SHA-256 `9E878A3FBA0B47C32D1FA32AD8DD6A60E63B82D08A8D9DF0D7CB3D82C56B2927`. The latest test file SHA-256 is `ABED4A095D02433F8A0AD56AA93803FE360FD7386790FECC34A5384A200D1F6A`; the four-test version used in the full-suite runs hashes to `71AA21650FC133D4DA75B0BFE47A5A14A2C556C03B2D65ECC8AA18E54EC88477`.

## Full Master verification and reproducibility

Exact modified product source hashes:

- `ChunkLockManager.java`: `F23C8ECC458406E8D399306AED910FC8CEAD9AF6C3ED08F4CEF2EE43801E8E49`
- `WriteLockManager.java`: `0D12B26328D9E65D93AA01C863F497EEDC45A3B6B68850276480419EE8FB1342`
- Full-suite `ChunkLockManagerTest.java`: `71AA21650FC133D4DA75B0BFE47A5A14A2C556C03B2D65ECC8AA18E54EC88477`; updated focused version with disconnect case: `ABED4A095D02433F8A0AD56AA93803FE360FD7386790FECC34A5384A200D1F6A`
- Existing randomized inventory journal test: `4D77016AE46A929F46D7B4C3E5B9F1A905103CBCB95F7C7860D76253FCA7169D`

Two detached Java 25.0.3.9 / Gradle 9.8 worktrees used identical source overlays, sequential uncached `:multipaper-master:clean :multipaper-master:test :multipaper-master:shadowJar --rerun-tasks --no-daemon --no-configuration-cache --no-build-cache --max-workers=1` runs, and no overlapping Gradle jobs. Each Master suite passed **126/126**, zero failures/errors/skips with the four-test version of `ChunkLockManagerTest`. The updated five-test version then passed separately (5/5). Both shaded Master JARs are 19,251,690 bytes and byte-identical, SHA-256 `60209180D1CF0FE3F7B4A1E27EA798B66874A0FBC99CA085ACB4C1E332F30B02`; the extra regression changes tests only, not product source.

- Pass 1 Gradle log SHA-256: `9F169640233E1E29BED5FCF91AC12F56C3175D1A04186928B3DD2E63F4B9580B`
- Pass 2 Gradle log SHA-256: `C9F0F14DDCA2E517517B31E01F13468F1B58D29AE6194CA5C6E6B057CCEA1F06`
- JARs, complete per-suite XML, logs, and manifests: `D:\multipaper-tests\evidence\rc5-overlap-quarantine-two-pass-20261030\`; latest focused output is under `focused-disconnect-test\`.

## Limits and disposition

This fixes premature clearing of an existing failed-write quarantine across an overlapping same-chunk completion barrier. It does not persist a write-intent marker before the first disk operation, cover power-loss ordering inside region-file sector/header writes, reproduce the complete Moonrise unload/restart path, or identify/recover any reported live chest. No Paperclip/backend pair was rebuilt from this Master change. RC5-01 remains P0 `TESTING`; do not label chest persistence verified or infer that the live chest-loss incident is fixed. No live SMP/Crafty process, world, or player data was accessed.
