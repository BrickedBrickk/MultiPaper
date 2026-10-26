# Consistent backup and restore

Treat the Master and both backend data directories as one save set. A usable backup contains the Master shared-world storage plus each backend's `server.properties`, `spigot.yml`, `multipaper.yml`, plugin JAR/config/data, and any local files required by the server. The two backend world copies are not substitutes for the Master world store.

## Before a backup

1. Announce maintenance and stop new joins. Stop Geyser first, then save/stop Backend A and Backend B, and stop the Master after both backends have disconnected and their saves have completed.
2. Confirm all Java processes for this test deployment have exited. Do not copy changing region files while any related process is running.
3. Copy the three data directories to a dated backup destination. Preserve permissions and timestamps. Do not include `key.pem`, RCON passwords, service environment files, or SSH credentials in a diagnostic bundle; keep Floodgate keys in a separate protected backup.
4. Generate SHA-256 checksums for the backup files and compare a sample (including region data, `level.dat`, plugin configs, and all server JARs) against the source. Keep the manifest with the snapshot.
5. Verify recovery by extracting a copy to a new private disposable directory and checking the copied checksums. Never point two live backends at the same world files.

## Restore

1. Stop Geyser, both backends, and Master; verify their processes have exited.
2. Preserve the failed state as a separate dated snapshot for diagnosis. Do not overwrite it in place.
3. Restore Master and both backends from the **same** verified backup set. Restore keys from their protected key backup only if needed; never put key material in the public evaluation ZIP.
4. Verify checksums, private port bindings, Master/backend names and the same shared world UUID before admitting players.
5. Start Master, then both backends, then Geyser. Check ready logs and inventory/world state with one allowlisted tester before reopening the server.

For a suspected bad change, restore the complete three-part snapshot rather than mixing a new Master store with older backend data. Keep the previous known-good snapshot until the restored world has been played and saved successfully.
