package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.server.*;
import puregero.multipaper.server.util.RegionFileCache;

import java.io.File;
import java.io.IOException;

public class ReadChunkHandler {
    public static void handle(ServerConnection connection, ReadChunkMessage message) {
        if (checkIfLoadedOnAnotherServer(connection, message.world, message.path, message.cx, message.cz, message)) {
            return;
        }

        Runnable callback = () -> {
            RegionFileCache.getChunkDeflatedDataAsync(getWorldDir(message.world, message.path), message.cx, message.cz).thenAccept(b -> {
                if (b == null) {
                    b = new byte[0];
                }
                if (message.path.equals("region") || message.path.equals("entities")) {
                    try {
                        b = ContainerTransactionManager.applyForRead(message.world, message.path, message.cx, message.cz, b);
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
            ChunkLockManager.readWhenSafe(message.world, message.cx, message.cz, callback, () -> {
                System.err.println("Refusing region read after a failed authoritative chunk save: world="
                        + message.world + " chunk=" + message.cx + "," + message.cz
                        + " requester=" + connection.getBungeeCordName());
                // The current protocol has no typed failed-read reply. Do not let
                // normal chunk loading consume a stale persisted snapshot.
                connection.getChannel().close();
            });
        } else if (message.path.equals("entities")) {
            EntitiesLockManager.waitForLock(message.world, message.cx, message.cz, callback);
        } else {
            callback.run();
        }
    }

    private static boolean checkIfLoadedOnAnotherServer(ServerConnection connection, String world, String path, int cx, int cz, ReadChunkMessage message) {
        if (path.equals("region")) {
            // A subscriber may still be waiting for its first chunk read. Redirect only to a
            // server that owns the chunk; redirecting between unowned subscribers can form
            // a read cycle during concurrent backend startup.
            ServerConnection alreadyLoadedChunk = ChunkSubscriptionManager.getOwner(world, cx, cz);
            ChunkSubscriptionManager.subscribe(connection, world, cx, cz);
            if (alreadyLoadedChunk != null && alreadyLoadedChunk != connection) {
                connection.sendReply(new ChunkLoadedOnAnotherServerMessage(alreadyLoadedChunk.getBungeeCordName()), message);
                return true;
            }
        }

        if (path.equals("entities")) {
            ServerConnection alreadyLoadedEntities = EntitiesSubscriptionManager.getSubscriber(world, cx, cz);
            if (alreadyLoadedEntities != null && alreadyLoadedEntities != connection) {
                connection.sendReply(new ChunkLoadedOnAnotherServerMessage(alreadyLoadedEntities.getBungeeCordName()), message);
                return true;
            }
        }

        return false;
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
