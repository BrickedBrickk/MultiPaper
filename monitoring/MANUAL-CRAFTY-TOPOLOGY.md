# Manual Crafty layout for RC4 evaluation

This describes a private evaluation layout only. The user installs it manually. Keep production untouched until they review the evaluation package and choose a maintenance window.

| PC | Process | Player-facing listener |
|---|---|---|
| PC1 | MultiPaper Master | Java TCP proxy (Playit Java tunnel targets this) |
| PC1 | Backend A | Private backend-only port; never public |
| PC1 | Geyser Standalone + Floodgate key | Bedrock UDP listener (Playit Bedrock UDP tunnel targets this) |
| PC2 | Backend B | Private backend-only port; reachable from Master over private LAN/Tailscale |

Create four separate Crafty entries/directories: Master, Backend A, Geyser, and Backend B. Use Java 25. Copy the same Paperclip JAR to both backend directories, and install the same five backend plugins on each. Install Floodgate-Spigot on both backends and use the same privately transferred Floodgate key in both and Geyser. Do not install Bukkit plugins in Master. Configure Geyser's Java remote to the Master proxy and `auth-type: floodgate`.

Configure Playit with one Java TCP tunnel to PC1's Master proxy and one Bedrock UDP tunnel to PC1's Geyser port. Do not tunnel the backend game ports, Master coordination port, or RCON. Restrict Master coordination to the two backend hosts. Keep RCON disabled unless needed for the private monitor; if enabled, bind it to loopback and give the monitoring process a protected environment variable. The PC2 monitor exports sanitized telemetry for manual merge on PC1.

Before first launch, stop any existing process using a chosen port, verify the prior world/config backups can be read, and confirm the Master and each backend have separate data directories. Start Master, then both backends, then Geyser. Verify registration/readiness and the actual Playit routes before any player connects. Shut down Geyser, backends, then Master after saving; wait for save completion before taking a consistent backup. Restore the Master and both backend snapshots as one set while all game processes are stopped, then start in the same order.

This is not proof that a proxy's online-mode or Bedrock identity-forwarding path works. Keep the beta allowlisted until a real authenticated Java login and a real Bedrock/Floodgate identity/reconnect both pass. Use the 14-day protocol and bug-report format in `BETA-TEST-PLAN.md`.
