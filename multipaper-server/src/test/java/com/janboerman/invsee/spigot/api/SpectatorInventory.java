package com.janboerman.invsee.spigot.api;

import java.util.UUID;

/** Shared public target-identity API for InvSee++ main and ender inventory views. */
public interface SpectatorInventory {
    UUID getSpectatedPlayerId();
}
