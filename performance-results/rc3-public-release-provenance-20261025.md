# Published v26.3-RC3 release provenance check — 2026-10-25

Queried the live GitHub REST API for `https://github.com/BrickedBrickk/MultiPaper/releases/tag/v26.3-RC3` and resolved its annotated tag.

- Release: `MultiPaper 26.3 RC3`, published 2026-10-24, prerelease=true, draft=false, 9 assets.
- Annotated tag `v26.3-RC3` resolves to commit `73095500f51c5bc95db1b0946f45bc8637c19169`.
- Published ZIP `MultiPaper-26.3-RC3-20261024.zip`: 82,392,227 bytes, SHA-256 `0F806AE2662BDFB66CEB2881DECBFCB8095F047185BC84C1AF4790151E3B4BCC`.
- Published Paperclip: 64,332,162 bytes, SHA-256 `22B1EE9737A98BA85A52AE09B6B9C6EB890331F9B7A9443BBD3CDEA9357C6460`.
- Published Master: 19,181,170 bytes, SHA-256 `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`.

All nine published assets were compared against the locally staged release by exact size and SHA-256 using GitHub's per-asset `digest`; all matched. All seven files inside the published ZIP were also compared with the corresponding published asset digests and matched. The release assets, local staged files, and immutable annotated tag remain unchanged. This verifies artifact provenance only; it does not expand the runtime compatibility evidence in `performance-results/plugin-stack-compatibility-20261024/REPORT.md`.
