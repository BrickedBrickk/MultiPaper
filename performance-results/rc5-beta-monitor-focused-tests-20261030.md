# RC5 beta monitor focused regression suite — 2026-10-30

## Command and result

Ran the focused monitor suite from the repository root after correcting Java process instance identity tracking:

```text
py -3 -m unittest scripts.tests.test_beta_monitor
Ran 18 tests in 0.183s
OK
```

Test file SHA-256: `2A65847499CDE49D0FDF99F2A81E04D131EEA451D46B101F1ACC5A55C28FA730`.
Monitor implementation SHA-256: `873843122CBD26261D2DCE2C12DD42FE5D69E157EF715FDB8F5B808297BAEF25`.

The monitor now explicitly requests each Java process's creation time when enumerating processes, so a reused PID after restart starts a fresh CPU baseline. The regression asserts this attribute is requested; the complete focused suite passes on the corrected source.

## Scope limits

This validates the current worktree's parser, sanitation, rotation, diagnostic-export and summary unit tests only. It does not measure live beta CPU/RSS/MSPT overhead, verify Crafty deployment, capture actual host/backend runtime metrics, or establish RC5 readiness. The suite ran locally without connecting to Minecraft, Crafty, RCON, or any server. No source, configuration, fixture, or binary was changed by the test run. The test may update Python bytecode cache files; the existing untracked cache directories were left untouched.
