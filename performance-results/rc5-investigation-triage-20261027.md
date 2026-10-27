# RC5 investigation triage — 2026-10-27

## Scope and safety

- Development checkout: `D:\ProjectsV3\Minecraft Mods\MultiPaper-Fork`, branch `feature/port-26.3`, HEAD `483294876697a614ac0e55cfa327bf90c285cb88`.
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

## Follow-up isolated shift-click attempt — 2026-10-27

- Revalidated the exact RC4 fixture binaries before startup: Paperclip SHA-256 `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`; Master SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`. The test fixture used separate fresh world data and the exact official plugin artifacts noted in the setup records; it did not touch release artifacts or the source tree.
- A modified copy of the existing protocol probe was prepared only under `D:\multipaper-tests\system-temp\rc5-inventory-matrix-20261027\probe`. Node syntax validation passed. The run connected synthetic protocol 26.3 clients, but all owner observations were `rc5-a`; it never found a cross-backend administrator/target pair, so no shift-click was issued and no conservation assertion was possible. This is an **inconclusive harness attempt**, not a pass or a defect reproduction. Logs remain under the marked disposable fixture.
- The probe process was stopped, both fixture backends received `save-all flush` and orderly RCON `stop`, then the fixture Master was stopped after both backend connections closed. Final listener check confirmed ports 32951–32962 closed. Only those task-owned fixture processes were stopped.
- No product source or JAR changed during this shift-click attempt. No build or test suite was run as part of that runtime attempt.
