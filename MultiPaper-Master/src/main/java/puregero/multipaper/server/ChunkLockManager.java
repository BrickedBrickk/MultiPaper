package puregero.multipaper.server;

import java.util.concurrent.CompletableFuture;

public class ChunkLockManager {

    private static final WriteLockManager locks = new WriteLockManager();

    public static void lockUntilWrite(ServerConnection connection, String world, int cx, int cz) {
        locks.lockUntilWrite(connection, world, cx, cz);
    }

    public static void writeStarted(ServerConnection connection, String world, int cx, int cz) {
        locks.writeStarted(connection, world, cx, cz);
    }

    public static void writeCompleted(ServerConnection connection, String world, int cx, int cz) {
        locks.writeCompleted(connection, world, cx, cz);
    }

    public static void cancelPendingWrite(ServerConnection connection, String world, int cx, int cz) {
        locks.cancelPendingWrite(connection, world, cx, cz);
    }

    public static void waitForLock(String world, int cx, int cz, Runnable callback) {
        locks.waitForLock(world, cx, cz, callback);
    }

    public static CompletableFuture<Void> waitForLockAsync(String world, int cx, int cz) {
        return locks.waitForLockAsync(world, cx, cz);
    }

    public static CompletableFuture<Void> releaseAll(ServerConnection connection) {
        return locks.releaseAll(connection);
    }

}
