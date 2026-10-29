# Private beta network routes

| Traffic | Protocol | Target | Exposure |
|---|---|---|---|
| Java players | TCP | PC1 Master proxy `25565` | Playit Java tunnel only |
| Bedrock players (NetherNet) | TCP signaling + UDP gameplay | PC1 Geyser Standalone; listener/tunnel port(s) per selected transport | Playit route must support both protocols; UDP-only is insufficient |
| Master coordination | TCP | PC1 Master `35353` | PC1/PC2 backend addresses only |
| Backend game | TCP | PC1 `25566`, PC2 `25567` | Private inter-process/backend network only |
| RCON, if enabled for monitoring | TCP | Each backend loopback, e.g. `127.0.0.1:25575` | Never Playit/public; host-local monitor only |
| Administration | Tailscale | PC management | Administrators only; not a player route |

The Master handles player Java TCP and sends the session to the selected backend. Geyser Standalone on PC1 sends Java traffic to the Master proxy and uses `auth-type: floodgate`. For NetherNet, Geyser also requires TCP signaling and UDP WebRTC gameplay; the prior UDP-only route is insufficient for current clients. Playit's support page states a combined TCP/UDP tunnel is Premium. Neither the account's tunnel capability nor the Bedrock route has been tested, so this remains a pre-beta gate. Do not switch back to RakNet as a release workaround without confirming client compatibility. Backend B connects to the Master on PC1 over the private LAN or Tailscale address configured identically on both backends. Do not publish backend ports or the Master coordination port. Keep Playit agent tokens and Floodgate private key outside the package.
