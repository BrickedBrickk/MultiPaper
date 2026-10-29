# RC5 blocked local review bundle v39 — superseded — 2026-10-29

V39 archive SHA-256 `1756dc3b878b7dad78ecb7feb9311ba403ef24ea168a353785b09a1b3223c25d` (82,724,228 bytes) and its 52 payload hashes match. A post-build README audit found that the packaging helper appended a stale prior source-commit checkpoint after the newly generated README. The package remains blocked/not for installation, but its README provenance is internally inconsistent. **Do not use v39; v40 supersedes it.** Preserve v39 for audit history.

The corrected builder now creates a fresh bounded README without accumulated prior-candidate text and stages the current Master/Paperclip pair plus persistence report. Detailed v40 verification is recorded in `performance-results/rc5-blocked-local-bundle-v40-20261029.md` after that archive is built.
