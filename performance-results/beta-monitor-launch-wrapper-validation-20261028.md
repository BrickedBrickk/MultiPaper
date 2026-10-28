# Beta monitor launcher wrapper validation — 2026-10-28

## Change

Added the missing `monitoring/start-monitor-pc2.sh` wrapper referenced by the setup guide. It requires a private config path, verifies the collector and config files exist, requires Python 3.10+, and `exec`s the collector with all remaining arguments. The local README working tree documents invoking it via `sh`, so package file mode is not required; the broader README edits were left uncommitted to preserve pre-existing changes. It never starts, stops, or configures a game service.

## Verification

- Existing disposable Python monitor suite: 7/7 passed on Python 3.11.9.
- PC2 launcher: Git Bash `-n` syntax check passed. Windows Git Bash had no `python3`, so a temporary `python3` shim pointed to the installed Python 3.11.9. The wrapper's `--once` completed and wrote only synthetic monitor output under `%TEMP%\rc5-monitor-wrapper-smoke`; the no-argument path returned documented usage exit 2.
- PC1 PowerShell wrapper: `--once` completed with a temporary sanitized config and wrote metrics for Master, backend and Geyser under `%TEMP%\rc5-monitor-wrapper-pc1-smoke`.
- `git diff --check` passed for the changed wrapper and README.

This verifies Windows-side isolated wrapper behavior and POSIX shell syntax through Git Bash. No Debian host was available, so Linux execution, systemd integration, Crafty-host metrics/permissions, and the documented 15-minute overhead gate remain unverified. No live log, RCON, game server, or Crafty host was contacted.
