package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestEntityIdBlock;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.IntegerPairMessageReply;
import puregero.multipaper.server.ServerConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestEntityIdBlockHandler {
    private static final int BLOCK_SIZE = Integer.getInteger("entityid.block.size", 4096);
    private static AtomicInteger lastBlock = null;
    private static volatile boolean queueLastBlockSave = true;
    private static CompletableFuture<Void> lastBlockWriteInProgress = CompletableFuture.completedFuture(null);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RequestEntityIdBlockHandler::saveLastBlock));
    }

    public static void handle(ServerConnection connection, RequestEntityIdBlock message) {
        if (lastBlock == null) {
            loadLastBlock();
        }

        int blockStart = lastBlock.getAndAdd(BLOCK_SIZE);
        connection.sendReply(new IntegerPairMessageReply(blockStart, blockStart + BLOCK_SIZE), message);

        queueLastBlockSave = true;
        if (lastBlockWriteInProgress.isDone()) {
            lastBlockWriteInProgress = lastBlockWriteInProgress.thenRunAsync(RequestEntityIdBlockHandler::saveLastBlock);
        }
    }

    private static synchronized void loadLastBlock() {
        if (lastBlock != null) {
            // Already been loaded by another thread
            return;
        }

        try {
            String str = Files.readString(lastBlockFile());
            // Entity id zero is reserved by Minecraft to mean "not assigned".
            lastBlock = new AtomicInteger(Math.max(Integer.parseInt(str), 1));
            return;
        } catch (NoSuchFileException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Entity id zero is reserved by Minecraft to mean "not assigned".
        lastBlock = new AtomicInteger(1);
    }

    private static Path lastBlockFile() {
        return Path.of(System.getProperty("multipaper.entityid.file", "lastblock.txt"));
    }

    private static synchronized void saveLastBlock() {
        if (queueLastBlockSave) {
            queueLastBlockSave = false;
            try {
                Files.writeString(lastBlockFile(), Integer.toString(lastBlock.get()));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (queueLastBlockSave) {
            // Has been requeued during the I/O operation
            saveLastBlock();
        }
    }
}
