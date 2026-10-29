# RC5 official component availability recheck — 2026-10-29

## Scope

Read official metadata endpoints only; no JARs were downloaded or installed this turn. Existing local artifact bytes were rehashed where available. This identifies current release-channel candidates; it does not verify the six-component stack on RC5.

## Official current versions and artifact identity

| Component | Official metadata result | Local artifact evidence |
|---|---|---|
| Geyser Standalone | GeyserMC Download API latest: 2.12.1 build 1276, timestamp 2026-10-28 11:54:25 UTC. | Existing marked-disposable JAR `Geyser-Standalone-2.12.1-b1276.jar`: SHA-256 `1DD1DE0D56AD5F50D31FEFF2E18F6F71DE50BC142853131D473BFD71D9E3809F`, matching API. |
| Floodgate Spigot | GeyserMC Download API latest: 2.2.5 build 141, timestamp 2026-09-17 14:53:03 UTC; Spigot SHA-256 `21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E`. | Existing `Mods To Check/floodgate-spigot.jar` rehashed to the same SHA-256. |
| ViaVersion | Official GitHub latest stable release: 5.12.2, published 2026-10-27; asset SHA-256 `24E4876FEFE73E08A4C87CD6C457EE0916E62DD69656631CAF41E26F909D504B`. | Existing marked-disposable `ViaVersion-5.12.2.jar` rehashed to the same SHA-256. |
| ViaBackwards | Official GitHub latest stable release: 5.12.2, published 2026-10-27; asset SHA-256 `9A148A80EA22074F4BD1626CDBE254A4CD998BF44E7CEAFF4E285A1B6B35DA42`. | Existing marked-disposable `ViaBackwards-5.12.2.jar` rehashed to the same SHA-256. |
| InvSee++ | Modrinth latest release: 0.31.22, ID `LmdQx1rG`, published 2026-10-15; official file SHA-512 `E86454AEB5F5A21C6918974CBD3F838D7C21092AE701F1380F035F247F663C968480419E7B0E2833534D1BE414454D22FE960393FE9AAA960650C30F62DB9683`. | Existing `Mods To Check/InvSee++.jar` rehashed SHA-256 `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9`; prior artifact report records the matching Modrinth SHA-512. This remains the version with the protocol-valid RC4 16→32 duplication reproduction. |
| GrimAC Bukkit | Modrinth latest **release** remains 2.3.74. A newer 2.3.75-1299a2a Bukkit artifact exists as **alpha**, ID `QNc4r5Qj`, metadata includes Minecraft 26.3, timestamp 2026-10-28 04:12:54 UTC; official SHA-512 `C0D42ABCFFC0698FC6A66D7063A2B2892BFC4307031B8D22BA14BF449F528C212F8F987E1DD0E6F0CE9F17A73307770373F6A0A8722573973C447F4789328E4B`. | Existing stable `Mods To Check/grimac-bukkit-2.3.74.jar` rehashed SHA-256 `3AC71C0B6EF8AB586C1DD3AB4C0B3254394EF39D048E4985EFB2898B9FEF628C`. Existing disposable alpha `grimac-bukkit-2.3.75-1299a2a.jar` rehashed SHA-256 `080E01C5545695B7E755F705D73DA49C5EAE9B86D67F1425F88F2C7B414F8DA9` and SHA-512 matching Modrinth. Keep the release pin at 2.3.74 until alpha is deliberately selected and exact-stack tested. |

## Official metadata endpoints

- GeyserMC: `https://download.geysermc.org/v2/projects/geyser/versions/latest`, then `/versions/2.12.1/builds/latest`; artifact download/digest metadata is available under `/versions/2.12.1/builds/1276/downloads/standalone`.
- Floodgate: `https://download.geysermc.org/v2/projects/floodgate/versions/latest`, then `/versions/2.2.5/builds/latest` (`downloads.spigot.sha256`).
- ViaVersion and ViaBackwards: `https://api.github.com/repos/ViaVersion/ViaVersion/releases/latest` and `https://api.github.com/repos/ViaVersion/ViaBackwards/releases/latest`; immutable release pages [ViaVersion 5.12.2](https://github.com/ViaVersion/ViaVersion/releases/tag/5.12.2) and [ViaBackwards 5.12.2](https://github.com/ViaVersion/ViaBackwards/releases/tag/5.12.2).
- Modrinth: `https://api.modrinth.com/v2/project/invsee-plus-plus/version` and `https://api.modrinth.com/v2/project/grimac/version`.

## Compatibility boundary

The current selected conservative candidate set is Geyser 2.12.1 build 1276, Floodgate 2.2.5 build 141, ViaVersion/ViaBackwards 5.12.2, InvSee++ 0.31.22, and GrimAC stable 2.3.74. Only artifact metadata/hashes were checked here. Existing exact-stack runtime evidence is for the older versions documented in `docs/RC5_COMPATIBILITY_MATRIX.md`; no current six-component RC5 startup was run, no credentials/key material were read, and there was no Java/Bedrock login or route test. Keep those manual gates open. The newer GrimAC alpha is deliberately not silently substituted.
