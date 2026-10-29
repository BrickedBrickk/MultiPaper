# RC5 backend session-ack readiness runtime — 2026-10-29

## Scope

This disposable paired runtime closes the callback-receipt gap for the player-session readiness prerequisite. A synthetic offline client logged into Backend A, disconnected and reconnected to A with the same UUID, then connected to Backend B as owner. The actual backend callback logged Master acceptance and queried its local readiness state after completing the callback.

The Paperclip was diagnostic-only: it was built from current source base `04724a62e188d5f99c20435a6a47427d09de5d1e` plus the five implementation/test overlay files in commit `f59ae3833b8faa4344c407a04beba98978f79bea`, with one temporary INFO log added after `PlayerSessionRegistrationState.complete`. The log recorded the callback's Boolean reply, whether the session/generation was still current, and whether the exact session was then ready. No registration logic was changed for the diagnostic. Diagnostic Paperclip SHA-256: `D874ACD1E3577057602A9E67B6B51C7FA2AD682F9446A2F07EB30C50C478C534`. The matching normal Master was `multipaper-master-3.0.0.jar`, SHA-256 `E9CEE0B236779A89DDBD1B84D1F06D3EDB75B8374DE28264B4CE04CF6F78FF0E`.

The diagnostic was built with Java 25 / Gradle 9.8 using `:multipaper-server:createPaperclipJar --offline --no-build-cache --no-daemon --max-workers=1 --console=plain` (build log SHA-256 `F82195733ED02E4E75BB7B496A2FBA7A4B832057B1CEDDBC3B44B279727D3B8D`). The temporary source edit was restored byte-for-byte (source SHA-256 `723823FCDC7204580E4CE02D788506CDFE95A733FF66424D22BF2F7C6C7FCBA8`), as was the normal candidate Paperclip `4180250D05A570009F30349A0251A4924D11D403ED7E4C65CF5686C18B204E7F`. The diagnostic JAR is retained outside the repository under `C:\Users\elibo\AppData\Local\Temp\rc5-full-current-04724-20261029\session-ack-diagnostic-artifacts`; it is **not** a candidate or release binary.

## Result

The corrected runner passed. Its protocol probe observed `A_INITIAL`, `A_RECONNECT`, `B_OWNER_TRANSFER`, and `SESSION_RECONNECT_SEQUENCE_COMPLETE`. Backend logs contained three `RC5_DIAG_PLAYER_SESSION_ACK` records for the same player UUID `ad3a3453-052f-36f0-8dc1-059401c44fab`: two from A and one from B. All three reported `accepted=true current=true ready=true`. The runner result is `PASS` at `D:\multipaper-tests\system-temp\rc5-session-ack-current-20261029\session-reconnect-runtime-045452\RESULT.txt`.

The runner `scripts/Test-RC5PlayerSessionReconnectRuntime.ps1` now has an explicit `-VerifyBackendSessionAckReadiness` mode that checks the backend callback/readiness markers. This avoids requiring special Master-side instrumentation; its original Master-instrumentation mode remains available. Runner SHA-256 after the change: `D6740A146891E83E15BAEB6DF5CFB8941EB948A5A105E47D8A00DB66F6693156`.

| Captured file | SHA-256 |
|---|---|
| `backend-a.out` | `038B4FC43484F1FD684A2A0FD5871A66E0809CE7BB754BFC4313998700068314` |
| `backend-b.out` | `385746914AC288F4083F9BE147E7D498170308DA8FCC2B6DE2EDEDA53CC5C9AD` |
| `master.out` | `083E5A4353E5C717B2B7377987DE130B885335889498D1733501777C0497EE27` |
| `probe.out` | `7D8E34D4FFA2942ABFAC3E3FFB367C652BB87CE1006F74977FC273E83B926DB3` |
| `probe.err` | `B1C73C9FE4F9C72EED94D3BBB65424AD33B55BBDC38C14D6CD21C9EBE17D1DD6` |
| `RESULT.txt` | `D259BB78728BA8F3E96B69992833B7403C91C6B13D52F0BE9CEB4DE6255A6B7E` |

All fixture Java and Node processes exited, and listeners on ports 33151–33154 and 33161–33162 were confirmed closed. The test used a marked disposable fixture and a newly named world; the earlier fixture and its world were not modified. No live server, Crafty host, or player data was accessed.

## Limits

This verifies the backend callback can receive accepted replies and mark the exact session ready through initial login, same-backend reconnect, and owner transfer in this synthetic schedule. It does not implement or verify InvSee++ owner prepare/attestation, click identity, cursor escrow, atomic durable click commits, successful remote edits, item conservation, or crash recovery. The remote-click gate remains fail-closed; **RC5-02 remains P0 `BLOCKED`**. The exact diagnostic JAR must not be distributed.
