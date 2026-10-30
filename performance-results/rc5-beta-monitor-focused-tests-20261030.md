# RC5 beta monitor focused regression suite — 2026-10-30

## Command and result

Ran the existing focused monitor suite from the repository root:

```text
py -3 -m unittest scripts.tests.test_beta_monitor
Ran 18 tests in 0.208s
OK
```

Test file SHA-256: `488E11EE72B2AF736A8FDC55791D5E5C597FEE35C1CD2DC44308AD2B02CB62FA`.
Monitor implementation SHA-256: `353B02E50F591218594735124B67851B4726108D6987268CE8A04A085C657197`.

## Scope limits

This validates the current worktree's parser, sanitation, rotation, diagnostic-export and summary unit tests only. It does not measure live beta CPU/RSS/MSPT overhead, verify Crafty deployment, capture actual host/backend runtime metrics, or establish RC5 readiness. The suite ran locally without connecting to Minecraft, Crafty, RCON, or any server. No source, configuration, fixture, or binary was changed by the test run. The test may update Python bytecode cache files; the existing untracked cache directories were left untouched.
