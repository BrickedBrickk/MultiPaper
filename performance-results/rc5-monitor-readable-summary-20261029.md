# RC5 beta monitor readable daily summary — 2026-10-29

## Change

The monitor now writes a UTF-8 `summaries/YYYY-MM-DD.txt` beside its JSON summary. The text view contains the UTC summary date/time, safe server identifier and role, latest available Paper/RCON and host/Java-process fields, explicit `unavailable` values, and categorized event counts present in retained event records. It labels network throughput as host-wide. The current day's JSON and text summaries refresh at startup and every five minutes. Both formats are included in the sanitized export allowlist; monitor configuration, raw Minecraft logs, chat, IPs, and raw player names remain excluded.

## Verification

- Focused Python suite: **10/10 passed** with `python -m unittest scripts.tests.test_beta_monitor -v`.
- Coverage includes human-readable values and missing metrics, categorized counts, absence of hashed session identifiers/private config in the text, inclusion in the sanitized ZIP, five-minute refresh cadence, bounded rotation, copy-truncate detection, and existing parsing/export behavior.
- `python -m py_compile scripts/multipaper-beta-monitor.py` passed.
- `git diff --check` passed on the reviewed monitor/docs files (Git reports only existing LF→CRLF normalization notices).

## SHA-256

- `scripts/multipaper-beta-monitor.py`: `322FB9D3FBA814C1453629F57B4CFDE1992227F593165FA92BBFED9074C45E43`
- `scripts/tests/test_beta_monitor.py`: `F4E01D968CFB484A6208CD4B5ADE70E0C0E1F235555E060A2283104C10194723`
- `monitoring/README.md`: `E4E2310B7C291DC9273D93989BC64666384D0E4E782DBB71AE82883B3AF097C0`

## Limits

This is synthetic unit/format/export evidence. It does not measure runtime overhead or resource collection on either Crafty PC, validate the RCON path, or prove that normal MultiPaper builds emit every categorized ownership, chunk, tick, entity, or plugin event. Event counts describe the event records in the bounded active log file at summary time; absence is not evidence that an event did not occur. Do not present this monitor work as clearing the P0 InvSee++ item-conservation or chest-integrity gates. No live server or Crafty host was accessed.
