package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ForceReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.server.ChunkLockManager;
import puregero.multipaper.server.EntitiesLockManager;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.util.RegionFileCache;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Like ReadChunkHandler, but forces a read and won't redirect to another server that already has it loaded.
 */
public class ForceReadChunkHandler {
    public static void handle(ServerConnection connection, ForceReadChunkMessage message) {
        Runnable read = () -> {
            RegionFileCache.getChunkDeflatedDataAsync(getWorldDir(message.world, message.path), message.cx, message.cz).thenAccept(b -> {
                if (b == null) {
                    b = new byte[0];
                }
                if (message.path.equals("region") || message.path.equals("entities")) {
                    try {
                        b = puregero.multipaper.server.ContainerTransactionManager.applyForRead(message.world, message.path, message.cx, message.cz, b);
                    } catch (IOException failure) {
                        System.err.println("Could not recover container transactions into chunk " + message.world + " " + message.cx + "," + message.cz);
                        failure.printStackTrace();
                        connection.getChannel().close();
                        return;
                    }
                }
                connection.sendReply(new DataMessageReply(b), message);
            });
        };

        if (message.path.equals("region")) {
            ChunkLockManager.readWhenSafe(message.world, message.cx, message.cz, read, () -> {
                System.err.println("Refusing forced region read after a failed authoritative chunk save: world="
                        + message.world + " chunk=" + message.cx + "," + message.cz
                        + " requester=" + connection.getBungeeCordName());
                // No failure reply exists in the current protocol. Closing the
                // requester is safer than returning a stale chunk as authoritative.
                connection.getChannel().close();
            });
        } else if (message.path.equals("entities")) {
            EntitiesLockManager.waitForLock(message.world, message.cx, message.cz, read);
        } else {
            read.run();
        }
    }

    static File getWorldDir(String world, String path) {
        File file = new File(world);

        if (world.endsWith("_nether")) {
            file = new File(file, "DIM-1");
        }

        if (world.endsWith("_the_end")) {
            file = new File(file, "DIM1");
        }

        return new File(file, path);
    }
}
