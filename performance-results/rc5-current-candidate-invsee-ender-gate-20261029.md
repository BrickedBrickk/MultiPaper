# RC5 current-candidate InvSee++ EnderSee click-gate runtime — 2026-10-29

## Scope and isolation

- Fresh disposable fixture: `D:\multipaper-tests\system-temp\rc5-invsee-current-8c-20261029`; synthetic world `rc5_invsee_current`. The fixture was assembled from a marked disposable config template, current candidate JARs, and the exact InvSee++ plugin. No template worlds, playerdata, logs, Floodgate keys, Crafty host, live SMP, or player account data were copied/accessed.
- Java: Temurin 25.0.3+9; Node 22.12.0; 26.3 protocol data and `minecraft-protocol` from isolated temp dependencies.
- Paperclip SHA-256 on both backends: `8C28146D36F2005C6943876D2A04C654BE407F150555C1DCD1EE4216EAA1E692`.
- Master SHA-256: `1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E`.
- InvSee++ 0.31.22 SHA-256 on both backends: `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9`.
- Runner SHA-256: `E956D09EE7D8F31FAB2AD012334DA280F72FB8FBCB6A700A5F3BAB10A5A78A80`; protocol client SHA-256: `C4433DF509261681575D5F6814B94D1DA753EB866AC86903EEF9CBFB0CB5A6AC`.

## Result

**PASS for one remote EnderSee pickup denial on this exact candidate pair.** Backend B owned the target with exactly 16 diamonds in Ender Chest slot 0. A synthetic client on backend A opened InvSee++ `/endersee` and sent a protocol-valid left-pickup with the predicted changed slot and carried stack. The pre-vanilla gate denied the click and sent the item-safety system message. The target owner still reported exactly 16 diamonds. The client reported an empty carried stack and the unchanged slot contents at matching menu state ID 2; no cursor correction packet was observed (`CURSOR_CORRECTIONS 0`).

This confirms current-pair fail-closed containment for this one menu/click. It does not enable editing or prove all click types, item conservation under accepted remote operations, persistence after restart, Bedrock login, or the six-component stack. It does not fix the exact-RC4 16→32 duplication reproduction; remote edits must remain denied.

## Evidence and cleanup

Fixture result SHA-256 `760BE13033A70ACEE4D139CCF2CC7514C3B4B59D5293441B09EF55EC939421FE`; assertions `1262DD95062C369578D907805A1BF6481AC196218F0DBB13883481AA39309A32`; protocol log `8233D4292DCEF6DC3407674AAC159A39DFD60F597851940E3593A0387FC43C98`; RCON transcript `436091DCBA3CAE8D4C0F9A665FF193485587A25ED7BADBC4A06C59B35B9A3189`; Backend A log `F248152765DF2EC2EAE3A27DA9802B354C5051D33551D33A3E8046D00380D907`; Backend B log `9DBBAF6B2AE507AC9400BC0ACE380E53A14AF689E990B496725AE3873EAD7B38`; Master log `942B90A76FE09099123A7D9027B22709FBA7A7EE7545FDE794A01C7478B76A53`. All raw evidence remains under the disposable fixture and is not included in the release archive.

The synthetic player clients and disposable servers exited. Post-run audit confirmed zero Java/Node processes created by this run and zero listeners on ports 33151–33154 and 33161–33162. The probe stderr contains only Node's `punycode` deprecation warning.

## Status

This adds an exact packaged-pair containment check only. **RC5-02 remains P0 `BLOCKED`**, remote player inventory clicks remain fail-closed, and no functional InvSee++ item-conservation fix is claimed. No live system was touched.
