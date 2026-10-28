# RC5 Boat passenger runtime check

Date: 2026-10-27
Source candidate: clean-build output from `d1346b44562d5273441fd072810565cad93bb26c` plus the uncommitted Boat guard and regression tests
Fixture: `D:\multipaper-tests\system-temp\rc5-boat-passenger-20261027-184118`
World: `rc5boat1027184133`
Runtime: Temurin Java 25.0.3.9

## Result

The disposable two-backend runtime assertions passed. Backend A created one oak boat and one pig, mounted the pig on the boat, and moved the boat. Both backends reported exactly one boat and one pig, matching UUIDs, and a boat with a pig in its `Passengers` NBT. After movement, Backend B still reported the same mounted boat UUID and a position containing the commanded X/Z coordinates. This verifies a narrow spawn/mount/replication/movement sequence on both backend views.

The run does not test player passengers, dismount, chunk-boundary transfer, ownership handoff, dimension changes, restart persistence, or a client-rendered view. It did not deliberately invoke the `ClientboundSetEntityLinkPacket` non-Mob branch that caused the real Boat→Mob cast exception. The three focused source regressions cover non-Mob entity-link safety, boat passenger dependency, and Mob leash set/clear; the two clean full Java 25 builds are recorded in `rc5-boat-fix-reproducible-builds-20261027.md`. Therefore RC5-03 remains `TESTING`.

## Artifacts and checks

