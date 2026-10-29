# RC5 session reconnect and backend registration runtime — 2026-10-29

## Scope

Ran the focused `scripts/Test-RC5PlayerSessionReconnectRuntime.ps1` test in the already-marked disposable fixture `D:\multipaper-tests\system-temp\rc5-session-registration-runtime-20261029`. No live Crafty process or world was contacted. The client performed three logins with the same offline profile: initial login to backend A, disconnect and reconnect to A, then disconnect and login to backend B. This exercises reconnect and registration on a different backend; it is not a real-time transfer of a continuously connected player.

## Pinned inputs and verification

- Both backend Paperclip JARs: SHA-256 `99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85`.
- Diagnostic Master JAR: SHA-256 `93FF3AB7E6F2D1DC7192070E90E54F8DD334D3247E74A22F72223B0CAF3E257C`.
- Java: Temurin 25.0.3+9.
- Node fixture syntax and PowerShell AST parsing passed before execution. Runner completed **PASS** and checked all fixture listeners were closed.
- Master accepted exactly three registrations, ordered `race-a,race-a,race-b`, all with player UUID `fcb13abe-eaad-3c79-8de1-ce895434cb2a`, distinct session UUIDs `b3592470-b44f-42ca-bad7-62fc135c425c`, `71f74c0b-c62b-40d4-bf39-70f4ffa41dac`, and `eceabd9e-3177-49be-9add-bfd989f42ac6`.
- Hashes: `master.out` `A652771A5635BC9FC36211C6950478A96E6880C5369E222F0ED831AB012465DA`; Backend A log `99DE6DF80BE00A933FBF44EA6D093354A909B0F98C79738A5E9F74B0498CF0BA`; Backend B log `89D9CE0CF0884EC76B39BB7AFFCED8A07115B874991C75F70D6B49B62DDB3CDF`; `probe.out` `446C2F28A5DA86EF3B7CE4425365A6AB4F0FDD271CA950252F65F366FBF60A66`; `RESULT.txt` `25BDF351AB8FB98B3E9B10B37CCB8B81440B82645FDDF12422DDD87EDA9F9ACD`.

## Harness attempts and limits

The first probe attempt used a 19-character Minecraft username; the backend rejected the hello packet before login. A later attempt with the corrected 12-character name completed all three stages but the runner treated Node's existing `DEP0040` punycode deprecation warning as an error. The runner was narrowed to tolerate only that exact warning and the final run passed. Neither failure indicates a product defect.

Master instrumentation logs acceptance immediately before sending the response. The run does not confirm backend receipt of that response or readiness callback, test actual InvSee clicks, remote prepare/attestation, cursor handling, cross-owner commit, or durable item conservation. Therefore RC5-02 remains P0 BLOCKED and remote edits remain fail-closed. This result adds no chest-causation evidence: the Crafty1 no-holder/read evidence and selected 273-read chest persistence regressions remain as recorded in the tracker; the unavailable newly-uploaded log bytes have not been rehashed. No source JAR was changed.

The disposable Master shut down gracefully. Post-run checks found no listeners on ports 33151–33154 or 33161–33162 and no fixture Java/Node process. Logs are retained under the fixture path above.
