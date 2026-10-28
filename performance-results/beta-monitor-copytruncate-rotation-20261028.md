# Beta monitor same-inode log rotation regression — 2026-10-28

## Defect and correction

The incremental log reader remembered only inode and byte offset. If a logger truncated and rewrote `latest.log` on the same inode and the replacement reached at least the previous size before the next poll, the reader resumed at the old offset and silently skipped new events.

The reader now remembers a SHA-256 fingerprint of at most 256 bytes immediately before its last byte offset. If the inode, size, or boundary fingerprint indicates replacement/truncation, it restarts that file at byte zero; normal append leaves the old boundary unchanged and resumes incrementally. Offsets are maintained in binary mode to keep the fingerprint and seek position byte-exact across Windows newline conversion.

## Reproduction and verification

- Disposable fixture: `D:\multipaper-tests\system-temp\rc5-monitor-copytruncate-20261028`; no game server, RCON endpoint, live log, or production file was used.
- New regression's expected pre-fix failure: old monitor source skipped the same-inode equal-length replacement; test observed `[]` events instead of one `player_join` category.
- Updated monitor suite: **7/7 passed** on CPython 3.11.9. The added test detects the rewrite, confirms a subsequent append is read once, and asserts both player names remain absent from telemetry.
- Synthetic overhead check in the same disposable fixture: 200 iterations, 20 synthetic log events each, 10-second assumed poll. CPU `3.40625s`, wall `5.218794s`, `17.031ms` CPU per poll, estimated `0.1703%` of one core at the configured interval, peak RSS `23,924,736` bytes. RCON disabled.
- This Windows synthetic measurement does not certify Debian, target Crafty PCs, live server load, or the separate 15-minute beta overhead gate.

Source SHA-256 `BADCB3B104C419A8E015E5475AFADB45941F0C93D37091EBB51FF3DEF9EDD72B`; test SHA-256 `522EFE653BFD6AC9D5EF78253B6D9B85526A70CB4AF7496A502BFEC06FED857E`.

No live server or source log was accessed. This change affects log ingestion only; event availability remains limited to messages actually emitted by the monitored components.
