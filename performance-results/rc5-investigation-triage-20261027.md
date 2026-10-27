# RC5 investigation triage — 2026-10-27

## Scope and safety

- Development checkout: `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork`, branch `feature/port-26.3`, HEAD at the time of the first pass `483294876697a614ac0e55cfa327bf90c285cb88`; current documented HEAD is `47ea4b3129b3deeaed5e99f56d397c8e79610668`.
- The product source candidate is commit `2265a5a3bf54d1a482c5ac21bd856a9a2674ee32`. Git confirms it is an ancestor of HEAD and there are no later changes to `patches/server` or `multipaper-server` product source. The workspace contains many unrelated untracked reports, logs, scripts, fixtures, plugin artifacts, and archives; none were edited or removed.
- No live Crafty/RC4 host was contacted, and no server process was stopped, restarted, changed, deployed to, or benchmarked. The initial source audit was read-only; the later isolated test attempt is recorded below.
- One read-only source audit agent was used, as requested. It did not edit files or access servers.

## Inventory duplication: what is already proven

The checkout contains the previously reproduced concurrent replacement guard. `performance-results/player-inventory-concurrent-replacement-20261021.txt` documents the original failure: two backends concurrently replaced the same eight-diamond source with four emeralds and six gold, and both replacements survived. The persistent guard was added before the current candidate; commit `ac3fb4f3100eb5f568b41b604716addc05af2418` is an ancestor of HEAD. On the rebuilt artifact, the second stale replacement was rejected and the owner-slot resync left only one replacement. A later exact build check on the two physical PCs also passed this administrator-driven adversarial mutation.

Exact current-tip RC4 evidence already covers these narrower cases:

- Cross-backend InvSee++ normal click moved five diamonds from slot 0 to slot 1, with both backend views agreeing.
- Owner disconnect/reconnect and reassignment to the other backend retained exactly one stack in both views on repeated reconnect.
- Ender Chest remote withdraw/redeposit cycles conserved six emeralds across the owner chest, mirror, target inventory, and admin inventory.

These are synthetic disposable-client checks. They do not prove real-player or human-GUI concurrency safety.

## Still untested for the user-reported GUI symptom

No current exact-candidate reproduction was found for two human-style GUI editors acting concurrently, shift-click races, drag distribution across slots, cursor-stack changes, crafting-result transactions, or disconnect/restart during an in-flight GUI transaction. Historical evidence explicitly states those limits. The stale-replacement source checks are owner-thread synchronous, but that alone is not proof for every packet sequence.

The read-only source audit also found that Ender Chest inventory updates carry slot snapshots without a monotonic per-slot sequence. Owner/session identity rejects updates from an old player owner/session, but same-owner/same-session write ordering has not been exercised with an injected delayed packet. This is an unverified race hypothesis, not a confirmed bug; no patch should be made without a controlled failing reproduction.

## RC5 disposition

No new product defect was reproduced in this pass, so no production Java source patch or JAR was changed. Do not claim that the reported intermittent human GUI duplication is fully closed based only on ordinary-click/reconnect tests. The next focused disposable investigation should use the exact RC4 Master/Paperclip/plugin hashes, two backend owners, and item-count assertions around concurrent InvSee viewers, shift-click, drag distribution, and a delayed same-owner Ender Chest update. Test fixtures must be fresh and marked disposable; no production world or live Crafty host may be used. If that matrix does not fail, preserve the current guard and document the remaining real-player evidence gap instead of adding speculative concurrency/versioning logic.

Other listed reports (boats/passengers, leash visibility, held-item/block views, villager sleep pose, block animations, sleep/gamerule/global state, and leave announcements) require symptom-specific exact-pair reproducers. Existing checkpoint instructions say entity/tick work already passed; do not repeat those checks without a new failing scenario or changed source.

## Follow-up isolated shift-click attempts — 2026-10-27