- Paperclip SHA-256, both backends: `D8C015EFE458CADA31113C606AB2541AF809A932ADBA514D267B55DD66B6393E`.
- Master SHA-256: `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.
- Reusable runner `scripts/Test-RC5BoatPassengerSync.ps1` SHA-256: `51A4998C1A00614046175DEC6100C4342F8827A90B95E499E5899C44E9F545F5`; PowerShell parser passed.
- RCON transcript SHA-256: `233836E566577E8AABE6B65F5C440F7F7F6C9CF248659A9537E091BFFE5E44B0`.
- Backend A/B log SHA-256: `2771554594A9FD7DE19869FA263C3FD92133AA610BC7377BE8350848BB1AC4CC` / `996CEAF392AD78B63A6214EB251C6021F8BD8D6C6C2B296DB5663C810CCED75A`.
- No Boat→Mob `ClassCastException` appeared during the runtime scenario; no such leash-link event was deliberately generated.
- The disposable Master/backends exited; zero Java processes remained and ports 33451–33454 and 33461–33462 were confirmed closed.

The first two harness attempts are retained as separate disposable fixtures. One reversed `/ride` rider/vehicle order; the next initially asserted for the literal `UUID` label even though vanilla displayed the UUID as an NBT int array. Neither is evidence of a product failure. The final run corrected these probe defects and passed.

No live Crafty/SMP service, world or player data was accessed.

## RC5-03 lifecycle extension — 2026-10-28

Extended the disposable two-backend boat runner with a temporary `RC5BoatOwnerProbe` plugin that reports the actual `NewChunkHolder.externalOwner`, plus cross-backend dismount/remount and A→B chunk-owner transfer assertions. The verified candidate was the previously reproducible Paperclip artifact SHA-256 `D8C015EFE458CADA31113C606AB2541AF809A932ADBA514D267B55DD66B6393E` and Master `E122A819950C50FF504CDC6600D6E5C4970D9D25F45AC19ED974F9D194134B7`, Java 25.0.3+9. Disposable fixture: `D:\multipaper-tests\system-temp\rc5-boat-owner-lifecycle-20261028-r2`, world `rc5boat1028020044`.

Backend A was asserted owner of chunk `(5,5)` before spawn; Backend B's probe agreed. A spawned an oak boat and pig, mounted the pig, moved the boat, dismounted and remounted it. Both backends observed exactly one boat and pig with matching UUIDs and the correct passenger relation; after dismount neither backend listed the pig as a passenger. A's force-load ticket was removed; the owner probe then observed Master assign chunk `(5,5)` to `boat-b`. B retained the same mounted boat/pig UUID and moved the boat. A reloaded the chunk as a non-owner observer, confirmed owner remained `boat-b`, and observed the boat at the new position. Backend logs had no `ClassCastException` or `handleEntityWithDependencies` failure. Result: PASS for this synthetic lifecycle.

Result file: fixture `RESULT.txt`; RCON transcript SHA-256 `DC8A80A9FF6733868757ECB91C1F27A68DF8CB9DAC182AE867E86F05A937D37F`; Backend A/B log SHA-256 `13C73083B609AE809E400EABAC4E3DA50EE5BD1E42E6F54CFB47CD907B3D6C0D` / `2DFA9CF62C2C235C742B02E79EB8A3B873CE73B20C6B26168F27186288D194EE`; Master log SHA-256 `59FD7511532DDBCA82B27D14A1F37F009FA4F4C1CE9ACFBE2D097420F71495F0`. Runner SHA-256 `36D8E399DBCBC5AA5A623DA1F3CC3DE54B04DC199E6BD28F758558B569F7640A`; probe source SHA-256 `E08E96C73201A23EE04937228C370971FE8D2303737942562321D0C18A857D2D`; test JAR SHA-256 `B28A41FE20E83DE50E0D3ECA290FCDEE3D9EB6FA9A7CF17FC0E25B34B0C55D46`.

The first attempt reached the transfer and correctly changed owner to B, but its final assertion queried A after its chunk had unloaded and got `No entity was found`; it is retained as a probe-observer mistake, not a product failure. The corrected run reloaded A and passed the non-owner observer check. Both disposable service sets shut down; the six configured ports closed and no fixture Java process remained. **RC5-03 remains P1 `TESTING`**, not `VERIFIED`: this was synthetic RCON/entity state rather than player riding or rendered client state, and does not cover restart/dimension transfer or directly force the originally logged exception branch.

## Exact current RC5 candidate lifecycle run — 2026-10-28

The parameterized reusable runner was rerun against the current clean-build pair, not its previous RC4-era fixture artifacts:

- Paperclip SHA-256 `391D91696B8C6DBBFD1A952FABF15A5F31AA2DB5D129BC742CCC279905438349`; Master SHA-256 `BB20D1935AC6A12A4B2BA0907097779BD268D4330FA8460C945354711704200C`.
- Disposable fixture `D:\multipaper-tests\system-temp\rc5-boat-current-candidate-20261028-115559`, world `rc5boat1028115644`.
- PASS: both backends observed exactly one matching boat and pig; mount, dismount, remount, A→B chunk ownership transfer and new-owner movement succeeded; former owner reloaded as non-owner and saw the same boat at its moved position. No Boat→Mob `ClassCastException` or ignored non-mob-link warning appeared. Result SHA-256 `4FC595BC0D3BCE85C86FF8A935A6CEA9161DBFEB7D830102536BAAAF5B880E65`; RCON transcript `38741BB34B59F932A14C47455CE1449FB5AF83FB70BC8DDA006334288A7C3BA7`; Backend A/B logs `3DA688C1E98E83D955B284F22DEF87170DA99A0367B9A81F8112C3B5C9386D7E` / `BAC094D75F9B1C89DCC745E00C807FA2D2D888F228E072373DC1400823ED1BE9`; Master log `96BC56FDB85B77971FAEA3E4A2C6FE6C1AE649D0096C43E4E9059CA272D081E7`.
- The current Java 25 full-build fixture's focused `MultiPaperEntitiesHandlerDependencyTest` XML reports 3 tests, 0 skipped/failures/errors; SHA-256 `51118C65B4C3E57FC83304996C50082A1BD59C8E39D28008B4531901301918B6`.
- All six disposable listeners closed, no test Java process remained. This does not deliberately reproduce the logged `ClientboundSetEntityLinkPacket` on a real player/leash or validate client-rendered riding, restart, or dimension travel. RC5-03 remains `TESTING`.

## RC5-06 current RC5 leash/ownership probe — 2026-10-28

A fresh disposable two-backend fixture ran against the reproducible RC5 Paperclip SHA-256 `F5462FDA66732D7226920B04B89F8DB45D4125746B51F4BD90BDB3D8A46167B9` and Master SHA-256 `BB20D1935AC6A12A4B2BA0907097779BD268D4330FA8460C945354711704200C`, Temurin Java 25. Fixture: `D:\multipaper-tests\system-temp\rc5-leash-transfer-20261028-current`; world `rc5boat1028174645`.

The probe created a wolf leashed to a fence knot in chunk (5,5). Both backends reported the same wolf UUID, `isLeashed=true`, a local leash-knot holder at the same fence block position, before and after A→B chunk ownership transfer. The local leash-knot UUIDs differ between backend instances; this test asserts state and block position, not identical knot UUIDs or client packet/render behavior. Result `PASS`; no failure text; fixture processes exited, listeners were closed by the runner. It is a narrow passing persistence/owner-transfer scenario, not reproduction of players' disappearing-lead symptom. RC5-06 advances to `TESTING`; do not mark verified.

Evidence SHA-256: RESULT `1383E0C533F83B935146434418A8368FD9FBBE9883BBBFBC5CC490A005F4BE8C`; RCON transcript `DB67E836BC42F14E3266924DB8C6419FCB8824CC2B81E86F96AEF4DB174D547B`; backend A/B logs `F808FE89D1A3327E7A1487000354256CF05DE5235A1D3B9C31311FC6D30CBB44` / `52496655CD4D97B0BAAE68C3F96304DBCFC16FCA70D6A0DEFE964858F2F46771`; Master log `C56D3E9010894612BA74A71560AC78250FB9795B0BDA4E4FF64E2A183F3B59F7`. Runner SHA-256 `E4E8B7FB7D3D8F86A206CE6D80CF315823206464D8E2A1E6132F127B71DBC81D`; probe source SHA-256 `B47EBB045323499AB199E84C9974666AC60522354138D7C26099D338D9A7A221`. All are synthetic fixture evidence; no live Crafty/SMP system was accessed.
