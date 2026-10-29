# RC5 current-worktree Master clean reproducible builds — 2026-10-29

## Scope and command

Two sequential clean, uncached builds were run against the same current worktree state at HEAD `95776c57f5a62617c881e9c1a9e55cd2f429083e`, with no source edits between passes. Temurin Java 25.0.3.9, Gradle 9.8, offline dependency resolution, isolated Gradle home `D:\multipaper-tests\gradle-home-rc5-invsee-focused-20261029`, one worker, no daemon, and no build cache were used.

Tasks in each pass: `:MultiPaper-Master:clean :MultiPaper-Master:test :MultiPaper-MasterMessagingProtocol:test :MultiPaper-Master:shadowJar` with `--rerun-tasks`.

## Results

- Pass 1: BUILD SUCCESSFUL, 4m33s. Master 88 tests across 10 suites and protocol 13 tests across 3 suites; 0 failures/errors/skips. Full command log SHA-256 `DDE5305C3D4A6C7085010BC363D6C02066F429D582B29391326127EEAA0B4618` at `D:\multipaper-tests\rc5-master-clean-current-20261029-pass1.log`.
- Pass 2: BUILD SUCCESSFUL, 6m14s. Master 88 tests across 10 suites and protocol 13 tests across 3 suites; 0 failures/errors/skips. Full command log SHA-256 `DF7DEF358B0F62B5743EB6D74B70A5339D9D72964DD599D233F18403515F1463` at `D:\multipaper-tests\rc5-master-clean-current-20261029-pass2.log`.
- Both shaded outputs `MultiPaper-Master/build/libs/multipaper-master-3.0.0.jar` are byte-identical: SHA-256 `85578CE7DE91A57944DEB12C811AC1C4C648638D62937BC6EBB1E325D0015578`.
- This reproduces Master/protocol outputs only. It is not a full Paperweight/Paperclip build, two-backend release candidate, exact six-plugin stack, or installable package. No JAR was staged or installed.

## Data-integrity coverage and limitations

Passing tests include the accepted asynchronous chunk-write/disconnect barrier, delayed former-owner write fencing, eight concurrent forced chest readers behind an announced pending save, exact chest NBT after Master restart/new-owner claim, Master process restart recovery, playerdata journal crash cuts, stale before-image rejection, and reply-future lifecycle. Existing hash-pinned paired backend 273-read chest schedules remain selected synthetic cases and are not evidence of the real players' chest-loss cause or recovery.

RC5-02 InvSee++ cross-backend item conservation remains P0 `BLOCKED`: these Master tests do not create a live owner attestation/reservation protocol or exercise accepted InvSee++ menu/cursor edits. Keep remote clicks fail-closed. RC5 is not cleared for installation or publication. No live server/world was accessed, and all build/test processes exited after each pass.
