# Private beta network routes

| Traffic | Protocol | Target | Exposure |
|---|---|---|---|
| Java players | TCP | PC1 Master proxy `25565` | Playit Java tunnel only |
| Bedrock players | UDP | PC1 Geyser Standalone `19132/udp` | Playit Bedrock UDP tunnel only |
| Master coordination | TCP | PC1 Master `35353` | PC1/PC2 backend addresses only |
| Backend game | TCP | PC1 `25566`, PC2 `25567` | Private inter-process/backend network only |
| RCON, if enabled for monitoring | TCP | Each backend loopback, e.g. `127.0.0.1:25575` | Never Playit/public; host-local monitor only |
| Administration | Tailscale | PC management | Administrators only; not a player route |

The Master handles player Java TCP and sends the session to the selected backend. Geyser Standalone on PC1 sends Java traffic to the Master proxy and uses `auth-type: floodgate`. Backend B connects to the Master on PC1 over the private LAN or Tailscale address configured identically on both backends. Do not publish backend ports or the Master coordination port. Keep Playit agent tokens and Floodgate private key outside the package.
