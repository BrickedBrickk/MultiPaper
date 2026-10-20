package puregero.multipaper.server;

public class EntitiesLockManager {

    private static final WriteLockManager locks = new WriteLockManager();

    public static void lockUntilWrite(ServerConnection connection, String world, int cx, int cz) {
        locks.lockUntilWrite(connection, world, cx, cz);
    }

    public static void writeCompleted(ServerConnection connection, String world, int cx, int cz) {
        locks.writeCompleted(connection, world, cx, cz);
    }

    public static void waitForLock(String world, int cx, int cz, Runnable callback) {
        locks.waitForLock(world, cx, cz, callback);
    }

    public static void releaseAll(ServerConnection connection) {
        locks.releaseAll(connection);
    }

}
