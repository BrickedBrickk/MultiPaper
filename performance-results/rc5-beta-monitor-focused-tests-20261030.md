# RC5 beta monitor focused regression suite — 2026-10-30

## Command and result

Ran the focused monitor suite from the repository root after adding an explicit Java restart/PID reuse scenario:

```text
py -3 -m unittest scripts.tests.test_beta_monitor
Ran 18 tests in 0.193s
OK
```

Test file SHA-256: `82F1C0892209180CFBABD2CD70129EFF097F66FE8E2A3CF2B4F583CAC9BEB962`.
Monitor implementation SHA-256: `873843122CBD26261D2DCE2C12DD42FE5D69E157EF715FDB8F5B808297BAEF25`.

The monitor now explicitly requests each Java process's creation time when enumerating processes, so a reused PID after restart starts a fresh CPU baseline. The regression changes the fake process creation time while retaining its PID and verifies the first sample for the restarted process is unavailable, then the next interval reports the measured CPU value. The complete focused suite passes on the corrected source.

## Synthetic overhead measurement

On Python 3.13.5, `py -3 scripts/Measure-BetaMonitorOverhead.py --iterations 200 --events-per-interval 20 --poll-seconds 10` exited 0 with 2.40625 process CPU seconds over 3.343762 wall seconds, 12.031 ms CPU per synthetic poll, and estimated 0.1203% of one core at the assumed 10-second interval. RCON was disabled and the runner used only temporary synthetic logs/metrics. RSS was unavailable (`null`) because `psutil` is not installed in this environment. This is not a Debian/Crafty/target-PC or live-RCON measurement; deployed-host overhead remains a manual beta gate.

## Scope limits

This validates the current worktree's parser, sanitation, rotation, diagnostic-export and summary unit tests only. It does not measure live beta CPU/RSS/MSPT overhead, verify Crafty deployment, capture actual host/backend runtime metrics, or establish RC5 readiness. The suite ran locally without connecting to Minecraft, Crafty, RCON, or any server. No source, configuration, fixture, or binary was changed by the test run. The test may update Python bytecode cache files; the existing untracked cache directories were left untouched.
