package puregero.multipaper;

/** Monotonic inbound Ender Chest update sequence for one owner session. */
public final class EnderChestSyncRevision {
    private long lastAccepted = -1;

    public synchronized boolean accept(long revision) {
        if (revision < 0 || revision <= lastAccepted) return false;
        lastAccepted = revision;
        return true;
    }

    public synchronized long lastAccepted() {
        return lastAccepted;
    }
}
