# RC4 local capacity-harness investigation — 2026-10-24

## Scope and boundary

This was a local Windows disposable run only, against the exact RC4 Master (`2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`) and Paperclip (`C124A7C775814FF9D5E537A2277084360F84DC67F700E158799B0D1E431C26D2`) on two local backends. The five backend plugins were enabled; Geyser Standalone also ran separately. No Crafty, production, or target PC was accessed. This run is **not** intended-hardware capacity evidence and produced no supported player-count result.

## Findings

- The 26.3 protocol codec preflight passed. The capacity PowerShell script parsed, and all five synthetic clients reached login, received 117 chunks each, and were staged at authoritative RCON positions.
- In a 30-second moving run, the client sent 2,788 position packets and completed a 31-second soak without disconnects, but RCON showed no authoritative movement for any client. GrimAC repeatedly reported `failed Simulation`; the server kept the players at their staged locations. This is a malformed protocol workload, not capacity evidence.
- A 70-second stationary run sent roughly 20 Hz player-input, tick-end, and unchanged-position packets. GrimAC still disconnected all five clients with `disconnect.timeout` about 60 seconds after login. The server logged initial `moved too quickly` corrections and a `GroundSpoof` alert. This client is not equivalent to an idle vanilla player and the run is invalid.
- A Mineflayer 4.39 client using protocol 1.21.8 was also rejected immediately with `multiplayer.disconnect.invalid_player_movement`; it cannot serve as the physical client for this 26.3 test setup.
- A temporary attempt to add regular input/tick-end and position heartbeats to `Run-ProtocolMovingClients.js` did not correct these GrimAC failures. That unverified harness edit was reverted; the tracked harness file is unchanged.
- Server TPS/MSPT during these short invalid runs looked healthy, but there was no valid workload and no controlled plugin/no-plugin comparison. No CPU, RAM, GC, network, synchronization-latency, or disk result from these runs should be used for release guidance.

## Cleanup and retained evidence

Both backends were save-flushed/stopped; Master and Geyser were stopped; fixture listeners were verified closed. The disposable fixture and logs remain in `%TEMP%\multipaper-26.3-rc4-plugin-runtime-f891db1`. Relevant client logs are `capacity-63a88fed67fc47f48f95b04772ff4803.out`, `capacity-56ff21fc6339468e8e60897ee4867a75.out`, and `capacity-0bfbb481ba1b41bd832b9377463a8a5a.out`; server console output is under `capacity-backend-*.stdout.log`.

## Next valid path

Capacity certification still needs a player implementation that passes GrimAC without exemptions and whose movement the two backend RCON views confirm. A real supported Java client would establish representative movement; a synthetic client must reproduce supported client physics/input and pass the same checks. Then run randomized, isolated 5/10/20-client comparisons with and without all plugins, collecting per-core CPU, RAM, GC, network, tick, disk, and synchronization metrics. Target-PC tests remain disallowed while those hosts run production services; localhost measurements must remain labeled simulations.
