# RC4 evaluation beta plan (14 days)

Use a private allowlist with 5–20 invited participants. Keep the Master, both backends, and Geyser on the same reviewed candidate/configuration throughout a test window. Before every planned fault injection, take and verify a restorable backup of Master world data plus both backend configs/plugins. No test should expose backend game ports, coordination, RCON, or Floodgate keys to the Internet.

## First launch checklist

- Verify Java 25, the Paperclip/Master SHA-256 values, and package checksums. Keep an untouched copy of the prior jars and config.
- Restore/initialize one Master data directory and two separate backend directories; never let both backends share local world files.
- Configure the Master listener and player TCP proxy. Restrict the coordination port to the two backend hosts. Backend game ports and RCON stay private; RCON binds loopback.
- Install the identical tested plugin stack on both backends. Set matching forwarding/authentication settings. Preserve the Floodgate key privately and check hashes without printing key contents.
- Route Playit Java TCP only to the Master Java proxy, and Bedrock UDP only to the PC1 Geyser listener. Set Geyser's Java target to the Master. Keep PC2's backend reachable over the private LAN/Tailscale path.
- Start Master, Backend A, Backend B, then Geyser. Verify each readiness log, backend registration, plugin enable, RCON loopback, port bindings, disk headroom, monitor freshness, and a fresh successful backup.
- Use an authenticated test Java account first. Confirm name/identity, movement, permissions, inventory, and reconnect. Add Bedrock only after its Floodgate identity and reconnect are verified.

## Schedule

| Days | Players and focus | Evidence to retain |
|---|---|---|
| 1–2 | 5 players; authenticated Java login, spawn, Survival movement, day/night, commands, quit/rejoin, inventory and Ender Chest conservation | Sanitized monitor timeline; tester/client version; observed login/reconnect; before/after inventory count |
| 3–4 | 5–10 players; redstone clocks, pistons/sticky pistons, observers, hoppers, farms with documented vanilla controls and item collection | Coordinates, block states on both owners, item counts, TPS/MSPT, exact farm steps and control result |
| 5–6 | 5–10 players; stasis pearls, thrown/returning pearls, entity tracking, ownership changes, chunk unload/reload | Entity identity/state and expected one-time action; do not use as a tick-race re-test absent a regression |
| 7–8 | 5–15 players; Nether and End portal entry/return, portal linking, dimension changes, End gateway and ordinary exploration | Portal/dimension route, backend before/after, coordinates, cleanup and inventory state |
| 9–10 | 5–15 players; supported boats, minecarts, horses and other agreed vehicles; legitimate GrimAC/ViaBackwards clients | Client versions, route, false positives/corrections, server-side movement and plugin logs |
| 11–12 | 5–20 mixed Java/Bedrock players; Floodgate identity, permissions, chat only reviewed locally, inventory edits, reconnect and owner transfer | Identity/permissions result without publishing names; ownership result; sanitized event bundle |
| 13 | 5–20; planned Backend A/B and Master recovery/failover in a maintenance window, after fresh verified backup | Stop/start sequence, recovery point, reconnect, world/inventory/entity consistency and incident timeline |
| 14 | Normal mixed play at expected peak; review issues and capacity | 15-minute baseline/load comparison, player peak, TPS/MSPT, CPU/RAM/disk/network, incidents and open risks |

Do not infer maximum capacity by extrapolating a synthetic bot count. Intended-hardware capacity remains unclaimed until a controlled run on the actual PCs shows stable world saves, TPS/MSPT, resource headroom, and no unexplained state loss at the chosen beta load.

## Bug report

Date/time/timezone; candidate and six component versions; Java/Bedrock client version and device; server role/backend; dimension and approximate coordinates; player-count range; exact steps plus expected/actual state; reconnect, owner transfer, portal, or restart involved; reproducibility; inventory/entity/block before and after; sanitized monitor export; relevant log time window with chat, addresses, tokens, passwords, and keys removed; backup/restore status; severity and whether beta play should pause.
