# RC5 two-backend player-session registration runtime — 2026-10-29

## Purpose and isolation

The current backend sender compiled, but no paired backend↔Master runtime proof had been retained. This disposable run checked that each current Paper backend negotiates the session capability and submits a live player's session epoch; temporary Master instrumentation recorded acceptance immediately before reply send. It reused the existing exact-plugin simultaneous InvSee gate schedule to put both backends and the owner-validated click-containment path under actual protocol-client logins.

Fixture: `D:\multipaper-tests\system-temp\rc5-session-registration-runtime-20261029`, cloned from a marked synthetic fixture. Both backend `level-name` values were changed only in this clone to `rc5_session_registration_20261029`. No Crafty host, live SMP process, player, or world was accessed.

## Artifacts

- Both backend Paperclip JARs: SHA-256 `99B24AE11909C246C040D137CD3528A6C3F6EBC052244C523AE2250A9CED5E85` (the twice-clean-built Java 25 candidate).
- Diagnostic Master JAR: SHA-256 `93FF3AB7E6F2D1DC7192070E90E54F8DD334D3247E74A22F72223B0CAF3E257C`. It was rebuilt from the current session-registration Master source with one temporary `System.out` line in `RegisterPlayerSessionHandler`, recording backend name, player UUID, session UUID, and returned acceptance value. No protocol or registration logic was changed. The temporary source line was removed immediately after building; source hash was restored to `F5E48D233BCBE99449542194B1770574BA09C5293889901DCDB2D5A3151C2A14`. The prepared worktree's uninstrumented Master JAR was rebuilt afterward and matched the pinned hash `1E2463A42CBE42CEF5DCF490461E3C956835CF4C43FD41D113013B885CE0360E`.
- Temporary diagnostic statement, inserted immediately before the existing `connection.sendReply(new BooleanMessageReply(accepted), message);` line:

  ```java
  System.out.println("RC5_SESSION_REGISTRATION_RESULT backend=" + connection.getBungeeCordName() + " player=" + message.playerId + " session=" + message.sessionId + " accepted=" + accepted);
  ```

- Diagnostic-only Master build command: Java 25 / Gradle 9.8.0, `:MultiPaper-Master:shadowJar --offline --no-build-cache --no-daemon --max-workers=1 --console=plain`. Then restore the source and rebuild the normal Master before reusing the prepared worktree.
- Runtime: Temurin Java 25.0.3.9; exact InvSee++ 0.31.22 plugin and existing pinned protocol probe. Runner: `scripts/Test-RC5InvSeeRemoteClickGate.ps1`, SHA-256 `E956D09EE7D8F31FAB2AD012334DA280F72FB8FBCB6A700A5F3BAB10A5A78A80`; client probe SHA-256 `C4433DF509261681575D5F6814B94D1DA753EB866AC86903EEF9CBFB0CB5A6AC`.

## Results

The three synthetic clients reached `READY`: one on backend `race-a`, two on backend `race-b`. Master emitted exactly three `RC5_SESSION_REGISTRATION_RESULT` records; **all three handler decisions were `accepted=true`**, with three distinct player UUIDs and three distinct session UUIDs. Master only accepts these requests after the session-capability negotiation and current owner checks, so the log confirms both backend senders reached the negotiated registration handler. The backend callback's receipt of the Boolean reply was not separately instrumented; the Master line is emitted immediately before sending it.

The simultaneous owner/remote pickup schedule also passed its existing containment assertions: the remote click was denied; after both viewers closed, the target had 0 diamonds, viewer A 0, viewer B 16, total **16**. This is a containment result, not functional remote editing.

Machine evidence is retained at `D:\multipaper-tests\system-temp\rc5-session-registration-runtime-20261029\rc5-gate-runtime-logs-reusable-005700`:

| File | SHA-256 |
|---|---|
| `master.out` | `3341DAF9C1198611ACE7ED492B32546BC5F4D29EC3EDAB1B1DC6DF5BC6A7FD7C` |
| `master.err` | `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855` |
| `backend-a.out` | `991C17734D41A0EA9D2093CB83C4E69EA9D1CAB2198C1E77EA9BB73D51ABDA07` |
| `backend-b.out` | `D39E4F9C18EE5158DCAF5DB312BB13977F71B6B96190DDBA826144A69AC97E91` |
| `probe.out` | `CB4183116D417ED914AE8B3B02784CDB74103EAC04104D1946BCAA664F60A7B0` |
| `assertions.txt` | `ADD778C3CDBEA0B3D3D11C348CC9580B03A073256F8070DE3A4ADCE150338BBF` |
| `RESULT.txt` | `E6F8F78978CE6AB1382D75E56E7583608D76CB004519E6E225B79C35CAC66861` |

All six configured fixture ports (33151–33154, 33161–33162) were closed after runner exit. No fixture Java/Node process remained. The diagnostic Master build log and restored-source rebuild log are retained at the fixture root.

## Limits and RC5-02 status

This proves Master-side acceptance for three initial backend login sessions and exercised the simultaneous fail-closed click gate. It does not prove backend callback receipt, test reconnect with the same player UUID, ownership transfer after reconnect, rejection/timeout handling, or a caller awaiting registration completion before a transaction. The backend registration API remains asynchronous; any future transaction must wait on an explicit confirmed-current-session readiness result. There is still no target-owner prepare/attestation, editor cursor escrow, cross-owner durable transaction, or functional InvSee edit path. Exact RC4 16→32 duplication is not fixed; **RC5-02 remains P0 `BLOCKED`** and remote edits must stay fail-closed.
