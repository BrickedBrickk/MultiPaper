# RC5 manual Crafty update and rollback plan

This is a future operator procedure for the two-PC SMP. It does not authorize an update now. Keep the running RC4 servers online while RC5 P0 inventory/chest defects and the full RC5 build/release gates remain open. Perform every step manually in Crafty during a maintenance window; this repository does not deploy or control either Crafty PC.

## Before scheduling

Proceed only when the owner approves the candidate and all of these are available:

- A published or privately delivered evaluation package with Java 25 Master and Paperclip JARs, verified per-file and ZIP SHA-256 manifests, matching source commit, release notes and a tested rollback procedure.
- Passing durable InvSee++ item-conservation tests and chest unload/ownership/restart tests on that exact artifact set. Do not use the current P0-blocked candidate.
- A maintenance window long enough to stop all four game services, verify a consistent backup, update three server JAR locations, start in order and validate before reopening access.
- An allowlisted operator/tester and a written maintenance notice. Do not ask players to test unverified data-integrity paths.

Keep `server.properties`, `multipaper.yml`, JVM flags, ports, names, plugin databases, Floodgate keys and local plugin configuration in their existing per-host/per-backend directories. Do not copy one backend's config over another. Do not include secrets or private chat in diagnostic exports.

## Capture a consistent recovery point

1. Record the current RC4 Master JAR, both backend Paperclip JARs, their SHA-256 hashes, active plugin versions and each Crafty server's data directory. Preserve copies of the exact current JARs outside the live directories.
2. Close admission for the maintenance window using the operator's established process. Stop Geyser first so no new Bedrock sessions enter; close or maintenance-lock the Java public route using the normal operator procedure. Keep backend and Master ports private.
3. In Crafty, gracefully stop Backend A and Backend B. Wait for each process to exit and confirm both have disconnected from Master. Do not force-kill during ordinary shutdown. Review the final save/shutdown messages for errors.
4. Gracefully stop Master only after both backends have exited. Confirm its process has exited before copying any data.
5. Copy the Master shared-world directory and **both** backend data directories, including server configuration, plugin configuration/data, permissions, local databases and logs needed for diagnosis, to one dated recovery set. Back up Floodgate private keys separately to a protected location; exclude them from public bundles.
6. Generate a SHA-256 manifest for the copied recovery set. Compare the manifest to the copy and test extraction/readability in a separate temporary location. Confirm it includes the Master world store and both backend directories. Never assume a backend-local world copy replaces the Master world store.
7. Keep this RC4 recovery set unchanged until the RC5 beta completes and the operator has verified the new backup policy.

If a clean shutdown or backup verification fails, do not replace JARs. Keep the candidate staged outside live directories and restore RC4 service from the untouched source directories only after verifying what stopped; escalate from the operator's maintenance procedure.

## Install and start RC5

1. Confirm all Master, backend and Geyser processes are stopped. Retain the original RC4 JARs and the verified backup set.
2. Verify the RC5 release tag, source commit, package checksum and individual Master/Paperclip checksums against the signed-off release notes. Reject a missing or mismatched file.
3. Replace only the Master executable JAR with the packaged Master JAR. Replace each backend's Paperclip JAR with the **same byte-identical** packaged Paperclip JAR. Keep directory names and Crafty commands consistent with the existing installation.
4. Preserve each backend's existing unique `my-name`, private port, Master address, JVM limits and plugin data. Do not regenerate or overwrite configurations wholesale. Change plugin JARs only if the signed-off RC5 compatibility matrix explicitly requires a version change; preserve each plugin's local database and configuration.
5. In Crafty, start Master first. Confirm both coordination and proxy listeners are ready and there are no startup/storage errors.
6. Start Backend A, then Backend B. Confirm each reaches `Done`, registers once under its expected unique name, loads the same shared world UUIDs and has no Master connection or chunk-persistence errors. Keep public admission closed.
7. Run the release's read-only/admin validation checklist: permissions and command responses; shared block/chunk state from both backends; exact chest and player inventory conservation checks supported by the candidate; dimension entry/return; plugin startup and exception review; monitor freshness and sanitized diagnostics export. Do not treat server-side state as proof of client-visible animation or successful Bedrock authentication.
8. Start Geyser last only if its Bedrock/Floodgate login, reconnect, identity and permission gates have passed for the exact candidate. Verify the Java TCP route and the selected Bedrock transport's TCP signaling plus UDP gameplay (or separately validated external signaling) independently; an older UDP-only route is not sufficient for NetherNet. Do not expose backend ports, Master coordination or RCON.
9. Admit the allowlisted beta players only after all checks pass. Keep a named operator on call and use the two-week checklist in `monitoring/BETA-TEST-PLAN.md`. Continue to describe this as an evaluation prerelease, never stable.

## Roll back

Roll back if either backend fails to register, shared UUID/state checks disagree, inventories/chests fail conservation checks, plugin or Master errors appear, backup/monitor evidence is incomplete, or a required login path fails.

1. Close admission and stop Geyser first.
2. Gracefully stop both backends and wait for them to exit and finish saves. Stop Master last. Do not force-kill unless an operator has exhausted the normal Crafty stop path and has separately decided how to preserve the possibly damaged state.
3. Copy the failed RC5 Master and both backend directories to a new, separate diagnostic snapshot. Do not overwrite the pre-upgrade backup and do not upload private keys, credentials, IPs or player chat.
4. Decide whether RC5 wrote or migrated any world/player data. If it did, or if that is uncertain, restore the Master and **both** backend data directories together from the verified pre-upgrade recovery set; do not mix RC5 Master data with RC4 backend files or vice versa.
5. Restore the saved RC4 Master JAR and the same saved RC4 Paperclip JAR to both backend directories. Verify their SHA-256 hashes before startup. Preserve the original per-server configuration, plugins and protected keys; restore those from backup only if they were changed or became inconsistent.
6. Start Master, then Backend A, then Backend B. Check registration, shared world UUIDs, critical chest/player inventories, permissions and startup logs before reopening access. Start Geyser last after its route is confirmed.
7. Keep both the failed RC5 snapshot and the untouched pre-upgrade recovery set until the operator confirms recovery and records the incident. Never attempt automatic item restoration or overwrite a live world based only on warning logs.

## Operator record

Record the approved maintenance time, release/tag/source commit, Master and Paperclip hashes, backup path and manifest hash, Crafty process exit/start confirmations, per-backend configuration identities (names/ports only; no secrets), validation results, monitor export hash, rollback decision and the operator who reopened access. Store the record with restricted access and exclude private player chat and credentials.