- Revalidated the exact RC4 fixture binaries before startup: Paperclip SHA-256 `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`; Master SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`. The test fixture used a separate disposable world directory and the exact official plugin artifacts noted in the setup records; it did not touch release artifacts or the source tree.
- The first proxy-routing attempt observed only same-owner players and could not issue a click. A corrected retry connected the synthetic clients through Master proxy port 32952 and observed `StackAdmin3(rc5-a)` with target `RC5Shift1(rc5-b)`. The target was seeded on backend B with eight diamonds via local fixture RCON (`/give`); backend B logged its synthetic diamond advancement. The probe was still waiting for the explicit `GO` input when GrimAC logged `Disconnecting RC5Shift1 for disconnect.timeout` at 16:39:17. Its probe script sends the shift-click only after `GO`, so no inventory click was issued and no post-click conservation assertion exists. Classify this as **inconclusive due to synthetic-client idle timeout**, not a pass or a product defect. The attempt exercised only player inventory; it did not issue chest, block, fill, or world-edit commands.
- The fixture world was the separate disposable level `rc5_invsee_20261027`, with no live world data copied in. The synthetic target was the only recipient of the eight test diamonds. All fixture Java processes and the probe have since exited; the final listener check confirmed ports 32951–32962 closed. No Crafty/RC4 host, production process, world, or player data was accessed or changed.
- No product source or JAR changed during this shift-click attempt. No build or test suite was run as part of that runtime attempt.

## Cross-owner shift-click result — 2026-10-27

- A later run used the same exact RC4 Paperclip/Master pair and disposable world, connecting the protocol clients through Master proxy port 32952. This time the probe received `GO` immediately after the pair was seeded. The player owner mapping was `StackAdmin3` on backend B and `RC5Audit0` on backend A.
- Before the click, RCON `data get entity ... Inventory` on both backends showed `RC5Audit0` with 8 diamonds and `StackAdmin3` with 8 diamonds. InvSee++ displayed the target's 8-diamond stack in slot 0. A mode-1 shift-click removed slot 0, and the viewer inventory update placed the target's 8 diamonds into the viewer's existing 8-diamond stack in window slot 89, making 16.
- Immediately after the click, RCON on both backends showed the target inventory empty and the viewer with 16 diamonds in inventory slot 8. Aggregate before and after counts were both 16. This passes one controlled synthetic cross-owner shift-click with stack merge and mirrored backend reads; it does not test two simultaneous editors, drag distribution, reconnect during the transaction, or restart persistence.
- Exact RC4 fixture hashes were rechecked after the run: Paperclip on each backend `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`; Master `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`. Java 25 was used. Backend logs record `/invsee RC5Audit0`; disposable fixture PIDs were 40300 (Master), 19968 (A), and 30876 (B). Both backends received `save-all flush` and orderly `stop`, then the Master exited. Final verification found no fixture processes and no listeners on ports 32951–32962.
- This result is not a new product fix and does not close the user's intermittent real-player duplication report. No chest, block, or world-edit command was issued; only synthetic player inventories were involved.

## Cross-owner drag distribution result — 2026-10-27

- A corrected disposable protocol probe connected both synthetic clients through proxy port 32952 and established `StackAdmin5` on backend A with target `RC5DragS0` on backend B. It opened `/invsee RC5DragS0`, picked up the target's 8-diamond stack from window slot 0, then sent a left-drag start, add-slot 1, add-slot 2, and end sequence.
- The InvSee++ window reported four diamonds in each target slot. Immediately afterward, owner reads on backend B and mirrored RCON reads on backend A both returned count 4 for target inventory slots 1 and 2; the viewer inventory remained empty. The aggregate target/viewer count remained 8. This passes one synthetic cross-owner drag distribution, not a concurrent-editor or human-GUI test.
- The first drag harness version watched `set_slot` for source removal, while InvSee++ sent the source update in `window_items`; it therefore stopped after pickup. On that separate disposable attempt, reconnecting the synthetic viewer showed the 8-diamond carried stack restored in its inventory. That attempt was not counted as a drag pass. The corrected harness handles the observed `window_items` packet.
- Exact fixture hashes were rechecked: both Paperclip JARs `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`; Master `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`. Java 25 was used. The disposable fixture saved and stopped both backends and then the Master; the remaining probe PID was verified against its command line and stopped. No test listener or task-owned process remains on ports 32951–32962.
- No chest/block/world-edit action occurred. No source or binary changed. Remaining inventory gates: simultaneous viewers, repeated mixed operations, disconnect/reconnect/restart between transaction steps, and real-player symptom reproduction.
