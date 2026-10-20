package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.server.*;
import puregero.multipaper.server.util.RegionFileCache;

import java.io.File;

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
                // An entity subscriber is a valid peer read source only once its
                // initial entity data is available. Register before replying so
                // subsequent reads can safely be redirected to this connection.
                if (message.path.equals("entities")) {
                    EntitiesSubscriptionManager.subscribe(connection, message.world, message.cx, message.cz);
                }
                connection.sendReply(new DataMessageReply(b), message);
            });
        };

        if (message.path.equals("region")) {
            ChunkLockManager.waitForLock(message.world, message.cx, message.cz, callback);
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
                ChunkSubscriptionManager.debug("read " + world + "," + path + "," + cx + "," + cz + " by " + connection.getBungeeCordName() + " -> peer " + alreadyLoadedChunk.getBungeeCordName()); // MultiPaper
                connection.sendReply(new ChunkLoadedOnAnotherServerMessage(alreadyLoadedChunk.getBungeeCordName()), message);
                return true;
            }
            ChunkSubscriptionManager.debug("read " + world + "," + path + "," + cx + "," + cz + " by " + connection.getBungeeCordName() + " -> disk"); // MultiPaper
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
